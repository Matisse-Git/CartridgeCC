package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Explicit owner import only. Known retail dumps are accepted; never downloads firmware. */
class Ps1BiosStore(private val directory: File) {
    fun description() = if (File(directory, "scph5501.bin").isFile) "PS1 BIOS: Imported" else "PS1 BIOS: HLE (limited compatibility)"
    fun import(resolver: ContentResolver, uri: Uri): String {
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            val bytes = ByteArray(512 * 1024); var offset = 0
            while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); require(count >= 0) { "PS1 BIOS must be exactly 512 KiB" }; offset += count }
            require(input.read() == -1) { "PS1 BIOS must be exactly 512 KiB" }; bytes
        }
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        require(md5 in HASHES) { "Choose a supported retail PS1 BIOS dump (SCPH-5500/5501/5502/1001/7001/101 or PSXONPSP660)" }
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("bios-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            // One configured private BIOS. PCSX identifies region from bytes, not this name.
            Files.move(temporary.toPath(), File(directory, "scph5501.bin").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return "PS1 BIOS imported. New PS1 sessions will use it."
    }
    companion object {
        private val HASHES = setOf("8dd7d5296a650fac7319bce665a6a53c", "490f666e1afb15b7362b406ed1cea246", "32736f17079d0b2b7024407c39bd3050",
            "924e392ed05558ffdb115408c263dccf", "1e68c231d0896b7eadcad1d7d8e76129", "6e3735ff4c7dc899ee98981385f6f3d0", "c53ca5908936d412331790f4426c6c33")
    }
}
