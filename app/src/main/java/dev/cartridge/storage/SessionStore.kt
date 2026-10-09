package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class ResumeSession(val id: String, val system: GameSystem, val savedAt: Long)

/** A completed state is published last. Interrupted writes leave the previous checkpoint usable. */
class SessionStore(private val directory: File) {
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private val manifest get() = File(directory, "session.properties")
    fun write(id: String, system: GameSystem, bytes: ByteArray, time: Long = System.currentTimeMillis()) = synchronized(lock) {
        require(id.matches(HASH) && bytes.isNotEmpty() && bytes.size <= MAX_STATE && time >= 0)
        check(directory.isDirectory || directory.mkdirs())
        val generation = UUID.randomUUID().toString()
        val state = File(directory, "$generation.state")
        val temporary = File.createTempFile("manifest-", ".tmp", directory)
        try {
            FileOutputStream(state).use { it.write(bytes); it.fd.sync() }
            val values = Properties().apply {
                setProperty("version", "1"); setProperty("core", CORE); setProperty("id", id)
                setProperty("system", system.name); setProperty("savedAt", time.toString())
                setProperty("generation", generation); setProperty("sha256", hash(bytes)); setProperty("size", bytes.size.toString())
            }
            FileOutputStream(temporary).use { values.store(it, "Cartridge completed session"); it.fd.sync() }
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles()?.filter { it.name.matches(STATE_NAME) && it != state }?.forEach { it.delete() }
        } catch (failure: Throwable) { state.delete(); throw failure }
        finally { temporary.delete() }
    }
    private fun values(): Properties? {
        if (!manifest.exists()) return null
        require(manifest.length() in 1..8192) { "The resume record is invalid. Eject or discard it to recover." }
        val values = Properties().apply { manifest.inputStream().use(::load) }
        require(values.getProperty("version") == "1" && values.getProperty("core") == CORE) { "The saved session uses a different core build" }
        require(values.getProperty("id", "").matches(HASH) && values.getProperty("generation", "").matches(UUID_NAME)
            && values.getProperty("sha256", "").matches(HASH)) { "The resume record is invalid" }
        require(values.getProperty("size").toInt() in 1..MAX_STATE && values.getProperty("savedAt").toLong() >= 0)
        GameSystem.valueOf(values.getProperty("system"))
        return values
    }
    fun available(): ResumeSession? = synchronized(lock) {
        values()?.let { ResumeSession(it.getProperty("id"), GameSystem.valueOf(it.getProperty("system")), it.getProperty("savedAt").toLong()) }
    }
    fun read(session: ResumeSession): ByteArray = synchronized(lock) {
        val values = checkNotNull(values()) { "There is no saved session" }
        require(session == ResumeSession(values.getProperty("id"), GameSystem.valueOf(values.getProperty("system")), values.getProperty("savedAt").toLong())) { "The saved session changed" }
        val state = File(directory, "${values.getProperty("generation")}.state")
        require(state.length() == values.getProperty("size").toLong()) { "The resume state is missing or incomplete" }
        state.readBytes().also { require(hash(it) == values.getProperty("sha256")) { "The resume state failed its integrity check" } }
    }
    fun clear() = synchronized(lock) {
        if (manifest.exists()) check(manifest.delete()) { "Cannot discard the saved session" }
        directory.listFiles()?.filter { it.name.matches(STATE_NAME) }?.forEach { it.delete() }
    }
    companion object {
        const val CORE = "mgba-0.10.5-cartridge-memory-2"
        const val MAX_STATE = 16 * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val UUID_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val STATE_NAME = Regex("${UUID_NAME.pattern}\\.state")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
