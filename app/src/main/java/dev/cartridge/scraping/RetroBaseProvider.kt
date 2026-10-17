package dev.cartridge.scraping

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Public API v1/OpenAPI inspected 2026-10-09. No ROM bytes, accounts or passwords are transmitted. */
class RetroBaseProvider(private val cache: File) : ArtworkProvider {
    override val name = "RetroBase Collection"
    private val requests = Mutex()
    private var lastRequest = 0L
    private val base = "https://retrobase-collection.com/api/public/v1"
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem): List<GameCandidate> = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..180) { "Enter a title between 2 and 180 characters" }
        val exact = if (rom?.isFile == true) {
            val digest = MessageDigest.getInstance("SHA-1")
            rom.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val response = json("$base/lookup?sha1=$hash&size=${rom.length()}&platform=${encode(system.provider)}", allowMissing = true)
            response?.optJSONObject("data")?.optJSONObject("game")?.let { parseGame(it, response.optJSONObject("meta"), true, system) }
        } else null
        val response = json("$base/games?search=${encode(query.trim())}&platform=${encode(system.provider)}&limit=30")
        val array = response?.optJSONArray("data")
        val choices = buildList {
            exact?.let(::add)
            if (array != null) repeat(array.length()) { index ->
                array.optJSONObject(index)?.let { parseGame(it, response.optJSONObject("meta"), system = system) }?.let(::add)
            }
        }.distinctBy { it.id }
        choices.sortedWith(compareByDescending<GameCandidate> { it.exactHash }
            .thenByDescending { candidate -> candidate.names.any { normalized(it) == normalized(query) } }
            .thenByDescending { it.artwork.any(ArtworkCandidate::isCaseArt) })
    }

    internal fun parseGame(game: JSONObject, meta: JSONObject?, exact: Boolean = false, system: dev.cartridge.domain.GameSystem = dev.cartridge.domain.GameSystem.GBA): GameCandidate? {
        val platform = game.optJSONObject("platform")?.optString("name").orEmpty()
        if (platform != system.provider) return null
        val id = game.optString("id")
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        val regional = game.optJSONObject("names")
        val names = buildList {
            listOf("us", "eu", "ss", "wor", "jp").forEach { region -> regional?.text(region)?.let(::add) }
            game.text("name")?.let(::add)
            regional?.keys()?.forEach { regional.text(it)?.let(::add) }
        }.distinct()
        if (names.isEmpty()) return null
        val metadata = linkedMapOf("title" to names)
        val tags = linkedMapOf("title" to names.map { name ->
            MetadataTags(region = regional?.keys()?.asSequence()?.firstOrNull { regional.text(it) == name }.orEmpty()) })
        for (field in listOf("year", "developer", "publisher", "genre")) game.text(field)?.let { metadata[field] = listOf(it) }
        val synopses = game.optJSONObject("synopses")
        val descriptions = buildList {
            synopses?.text("en")?.let(::add)
            game.text("synopsis")?.let(::add)
            synopses?.keys()?.forEach { synopses.text(it)?.let(::add) }
        }.distinct()
        if (descriptions.isNotEmpty()) metadata["description"] = descriptions
        if (descriptions.isNotEmpty()) tags["description"] = descriptions.map { description ->
            MetadataTags(language = synopses?.keys()?.asSequence()?.firstOrNull { synopses.text(it) == description }.orEmpty()) }
        val media = game.optJSONArray("media")
        val artwork = buildList {
            if (media != null) repeat(media.length()) { index ->
                val item = media.optJSONObject(index) ?: return@repeat
                val type = item.optString("type")
                if (type !in setOf("support-texture", "support-2D", "box-2D", "box-texture", "ss", "screenshot", "sstitle", "wheel")) return@repeat
                val url = item.optString("url")
                if (allowedImage(url)) add(ArtworkCandidate(type, item.optString("region"), url, item.text("hash")))
            }
        }.distinctBy { it.url }.sortedWith(compareByDescending<ArtworkCandidate> { it.isCaseArt }.thenBy { it.region })
        return GameCandidate(id, names.first(), platform, names, metadata, artwork,
            "$base/games/$id", meta?.text("license") ?: "Provider licence not supplied", exact, tags)
    }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().take(16_384).takeIf { it.isNotEmpty() && it != "null" }
    private fun normalized(value: String) = value.lowercase().replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "")
        .replace(Regex("[^a-z0-9]"), "")
    private fun allowedImage(url: String) = url.length <= 4096 && runCatching { URI(url).let {
        it.scheme == "https" && it.host == "retrocollection.s3.fr-par.scw.cloud" && it.userInfo == null && it.port == -1
    } }.getOrDefault(false)

    override suspend fun image(candidate: ArtworkCandidate): ByteArray = withContext(Dispatchers.IO) {
        require(allowedImage(candidate.url)) { "Unsupported provider image host" }
        fetch(candidate.url, 12 * 1024 * 1024) ?: error("This image is unavailable")
    }
    private suspend fun json(url: String, allowMissing: Boolean = false): JSONObject? = requests.withLock {
        withContext(Dispatchers.IO) {
            cache.mkdirs()
            val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(cache, "$id.json")
            if (file.isFile && System.currentTimeMillis() - file.lastModified() in 0..3_600_000 && file.length() <= 8 * 1024 * 1024) {
                val cached = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (cached != null && !cached.has("error")) return@withContext cached
                file.delete() // Candidate cache is disposable; a corrupt file must not make retries fail for an hour.
            }
            val elapsed = System.nanoTime() / 1_000_000 - lastRequest
            if (elapsed < 1100) delay(1100 - elapsed) // Anonymous quota is 60 requests/minute.
            lastRequest = System.nanoTime() / 1_000_000
            val bytes = fetch(url, 8 * 1024 * 1024, allowMissing) ?: return@withContext null
            val parsed = JSONObject(bytes.toString(Charsets.UTF_8))
            if (parsed.has("error")) error("The provider could not complete this search")
            val temporary = File(cache, "$id.tmp")
            try { temporary.writeBytes(bytes); check(temporary.renameTo(file)) { "Cannot cache provider results" } }
            finally { temporary.delete() }
            // Disposable candidate cache, independently bounded from accepted assets.
            var retainedBytes = 0L
            cache.listFiles { candidate -> candidate.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.forEachIndexed { index, candidate ->
                    retainedBytes += candidate.length()
                    if (index >= 32 || retainedBytes > 32L * 1024 * 1024) candidate.delete()
                }
            parsed
        }
    }
    private suspend fun fetch(url: String, limit: Int, allowMissing: Boolean = false): ByteArray? {
        coroutineContext.ensureActive()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "ProjectCartridge/0.1 (Android; personal collection)")
        try {
            val status = connection.responseCode
            if (status == 404 && allowMissing) return null
            if (status == 429) error("Provider rate limit reached. Retry after ${connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600) ?: 60} seconds.")
            check(status in 200..299) { "Provider request failed (HTTP $status). Retry when the service is available." }
            require(connection.contentLengthLong <= limit) { "Provider response is too large" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size().toLong() + count <= limit) { "Provider response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
