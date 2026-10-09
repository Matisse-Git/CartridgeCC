package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.cartridge.emulation.GameRequest
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ImportedGame(val request: GameRequest, val title: String)

/** M0 imports one document, without broad storage permissions or a commercial test ROM. */
class RomImporter(private val resolver: ContentResolver, private val directory: File) {
    fun import(uri: Uri): ImportedGame {
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "User game.gba"
        require(title.endsWith(".gba", ignoreCase = true)) { "Select an uncompressed .gba file" }
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "import.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file is unavailable" }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 32L * 1024 * 1024) { "GBA files must be at most 32 MiB" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    require(size >= 192) { "The selected file is too small to be a GBA game" }
                    output.fd.sync()
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$id.gba")
            if (destination.exists()) temporary.delete() else check(temporary.renameTo(destination))
            return ImportedGame(GameRequest(destination, id), title.removeSuffix(".gba").removeSuffix(".GBA"))
        } finally { temporary.delete() }
    }
}
