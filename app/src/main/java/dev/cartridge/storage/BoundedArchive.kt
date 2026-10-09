package dev.cartridge.storage

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Never use archive paths as filesystem paths. Originals are read into generated private files. */
object BoundedArchive {
    fun safeName(name: String): Boolean = name.length in 1..240 && !name.startsWith("/") &&
        name.matches(Regex("[A-Za-z0-9._/-]+")) && name.trimEnd('/').split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    fun read(input: InputStream, staging: File, check: () -> Unit = {}): Map<String, File> {
        val files = linkedMapOf<String, File>(); val names = mutableSetOf<String>(); var total = 0L
        val bounded = object : FilterInputStream(input) {
            var consumed = 0L
            override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) counted(it) }
            fun counted(count: Int) { consumed += count; require(consumed <= 128L * 1024 * 1024) { "Archive exceeds 128 MiB" } }
        }
        ZipInputStream(bounded).use { zip ->
            while (true) {
                check(); val entry = zip.nextEntry ?: break
                require(safeName(entry.name) && names.add(entry.name)) { "Unsafe or duplicate archive path" }
                require(names.size <= 2048) { "Archive has too many entries" }
                if (entry.isDirectory) { require(zip.read() == -1) { "Directory entries must be empty" }; zip.closeEntry(); continue }
                val maximum = if (entry.name == "pack.json") 1024 * 1024 else 12 * 1024 * 1024
                require(entry.size <= maximum) { "Archive entry is too large" }
                val key = MessageDigest.getInstance("SHA-256").digest(entry.name.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = File(staging, "$key.data"); var size = 0
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(); val count = zip.read(buffer); if (count < 0) break
                        size += count; total += count
                        require(size <= maximum && total <= 256L * 1024 * 1024) { "Archive expands beyond supported limits" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                files[entry.name] = file; zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "The archive is empty" }
        return files
    }
}
