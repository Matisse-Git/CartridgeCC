package dev.cartridge.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File replacement leaves the previous save intact if writing the next save fails. */
class SaveStore(private val directory: File) {
    private fun file(id: String, kind: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid game identifier" }
        require(kind in setOf("sram", "state"))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create save directory" }
        return File(directory, "$id.$kind")
    }
    fun write(id: String, kind: String, bytes: ByteArray) {
        val destination = file(id, kind)
        val temporary = File(destination.path + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun read(id: String, kind: String): ByteArray? = file(id, kind).takeIf { it.exists() }?.readBytes()
}

