package dev.cartridge.scraping

import android.util.AtomicFile
import dev.cartridge.library.LibraryGame
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One manifest and one candidate file per game: only the current title's candidates need RAM. */
class FileScrapeJournal(private val directory: File) : ScrapeJournal {
    private class Guard {
        val mutex = Mutex()
        var savedSequence = 0L
        var resetSequence = 0L
        val matchSequences = mutableMapOf<String, Long>()
    }
    private val guard = guards.getOrPut(directory.absolutePath) { Guard() }
    private fun candidateFile(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$id.json")
    }
    private fun read(file: File): String? {
        if (!file.exists() && !File("${file.path}.bak").exists()) return null
        require(file.length() <= 8L * 1024 * 1024) { "Saved search data is too large" }
        return AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size().toLong() + count <= 8L * 1024 * 1024) { "Saved search data is too large" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
    private fun write(file: File, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "This search batch is too large to save" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create scrape journal" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    override suspend fun load(): ScrapeState? = withContext(Dispatchers.IO) { guard.mutex.withLock {
        val root = read(File(directory, "manifest.json"))?.let(::JSONObject) ?: return@withLock null
        require(root.getInt("version") in 1..2) { "Unknown saved scrape format" }
        val entries = root.getJSONArray("entries").objects().map { item ->
            ScrapeEntry(LibraryGame(item.getString("id"), item.getString("title"), item.getString("source")),
                item.getString("query"), error = item.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                searched = item.getBoolean("searched"), corrected = item.optBoolean("corrected"),
                outcome = ScrapeOutcome.valueOf(item.optString("outcome", "Pending")))
        }
        if (entries.isEmpty()) return@withLock null
        val index = root.getInt("index").coerceIn(entries.indices)
        val entry = entries[index]
        val matches = if (entry.searched && entry.error == null) readMatches(entry.game.id) else null
        ScrapeState(entries = entries.toMutableList().also {
            it[index] = entry.copy(matches = matches.orEmpty(), searched = entry.searched && (matches != null || entry.error != null))
        }, index = index, ready = true)
    } }
    override suspend fun save(state: ScrapeState) {
        val sequence = nextSequence.incrementAndGet() // Captured before IO; late older writes cannot replace newer state.
        withContext(NonCancellable + Dispatchers.IO) { guard.mutex.withLock {
            if (sequence < guard.savedSequence) return@withLock
            val entries = JSONArray().apply { state.entries.forEach { entry -> put(JSONObject()
                .put("id", entry.game.id).put("title", entry.game.title).put("source", entry.game.sourceUri)
                .put("query", entry.query).put("error", entry.error).put("searched", entry.searched).put("corrected", entry.corrected).put("outcome", entry.outcome.name)) } }
            write(File(directory, "manifest.json"), JSONObject().put("version", 2).put("index", state.index).put("entries", entries).toString())
            guard.savedSequence = sequence
        } }
    }
    override suspend fun saveMatches(gameId: String, matches: List<GameCandidate>) {
        val sequence = nextSequence.incrementAndGet()
        withContext(NonCancellable + Dispatchers.IO) { guard.mutex.withLock {
            if (sequence < guard.resetSequence || sequence < (guard.matchSequences[gameId] ?: 0)) return@withLock
            write(candidateFile(gameId), JSONArray().apply { matches.forEach { put(json(it)) } }.toString())
            guard.matchSequences[gameId] = sequence
        } }
    }
    override suspend fun matches(gameId: String) = withContext(Dispatchers.IO) { guard.mutex.withLock { readMatches(gameId) } }
    private fun readMatches(id: String): List<GameCandidate>? = read(candidateFile(id))?.let { JSONArray(it).objects().map(::candidate) }
    override suspend fun reset() {
        val sequence = nextSequence.incrementAndGet()
        withContext(NonCancellable + Dispatchers.IO) { guard.mutex.withLock {
            if (sequence < guard.savedSequence || sequence < guard.resetSequence) return@withLock
            // Only generated private journal files; never source documents or accepted artwork.
            directory.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) { "Cannot reset the saved scrape batch" } }
            guard.resetSequence = sequence
            guard.savedSequence = sequence
            guard.matchSequences.clear()
        } }
    }
    private fun json(game: GameCandidate) = JSONObject().put("id", game.id).put("title", game.title)
        .put("platform", game.platform).put("names", JSONArray(game.names)).put("url", game.sourceUrl)
        .put("license", game.license).put("exact", game.exactHash).put("metadata", JSONObject().apply {
            game.metadata.forEach { (field, values) -> put(field, JSONArray(values)) }
        }).put("tags", JSONObject().apply { game.metadataTags.forEach { (field, tags) -> put(field, JSONArray().apply {
            tags.forEach { put(JSONObject().put("region", it.region).put("language", it.language)) }
        }) } }).put("artwork", JSONArray().apply { game.artwork.forEach { put(JSONObject().put("type", it.type)
            .put("region", it.region).put("url", it.url).put("checksum", it.checksum)) } })
    private fun candidate(json: JSONObject): GameCandidate {
        val metadata = json.getJSONObject("metadata")
        return GameCandidate(json.getString("id"), json.getString("title"), json.getString("platform"),
            json.getJSONArray("names").strings(), metadata.keys().asSequence().associateWith { metadata.getJSONArray(it).strings() },
            json.getJSONArray("artwork").objects().map { ArtworkCandidate(it.getString("type"), it.getString("region"),
                it.getString("url"), it.optString("checksum").takeIf { value -> value.isNotEmpty() && value != "null" }) },
            json.getString("url"), json.getString("license"), json.getBoolean("exact"),
            json.optJSONObject("tags")?.let { tags -> tags.keys().asSequence().associateWith { field ->
                tags.getJSONArray(field).objects().map { MetadataTags(it.optString("region"), it.optString("language")) }
            } } ?: emptyMap())
    }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    companion object {
        private val guards = ConcurrentHashMap<String, Guard>()
        private val nextSequence = AtomicLong()
    }
}
