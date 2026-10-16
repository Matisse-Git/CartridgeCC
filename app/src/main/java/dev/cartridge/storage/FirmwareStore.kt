package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import dev.cartridge.domain.Core
import dev.cartridge.domain.Firmware
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Console firmware the owner chose to add. Explicit import only: a file is accepted by its content, against
 * the dumps a pinned core is known to run or by what the file itself has to show, and stored under the private
 * name that core looks for. Nothing is downloaded, bundled or put in a backup.
 */
class FirmwareStore(private val directory: File) {
    fun present(firmware: Firmware) = File(directory, firmware.file).isFile
    /** The firmware files of [core] that are in place. */
    fun present(core: Core) = core.firmware.filter(::present)
    /** [core] narrows the search to the console the owner is setting up, so a refusal can say what it expected. */
    fun import(resolver: ContentResolver, uri: Uri, core: Core? = null): Pair<Core, Firmware> {
        val cores = core?.let(::listOf) ?: Core.entries
        val largest = cores.flatMap { it.firmware }.maxOfOrNull { it.bytes } ?: 0
        val refusal = core?.firmwareAccepts?.ifBlank { null }?.let { "Choose $it" } ?: "This is not a console file Cartridge can use"
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file is unavailable" }
            val buffer = ByteArray(largest + 1); var size = 0
            while (size < buffer.size) { val count = input.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }
            require(size in 1..largest) { refusal }
            buffer.copyOf(size)
        }
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val owner = cores.firstOrNull { it.firmware(bytes, md5) != null }
        val firmware = requireNotNull(owner?.firmware(bytes, md5)) { refusal }
        val destination = File(directory, firmware.file)
        check(destination.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val temporary = File.createTempFile("firmware-", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return owner!! to firmware
    }
}
