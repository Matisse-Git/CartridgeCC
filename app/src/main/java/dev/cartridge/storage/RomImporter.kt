package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.cartridge.emulation.GameRequest
import dev.cartridge.domain.GameSystem
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ImportedGame(val request: GameRequest, val title: String)

/** Bounded single-document import; originals stay under the user's control. */
class RomImporter(private val resolver: ContentResolver, private val directory: File) {
    /** [folder] is the console the document's folder is named for; it settles a container that several consoles share. */
    fun import(uri: Uri, siblings: ((String) -> Uri?)? = null, folder: GameSystem? = null, checkCancelled: () -> Unit = {}): ImportedGame {
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "User game.gba"
        require(GameSystem.supports(title)) { GameSystem.UNSUPPORTED }
        if (GameSystem.candidates(title).any { it.bundle }) return DiscImporter(resolver, directory).import(uri, title, siblings, folder, checkCancelled)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("rom-", ".tmp", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val system = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file is unavailable" }
                // The first bytes name the console, so a file that only shares an extension is refused before it is copied.
                val header = ByteArray(512); var filled = 0
                while (filled < header.size) { val count = input.read(header, filled, header.size - filled); if (count < 0) break; filled += count }
                require(filled >= 192) { "The selected ROM is incomplete" }
                val system = GameSystem.detect(title, header.copyOf(filled), folder)
                FileOutputStream(temporary).use { output ->
                    output.write(header, 0, filled); digest.update(header, 0, filled)
                    val buffer = ByteArray(64 * 1024)
                    var size = filled.toLong()
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= system.maxBytes) { "The ROM exceeds the system's size limit" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                system
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$id.${system.extension}")
            if (destination.exists()) temporary.delete() else check(temporary.renameTo(destination))
            return ImportedGame(GameRequest(destination, id, system), title.substringBeforeLast('.'))
        } finally { temporary.delete() }
    }
}
