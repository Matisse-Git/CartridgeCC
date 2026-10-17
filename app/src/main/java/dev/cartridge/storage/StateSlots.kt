package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SlotKind { Quick, Previous, Saved }

/**
 * One state a game can be loaded from. [name] is `quick`, `previous` or a saved state's generated id. [compatible] is
 * false where the state is known to come from another core build; it is then shown, never loaded, and never deleted.
 */
data class StateSlot(val name: String, val kind: SlotKind, val savedAt: Long, val size: Long, val compatible: Boolean, val thumbnail: File?)

/**
 * A game's states (docs/SAVE_RELIABILITY). The quick save is the file it always was, `saves/<id>.state`, so its bytes,
 * backups and "Start from save state" are unchanged. Beside it, `saves/<id>.slots/` holds the quick save it last replaced,
 * the saved list and a thumbnail for each. A saved state is published by its record, written last: a state without one
 * is an interrupted write and is never listed. Overwriting publishes a new state before the old one is removed.
 * The bytes stored are what [ManualState.encode] made; the core revision is also in each record.
 */
class StateSlots(private val saves: File) {
    private val store = SaveStore(saves)
    private val lock = locks.computeIfAbsent(saves.canonicalPath) { Any() }
    private fun folder(id: String): File {
        require(id.matches(HASH)) { "Invalid game identifier" }
        return File(saves, "$id.slots")
    }
    private fun writable(id: String) = folder(id).apply { check(isDirectory || mkdirs()) { "Cannot create the save folder" } }
    private fun picture(id: String, name: String) = File(folder(id), "$name.jpg").takeIf { it.isFile && it.length() in 1..MAX_THUMBNAIL }

    fun list(id: String, system: GameSystem): List<StateSlot> = synchronized(lock) {
        val quick = File(saves, "$id.state")
        val previous = File(folder(id), "previous.state")
        listOfNotNull(
            quick.takeIf { it.isFile }?.let { StateSlot(QUICK, SlotKind.Quick, it.lastModified(), it.length(), envelope(system, it), picture(id, QUICK)) },
            previous.takeIf { it.isFile }?.let { StateSlot(PREVIOUS, SlotKind.Previous, it.lastModified(), it.length(), envelope(system, it), picture(id, PREVIOUS)) },
        ) + saved(id, system)
    }

    /** Saved states, newest first. A record that cannot be read is shown as an incompatible state rather than hidden. */
    private fun saved(id: String, system: GameSystem): List<StateSlot> =
        folder(id).listFiles().orEmpty().filter { it.name.matches(RECORD) }.map { file ->
            val name = file.name.removeSuffix(".properties")
            val record = runCatching { record(file) }.getOrNull()
            val state = File(folder(id), "$name.state")
            StateSlot(name, SlotKind.Saved, record?.getProperty("savedAt")?.toLongOrNull() ?: file.lastModified(), state.length(),
                record != null && record.getProperty("core") == system.coreRevision && state.length() == record.getProperty("size")?.toLongOrNull(),
                picture(id, name))
        }.sortedByDescending { it.savedAt }

    /**
     * The quick save replaces the one before it, which is kept as `previous` first: the one way back from a button
     * pressed by mistake. The old thumbnail is removed before the new state is written, so no picture ever shows
     * another moment, and a thumbnail that cannot be written leaves the state saved without one.
     */
    fun writeQuick(id: String, bytes: ByteArray, thumbnail: ByteArray?) = synchronized(lock) {
        val quick = File(saves, "$id.state")
        if (quick.isFile) {
            val folder = writable(id)
            replace(quick, File(folder, "previous.state"))
            val oldPicture = File(folder, "$QUICK.jpg")
            val previousPicture = File(folder, "$PREVIOUS.jpg")
            if (oldPicture.isFile) replace(oldPicture, previousPicture) else previousPicture.delete()
            oldPicture.delete()
        } else File(folder(id), "$QUICK.jpg").delete()
        store.write(id, "state", bytes)
        thumbnail?.let { runCatching { publishPicture(id, QUICK, it) } }
        Unit
    }

    /** A new saved state, or one that takes the place of [replacing]. Returns the name it is listed under. */
    fun write(id: String, system: GameSystem, bytes: ByteArray, thumbnail: ByteArray?, now: Long = System.currentTimeMillis(), replacing: String? = null): String = synchronized(lock) {
        require(bytes.isNotEmpty() && bytes.size <= SessionStore.MAX_STATE) { "The state is empty or too large" }
        replacing?.let { require(it.matches(UUID_NAME)) { "Only a saved state can be overwritten" } }
        val folder = writable(id)
        val name = UUID.randomUUID().toString()
        val state = File(folder, "$name.state")
        val record = File(folder, "$name.properties")
        val temporary = File.createTempFile("slot-", ".tmp", folder)
        try {
            FileOutputStream(state).use { it.write(bytes); it.fd.sync() }
            thumbnail?.let { runCatching { publishPicture(id, name, it) } }
            val values = Properties().apply {
                setProperty("version", "1"); setProperty("system", system.name); setProperty("core", system.coreRevision)
                setProperty("savedAt", now.toString()); setProperty("size", bytes.size.toString()); setProperty("sha256", hash(bytes))
            }
            FileOutputStream(temporary).use { values.store(it, "Cartridge saved state"); it.fd.sync() }
            Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            record.delete(); state.delete(); File(folder, "$name.jpg").delete(); throw failure
        } finally { temporary.delete() }
        replacing?.let { removeSaved(id, it) }
        name
    }

    /** The stored bytes of a state, checked against its record. Nothing is changed when they do not match. */
    fun read(id: String, system: GameSystem, name: String): ByteArray = synchronized(lock) {
        when (name) {
            QUICK -> checkNotNull(store.read(id, "state")) { "There is no quick save for this game" }
            PREVIOUS -> bounded(File(folder(id), "previous.state"))
            else -> {
                require(name.matches(UUID_NAME)) { "Unknown saved state" }
                val record = File(folder(id), "$name.properties")
                check(record.isFile) { "This saved state is no longer there" }
                val values = record(record)
                check(values.getProperty("core") == system.coreRevision) { "This state was made by a different core build. The file was kept." }
                bounded(File(folder(id), "$name.state")).also {
                    check(it.size.toString() == values.getProperty("size") && hash(it) == values.getProperty("sha256")) {
                        "This saved state failed its integrity check. The file was kept."
                    }
                }
            }
        }
    }

    /** The newest state of all, quick or saved, for "Start from save state". Never an older one in its place. */
    fun latest(id: String, system: GameSystem): StateSlot? = list(id, system).filter { it.kind != SlotKind.Previous }.maxByOrNull { it.savedAt }

    /** Only saved states are deleted; the quick save and the one it replaced are not offered for it. */
    fun delete(id: String, name: String) = synchronized(lock) {
        require(name.matches(UUID_NAME)) { "Only a saved state can be deleted" }
        removeSaved(id, name)
    }

    /** What a backup carries for a game with every state included: the files themselves, read by the archive writer. */
    fun backupEntries(id: String, system: GameSystem): List<BackupSlot> = synchronized(lock) {
        list(id, system).mapNotNull { slot ->
            when (slot.kind) {
                SlotKind.Quick -> slot.thumbnail?.let { BackupSlot(QUICK, null, slot.savedAt, null, it) }
                SlotKind.Previous -> BackupSlot(PREVIOUS, File(folder(id), "previous.state"), slot.savedAt, system.coreRevision, slot.thumbnail)
                SlotKind.Saved -> if (!slot.compatible) null else BackupSlot(slot.name, File(folder(id), "${slot.name}.state"), slot.savedAt,
                    system.coreRevision, slot.thumbnail)
            }
        }
    }

    /** A backup's state or picture goes in only where nothing of that name is kept already. Returns whether it did. */
    fun importMissing(id: String, system: GameSystem, name: String, bytes: ByteArray?, savedAt: Long, thumbnail: ByteArray?): Boolean = synchronized(lock) {
        require(name == QUICK || name == PREVIOUS || name.matches(UUID_NAME))
        val folder = writable(id)
        when {
            name == QUICK -> {
                if (thumbnail == null || File(folder, "$QUICK.jpg").exists() || !File(saves, "$id.state").isFile) return@synchronized false
                publishPicture(id, QUICK, thumbnail); true
            }
            name == PREVIOUS -> {
                val target = File(folder, "previous.state")
                if (bytes == null || target.exists()) return@synchronized false
                val temporary = File.createTempFile("slot-", ".tmp", folder)
                try {
                    FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                    temporary.setLastModified(savedAt)
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally { temporary.delete() }
                if (thumbnail != null && !File(folder, "$PREVIOUS.jpg").exists()) runCatching { publishPicture(id, PREVIOUS, thumbnail) }
                true
            }
            else -> {
                if (bytes == null || File(folder, "$name.properties").exists() || File(folder, "$name.state").exists()) return@synchronized false
                val written = write(id, system, bytes, thumbnail, savedAt)
                // Keep the backup's own name, so a second merge of the same backup recognises it.
                listOf(".state", ".jpg", ".properties").forEach { suffix -> File(folder, "$written$suffix").takeIf(File::exists)?.renameTo(File(folder, "$name$suffix")) }
                true
            }
        }
    }

    private fun removeSaved(id: String, name: String) {
        val folder = folder(id)
        // The record goes first: a state left behind by an interruption is no longer listed.
        File(folder, "$name.properties").let { check(!it.exists() || it.delete()) { "The saved state could not be deleted" } }
        File(folder, "$name.state").delete(); File(folder, "$name.jpg").delete()
    }
    private fun publishPicture(id: String, name: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_THUMBNAIL && isJpeg(bytes)) { "Invalid thumbnail" }
        val folder = writable(id)
        val temporary = File.createTempFile("picture-", ".tmp", folder)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), File(folder, "$name.jpg").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    /** Copies [source] over [target] whole, keeping its time, so the copy is listed as when it was made. */
    private fun replace(source: File, target: File) {
        val temporary = File.createTempFile("slot-", ".tmp", target.parentFile)
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            temporary.setLastModified(source.lastModified())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun record(file: File): Properties {
        require(file.length() in 1..4096) { "The saved state's record is invalid" }
        return Properties().apply { file.inputStream().use(::load) }.also {
            require(it.getProperty("version") == "1" && it.getProperty("sha256", "").matches(HASH) && (it.getProperty("size")?.toIntOrNull() ?: 0) in 1..SessionStore.MAX_STATE)
        }
    }
    private fun bounded(file: File): ByteArray {
        check(file.isFile) { "This saved state is no longer there" }
        require(file.length() in 1..SessionStore.MAX_STATE.toLong()) { "The stored state is empty or too large. The file was kept." }
        return file.readBytes()
    }
    /** Only a core whose states carry their revision can be told apart from here; any other state is tried when loaded. */
    private fun envelope(system: GameSystem, file: File): Boolean {
        if (!system.core.stateEnvelope) return true
        val header = ByteArray(256)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        return read > 0 && ManualState.compatible(system, header.copyOf(read))
    }

    companion object {
        const val QUICK = "quick"
        const val PREVIOUS = "previous"
        /** A thumbnail is a small JPEG of the screen, a few tens of kilobytes. */
        const val MAX_THUMBNAIL = 512 * 1024
        val UUID_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val HASH = Regex("[a-f0-9]{64}")
        private val RECORD = Regex("${UUID_NAME.pattern}\\.properties")
        private val locks = ConcurrentHashMap<String, Any>()
        fun isJpeg(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** One state or picture of a game's list in a backup; [state] is null for the quick save's picture alone. */
class BackupSlot(val name: String, val state: File?, val savedAt: Long, val core: String?, val thumbnail: File?)
