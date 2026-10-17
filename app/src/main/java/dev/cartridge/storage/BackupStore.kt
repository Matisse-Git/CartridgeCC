package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import dev.cartridge.library.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read-only review owns generated staging paths. Archive entry names are never extraction paths. */
class BackupReview internal constructor(internal val directory: File, internal val files: Map<String, File>,
    val library: LibraryBackup, val preferences: JSONObject, internal val blobs: List<BackupBlob>,
    internal val session: ResumeSession?) : Closeable {
    val imageCount get() = blobs.count { it.kind == "image" }
    val saveCount get() = blobs.count { it.kind in setOf("sram", "rtc", "state", "slot") }
    override fun close() { directory.deleteRecursively() }
}
/** [slot] and [savedAt] belong to a game's listed states and their pictures (`slot` and `slot-image`). */
internal data class BackupBlob(val path: String, val sha: String, val bytes: Long, val id: String, val kind: String, val core: String? = null,
    val slot: String? = null, val savedAt: Long? = null)

/**
 * [includeSlots] adds every game's listed states, the quick save each last replaced, and their pictures; the quick save
 * itself is always in a backup. A backup without them restores as before, and one with them merges only what is missing.
 */
class BackupStore(private val repository: BackupRepository, private val labels: LabelStore, private val saves: SaveStore,
    private val sessions: SessionStore, private val staging: File, private val includeSlots: Boolean = false) {
    private val slots = StateSlots(saves.directory)
    suspend fun export(output: OutputStream, preferences: JSONObject) {
        BackupPreferences.validate(preferences)
        val library = repository.exportLibrary()
        require(library.games.size <= 10_000) { "Backup supports at most 10,000 games" }
        val temporary = stage()
        try {
            val blobs = mutableListOf<BackupBlob>(); val files = linkedMapOf<String, File>()
            val images = library.games.flatMap { game -> listOfNotNull(game.labelAssetId) + LibraryJson.media(game.mediaJson).values.map { it.image.assetId } }.toSet()
            images.forEach { id ->
                currentCoroutineContext().ensureActive()
                val file = labels.exportOriginal(id); labels.validateImage(file)
                check(hash(file) == id) { "An accepted image failed its integrity check" }
                val path = "images/$id.image"; blobs.add(BackupBlob(path, id, file.length(), id, "image")); files[path] = file
            }
            library.games.forEach { game ->
                for (kind in listOf("sram", "rtc", "state")) {
                    currentCoroutineContext().ensureActive()
                    saves.read(game.id, kind)?.let { bytes ->
                        val path = "saves/${game.id}.$kind"; val file = File(temporary, "${game.id}.$kind").apply { writeBytes(bytes) }
                        blobs.add(BackupBlob(path, hash(file), file.length(), game.id, kind, if (kind == "state") GameSystem.valueOf(game.system).coreRevision else null)); files[path] = file
                    }
                }
                if (includeSlots) slots.backupEntries(game.id, GameSystem.valueOf(game.system)).forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    entry.state?.let { state ->
                        val path = "slots/${game.id}/${entry.name}.state"
                        blobs.add(BackupBlob(path, hash(state), state.length(), game.id, "slot", entry.core, entry.name, entry.savedAt)); files[path] = state
                    }
                    entry.thumbnail?.let { picture ->
                        val path = "slots/${game.id}/${entry.name}.jpg"
                        blobs.add(BackupBlob(path, hash(picture), picture.length(), game.id, "slot-image", null, entry.name)); files[path] = picture
                    }
                }
            }
            val session = sessions.available()?.takeIf { saved -> library.games.any { it.id == saved.id && it.system == saved.system.name } }
            session?.let {
                val file = File(temporary, "session.state").apply { writeBytes(sessions.read(it)) }
                val path = "session/state.bin"; blobs.add(BackupBlob(path, hash(file), file.length(), it.id, "session")); files[path] = file
            }
            val root = encodeLibrary(library).put("format", FORMAT).put("version", 1).put("core", SessionStore.CORE).put("settings", preferences)
                .put("blobs", JSONArray(blobs.map { JSONObject().put("path", it.path).put("sha256", it.sha).put("bytes", it.bytes).put("id", it.id).put("kind", it.kind).put("core", it.core)
                    .apply { it.slot?.let { slot -> put("slot", slot) }; it.savedAt?.let { time -> put("savedAt", time) } } }))
            session?.let { root.put("session", JSONObject().put("id", it.id).put("system", it.system.name).put("savedAt", it.savedAt).put("core", it.system.coreRevision)) }
            val manifest = root.toString().toByteArray(Charsets.UTF_8)
            require(manifest.size <= LIMITS.manifest && blobs.size + 1 <= LIMITS.entries && blobs.sumOf { it.bytes } + manifest.size <= LIMITS.expanded) { "Backup exceeds supported limits" }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json")); zip.write(manifest); zip.closeEntry()
                files.forEach { (path, file) ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } finally { temporary.deleteRecursively() }
    }
    suspend fun inspect(input: InputStream): BackupReview {
        val directory = stage()
        try {
            val task = currentCoroutineContext()
            val files = BoundedArchive.read(input, directory, LIMITS) { task.ensureActive() }
            val root = JSONObject(checkNotNull(files["backup.json"]) { "Not a Cartridge backup" }.readText(Charsets.UTF_8))
            require(root.getString("format") == FORMAT && root.getInt("version") == 1) { "Unsupported backup version" }
            val library = decodeLibrary(root)
            val settings = root.getJSONObject("settings").also(BackupPreferences::validate)
            val array = root.getJSONArray("blobs"); require(array.length() < LIMITS.entries)
            val blobs = (0 until array.length()).map { index -> array.getJSONObject(index).let {
                BackupBlob(it.getString("path"), it.getString("sha256"), it.getLong("bytes"), it.getString("id"), it.getString("kind"), if (it.has("core")) it.getString("core") else null,
                    if (it.has("slot")) it.getString("slot") else null, if (it.has("savedAt")) it.getLong("savedAt") else null) } }
            if (blobs.any { it.kind in setOf("state", "session") }) require(root.getString("core") == SessionStore.CORE) { "Saved states use a different core revision" }
            require(blobs.map { it.path }.distinct().size == blobs.size && files.keys == (blobs.map { it.path } + "backup.json").toSet()) { "Undeclared or duplicate backup data" }
            val ids = library.games.associateBy { it.id }
            val images = library.games.flatMap { listOfNotNull(it.labelAssetId) + LibraryJson.media(it.mediaJson).values.map { m -> m.image.assetId } }.toSet()
            require(images == blobs.filter { it.kind == "image" }.map { it.id }.toSet()) { "Backup images do not match the library" }
            blobs.forEach { blob ->
                task.ensureActive()
                require(blob.id.matches(HASH) && blob.sha.matches(HASH)) { "Invalid backup identity" }
                if (blob.kind == "state") require((blob.core ?: root.getString("core")) == GameSystem.valueOf(checkNotNull(ids[blob.id]).system).coreRevision) { "Saved state uses a different console core revision" }
                val expected = when(blob.kind) {
                    "image" -> "images/${blob.id}.image"
                    "sram", "rtc", "state" -> { require(blob.id in ids); "saves/${blob.id}.${blob.kind}" }
                    "session" -> "session/state.bin"
                    "slot" -> {
                        val slot = checkNotNull(blob.slot); val system = GameSystem.valueOf(checkNotNull(ids[blob.id]) { "Undeclared game" }.system)
                        require((slot == StateSlots.PREVIOUS || slot.matches(StateSlots.UUID_NAME)) && (blob.savedAt ?: -1) >= 0)
                        require(blob.core == system.coreRevision) { "Saved state uses a different console core revision" }
                        "slots/${blob.id}/$slot.state"
                    }
                    "slot-image" -> {
                        val slot = checkNotNull(blob.slot); require(blob.id in ids)
                        // A picture belongs to a state in the same backup: the quick save, or the listed state of its name.
                        require(if (slot == StateSlots.QUICK) blobs.any { it.kind == "state" && it.id == blob.id }
                            else blobs.any { it.kind == "slot" && it.id == blob.id && it.slot == slot }) { "A save picture has no save" }
                        "slots/${blob.id}/$slot.jpg"
                    }
                    else -> error("Unsupported backup data")
                }
                require(blob.path == expected)
                val file = checkNotNull(files[blob.path]); val limit = when(blob.kind) { "image" -> 12L * 1024 * 1024; "sram" -> 128L * 1024; "rtc" -> 4096L
                    "slot-image" -> StateSlots.MAX_THUMBNAIL.toLong(); else -> SessionStore.MAX_STATE.toLong() }
                require(blob.bytes in 0..limit && file.length() == blob.bytes && hash(file) == blob.sha) { "Backup data failed its integrity check" }
                if (blob.kind in setOf("image", "state", "session", "slot", "slot-image")) require(blob.bytes > 0)
                if (blob.kind == "image") { require(blob.sha == blob.id); labels.validateImage(file) }
                if (blob.kind == "slot-image") require(StateSlots.isJpeg(file.readBytes())) { "A save picture is not an image" }
            }
            val session = root.optJSONObject("session")?.let {
                require(it.getString("core") == GameSystem.valueOf(it.getString("system")).coreRevision) { "The saved session uses a different core revision" }
                ResumeSession(it.getString("id"), GameSystem.valueOf(it.getString("system")), it.getLong("savedAt")).also { value ->
                    require(value.savedAt >= 0 && ids[value.id]?.system == value.system.name && blobs.singleOrNull { b -> b.kind == "session" }?.id == value.id)
                }
            }
            require((session != null) == blobs.any { it.kind == "session" })
            return BackupReview(directory, files, library, settings, blobs, session)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    suspend fun merge(review: BackupReview): String {
        val existing = repository.exportLibrary().games.associateBy { it.id }
        require(review.library.games.all { existing[it.id]?.system?.let { system -> system == it.system } ?: true }) { "Conflicting console identity in backup" }
        // Validate all bytes again before accepting; a review never authorizes altered staging data.
        review.blobs.forEach { require(hash(checkNotNull(review.files[it.path])) == it.sha) { "The reviewed backup changed" } }
        review.blobs.filter { it.kind == "image" }.forEach { blob ->
            val imported = labels.importStream("backup:${blob.id}") { checkNotNull(review.files[blob.path]).inputStream() }
            check(imported.assetId == blob.id)
        }
        var restoredSaves = 0
        review.blobs.filter { it.kind in setOf("sram", "rtc", "state") }.forEach {
            if (saves.writeMissing(it.id, it.kind, checkNotNull(review.files[it.path]).readBytes())) restoredSaves++
        }
        val systems = review.library.games.associate { it.id to GameSystem.valueOf(it.system) }
        val pictures = review.blobs.filter { it.kind == "slot-image" }.associateBy { it.id to it.slot }
        review.blobs.filter { it.kind == "slot" }.forEach { blob ->
            val picture = pictures[blob.id to blob.slot]?.let { checkNotNull(review.files[it.path]).readBytes() }
            if (slots.importMissing(blob.id, systems.getValue(blob.id), checkNotNull(blob.slot), checkNotNull(review.files[blob.path]).readBytes(),
                    checkNotNull(blob.savedAt), picture)) restoredSaves++
        }
        pictures.values.filter { it.slot == StateSlots.QUICK }.forEach { blob ->
            slots.importMissing(blob.id, systems.getValue(blob.id), StateSlots.QUICK, null, 0, checkNotNull(review.files[blob.path]).readBytes())
        }
        val added = repository.mergeLibrary(review.library)
        review.session?.let { sessions.writeMissing(it, checkNotNull(review.files["session/state.bin"]).readBytes()) }
        return "Restored $added new games and $restoredSaves missing saves. Existing choices kept. Choose ROM folders again on another device."
    }
    private fun stage(): File {
        check(staging.isDirectory || staging.mkdirs())
        return File(staging, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }
    companion object {
        const val FORMAT = "project-cartridge.backup"
        private val HASH = Regex("[a-f0-9]{64}")
        private val LIMITS = BoundedArchive.Limits(compressed = 1024L * 1024 * 1024, expanded = 1024L * 1024 * 1024,
            entries = 60_001, entry = SessionStore.MAX_STATE, manifestName = "backup.json", manifest = 32 * 1024 * 1024)
        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun encodeLibrary(library: LibraryBackup): JSONObject = JSONObject().put("games", JSONArray(library.games.map { game -> JSONObject()
            .put("id", game.id).put("title", game.title).put("sourceUri", game.sourceUri).put("system", game.system)
            .put("label", game.labelAssetId).put("fit", game.labelFit).put("turns", game.labelQuarterTurns)
            .put("labelSource", game.labelSourceUri).put("labelProvenance", game.labelProvenance)
            .put("metadata", game.metadataJson).put("media", game.mediaJson).put("favorite", game.favorite)
            .put("playMs", game.playMs).put("lastPlayed", game.lastPlayed) }))
            .put("order", JSONArray(library.order)).put("removed", JSONArray(library.removed.toList()))
            .put("collection", JSONObject().put("selected", library.state.selectedSlot).put("sort", library.state.sort)
                .put("favorites", library.state.favoritesOnly).put("search", library.state.search))
        private fun decodeLibrary(root: JSONObject): LibraryBackup {
            fun optional(json: JSONObject, key: String, maximum: Int = 4096) = if (json.isNull(key)) null else json.getString(key).also { require(it.length <= maximum) }
            val array = root.getJSONArray("games"); require(array.length() <= 10_000)
            val games = (0 until array.length()).map { index -> array.getJSONObject(index).let { json ->
                GameRecord(json.getString("id"), json.getString("title"), json.getString("sourceUri"), optional(json, "label"),
                    json.getString("fit"), json.getInt("turns"), optional(json, "labelSource"), optional(json, "labelProvenance", 32768),
                    optional(json, "metadata", 256 * 1024), optional(json, "media", 256 * 1024), json.getBoolean("favorite"),
                    json.getLong("playMs"), json.getLong("lastPlayed"), json.getString("system")).also { game ->
                    require(game.id.matches(HASH) && game.title.length in 1..1024 && game.sourceUri.length <= 4096 && game.sourceUri.startsWith("content://") && game.playMs >= 0 && game.lastPlayed >= 0)
                    GameSystem.valueOf(game.system); LabelFit.valueOf(game.labelFit); require(game.labelQuarterTurns in 0..3)
                    game.labelAssetId?.let { require(it.matches(HASH)) }
                    LibraryJson.provenance(game.labelProvenance)?.let { require(it.acceptedAt >= 0) }
                    val metadata = LibraryJson.metadata(game.metadataJson)
                    require(metadata.keys.all { it in dev.cartridge.library.METADATA_FIELDS } && metadata.values.all { it.value.length <= 16384 && it.acceptedAt >= 0 })
                    val media = LibraryJson.media(game.mediaJson); require(media.size <= 32 && media.keys.all { it.length in 1..64 })
                }
            } }
            val ids = games.map { it.id }.toSet(); require(ids.size == games.size)
            fun strings(key: String): List<String> { val values = root.getJSONArray(key); require(values.length() <= 10000); return (0 until values.length()).map(values::getString).also { require(it.toSet().size == it.size && it.all { id -> id in ids }) } }
            val order = strings("order"); val removed = strings("removed").toSet(); require(order.none { it in removed })
            val state = root.getJSONObject("collection").let { CollectionRecord(selectedSlot = it.getInt("selected"), sort = it.getString("sort"), favoritesOnly = it.getBoolean("favorites"), search = it.getString("search")) }
            LibrarySort.valueOf(state.sort); require(state.selectedSlot in 0..(order.size - 1).coerceAtLeast(0) && state.search.length <= 160)
            return LibraryBackup(games, order, removed, state)
        }
    }
}
