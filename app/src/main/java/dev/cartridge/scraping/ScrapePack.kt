package dev.cartridge.scraping

import android.content.ContentResolver
import android.net.Uri
import dev.cartridge.storage.BoundedArchive
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.UUID

/** Versioned offline candidates. ROM hashes identify existing games, never title guesses. */
data class ScrapePack(val games: Map<String, List<GameCandidate>>, val source: String)

class ScrapePackStore(private val directory: File) {
    suspend fun import(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        importStream { checkNotNull(resolver.openInputStream(uri)) { "The pack is unavailable" } }
    }
    suspend fun importStream(open: () -> InputStream): ScrapePack = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create pack storage" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(directory, "$id.tmp").apply { check(mkdir()) }
        val taskContext = coroutineContext
        try {
            val files = open().use { BoundedArchive.read(it, staging) { taskContext.ensureActive() } }
            val root = JSONObject(checkNotNull(files["pack.json"]) { "Missing pack.json" }.readText(Charsets.UTF_8))
            require(root.getString("format") == "project-cartridge.scrape-pack" && root.getInt("version") == 1) { "Unsupported scrape-pack format/version" }
            val source = root.text("provider", "Imported pack", 180)
            val licence = root.text("license", "Licence not supplied; artwork rights remain with their owners", 2000)
            val store = LabelStore(staging)
            val used = mutableSetOf("pack.json")
            val assets = root.getJSONArray("assets").objects(1024).associate { asset ->
                taskContext.ensureActive()
                val key = asset.getString("id")
                require(key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid pack asset ID" }
                val path = asset.getString("path")
                require(path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")) { "Packs contain images only" }
                val file = checkNotNull(files[path]) { "Missing image: $path" }; used += path
                val expected = asset.getString("sha256")
                require(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid image checksum" }
                val label = store.importStream("Imported pack") { file.inputStream() }
                require(label.assetId == expected) { "Image checksum mismatch: $path" }
                key to "cartridge-pack://$id/${label.assetId}.image"
            }
            require(assets.size == root.getJSONArray("assets").length()) { "Duplicate pack asset ID" }
            require(files.keys == used) { "Pack contains undeclared files" }
            val records = root.getJSONArray("games").objects(1000)
            val games = records.associate { game ->
                val hash = game.getString("romSha256")
                require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid ROM hash" }
                val matches = game.getJSONArray("matches").objects(16).map { match ->
                    val platform = match.text("platform", "Game Boy Advance", 80)
                    require(dev.cartridge.domain.GameSystem.entries.any { it.provider == platform }) { "This pack contains an unsupported system" }
                    val title = match.text("title", "", 180); require(title.isNotBlank()) { "Missing candidate title" }
                    val metadata = linkedMapOf<String, List<String>>(); val tags = linkedMapOf<String, List<MetadataTags>>()
                    match.optJSONObject("metadata")?.let { values -> values.keys().forEach { field ->
                        require(field in setOf("title", "year", "developer", "publisher", "genre", "description")) { "Unsupported metadata field" }
                        val variants = values.getJSONArray(field).objects(16)
                        metadata[field] = variants.map { it.text("value", "", 16384).also { value -> require(value.isNotBlank()) } }
                        tags[field] = variants.map { MetadataTags(it.text("region", "", 16), it.text("language", "", 16)) }
                    } }
                    val artwork = match.getJSONArray("media").objects(64).map { media ->
                        val type = media.getString("type")
                        require(type in setOf("support-texture", "support-2D", "box-2D", "box-texture", "ss", "screenshot", "sstitle", "wheel")) { "Unsupported media type" }
                        ArtworkCandidate(type, media.text("region", "", 16), checkNotNull(assets[media.getString("asset")]) { "Unknown asset reference" },
                            originalUrl = media.text("sourceUrl", "", 4096).takeIf(String::isNotBlank))
                    }.distinctBy { it.url }
                    GameCandidate(match.text("id", "", 180).also { require(it.isNotBlank()) }, title, platform, listOf(title), metadata, artwork,
                        match.text("sourceUrl", "", 4096), match.text("license", licence, 2000), exactHash = true, metadataTags = tags, sourceName = source)
                }
                require(matches.isNotEmpty() && matches.map { it.id }.distinct().size == matches.size) { "Empty or duplicate candidates" }
                hash to matches
            }
            require(games.isNotEmpty() && games.size == records.size) { "Empty or duplicate game hashes" }
            files.values.forEach { check(it.delete()) }
            taskContext.ensureActive()
            check(staging.renameTo(File(directory, id))) { "Cannot retain imported pack" }
            ScrapePack(games, source)
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }
    suspend fun image(url: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "cartridge-pack" && uri.host?.matches(Regex("[a-f0-9]{32}")) == true &&
            uri.rawPath.matches(Regex("/[a-f0-9]{64}\\.image")) && uri.query == null && uri.fragment == null && uri.userInfo == null && uri.port == -1) { "Invalid pack media reference" }
        val file = File(File(directory, uri.host), uri.path.substring(1))
        require(file.isFile && file.length() in 1..12L * 1024 * 1024) { "Pack image missing; import the pack again" }
        file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 12 * 1024 * 1024); output.write(buffer, 0, count) }
            output.toByteArray()
        }
    }
    private fun JSONObject.text(key: String, fallback: String, maximum: Int): String {
        val value = if (!has(key) || isNull(key)) fallback else getString(key)
        require(value.length <= maximum) { "Pack field exceeds supported size: $key" }
        return value
    }
    private fun JSONArray.objects(maximum: Int): List<JSONObject> { require(length() <= maximum) { "Too many pack records" }; return (0 until length()).map(::getJSONObject) }
}

/** Local URLs can only read validated private packs. All online calls retain the audited provider. */
class PackAwareProvider(private val online: ArtworkProvider, private val packs: ScrapePackStore) : ArtworkProvider {
    override val name get() = online.name
    override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem) = online.search(query, rom, system)
    override suspend fun image(candidate: ArtworkCandidate) = if (candidate.url.startsWith("cartridge-pack://")) packs.image(candidate.url) else online.image(candidate)
}
