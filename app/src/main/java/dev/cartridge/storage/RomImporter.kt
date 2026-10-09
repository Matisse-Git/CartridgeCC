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
    fun import(uri: Uri, checkCancelled: () -> Unit = {}): ImportedGame {
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "User game.gba"
        require(GameSystem.supports(title)) { "Choose an uncompressed .gba, .gb or .gbc file" }
        val limit = if (title.endsWith(".gba", ignoreCase = true)) 32L * 1024 * 1024 else 8L * 1024 * 1024 + 64
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("rom-", ".tmp", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file is unavailable" }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= limit) { "The ROM exceeds the system's size limit" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    require(size >= 192) { "The selected ROM is incomplete" }
                    output.fd.sync()
                }
            }
            val system = temporary.inputStream().use { GameSystem.detect(title, java.io.DataInputStream(it).run { ByteArray(minOf(temporary.length(), 512L).toInt()).also(::readFully) }) }
            require(system == GameSystem.GBA || temporary.length() <= 8L * 1024 * 1024 + 64) { "GB/GBC files must be at most 8 MiB (plus a GBX footer)" }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$id.${system.extension}")
            if (destination.exists()) temporary.delete() else check(temporary.renameTo(destination))
            return ImportedGame(GameRequest(destination, id, system), title.substringBeforeLast('.'))
        } finally { temporary.delete() }
    }
}
