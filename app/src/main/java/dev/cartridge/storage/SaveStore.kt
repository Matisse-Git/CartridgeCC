package dev.cartridge.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** File replacement leaves the previous save intact if writing the next save fails. */
class SaveStore(private val directory: File) {
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private fun file(id: String, kind: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid game identifier" }
        require(kind in setOf("sram", "rtc", "state"))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create save directory" }
        return File(directory, "$id.$kind")
    }
    fun write(id: String, kind: String, bytes: ByteArray) {
        synchronized(lock) {
            require(bytes.size <= limit(kind)) { "The save exceeds the supported size" }
            require(kind != "state" || bytes.isNotEmpty()) { "Cannot write an empty saved state" }
            val destination = file(id, kind)
            val temporary = File.createTempFile("$id-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
    /** Append only: a merge must never replace an existing save, including an invalid one. */
    fun writeMissing(id: String, kind: String, bytes: ByteArray): Boolean = synchronized(lock) {
        if (file(id, kind).exists()) false else { write(id, kind, bytes); true }
    }
    fun read(id: String, kind: String): ByteArray? = synchronized(lock) {
        val source = file(id, kind)
        if (!source.exists()) return@synchronized null
        val maximum = limit(kind)
        require(source.length() <= maximum) { "The stored $kind file is too large. The original file was kept." }
        Files.newInputStream(source.toPath()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size().toLong() + count <= maximum) { "The stored save is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(kind != "state" || it.isNotEmpty()) { "The saved state is empty" } }
        }
    }
    private fun limit(kind: String): Int = when (kind) {
        "sram" -> 128 * 1024
        "rtc" -> 4096
        "state" -> SessionStore.MAX_STATE
        else -> throw IllegalArgumentException("Invalid save kind")
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
