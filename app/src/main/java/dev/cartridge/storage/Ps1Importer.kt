package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import dev.cartridge.domain.GameSystem
import dev.cartridge.emulation.GameRequest
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Whole bundle is promoted only after every bounded track has been copied and synced. */
class Ps1Importer(private val resolver: ContentResolver, private val directory: File) {
    fun import(uri: Uri, name: String, siblings: ((String) -> Uri?)?, checkCancelled: () -> Unit): ImportedGame {
        check(directory.isDirectory || directory.mkdirs())
        val stage = File(directory, "ps1-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        fun copy(source: Uri, output: File, limit: Long, hashing: Boolean = true) {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "The disc file is unavailable" }
                FileOutputStream(output).use { stream ->
                    val buffer = ByteArray(128 * 1024); var size = 0L
                    while (true) {
                        checkCancelled(); val count = input.read(buffer); if (count < 0) break
                        size += count; total += count
                        require(size <= limit && total <= MAX_DISC) { "PS1 disc import exceeds 2 GiB" }
                        stream.write(buffer, 0, count); if (hashing) digest.update(buffer, 0, count)
                    }
                    require(size > 0) { "The disc file is empty" }; stream.fd.sync()
                }
            }
        }
        try {
            val extension = name.substringAfterLast('.').lowercase()
            val launcher = File(stage, "game.$extension")
            if (extension == "cue") {
                requireNotNull(siblings) { "Import CUE + BIN using Folder so Cartridge can read all tracks" }
                copy(uri, launcher, 262144, false)
                val cue = CueSheet.parse(launcher.readText(Charsets.UTF_8))
                val normalized = cue.text.toByteArray(Charsets.UTF_8)
                digest.update("Cartridge PS1 CUE v1\u0000".toByteArray()); digest.update(normalized)
                FileOutputStream(launcher).use { it.write(normalized); it.fd.sync() }
                cue.files.forEachIndexed { index, track ->
                    val file = File(stage, "track-${index.toString().padStart(2, '0')}.bin")
                    copy(requireNotNull(siblings(track)) { "CUE track missing: $track" }, file, MAX_DISC)
                    digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
                }
                siblings(name.substringBeforeLast('.') + ".sbi")?.let { source ->
                    val sbi=File(stage,"game.sbi");copy(source,sbi,131072,false)
                    val bytes=sbi.readBytes();SbiFile.validate(bytes)
                    digest.update("SBI\u0000".toByteArray());digest.update(bytes)
                }
            } else {
                copy(uri, launcher, MAX_DISC)
                val header = launcher.inputStream().use { stream -> ByteArray(minOf(512L, launcher.length()).toInt()).also { DataInputStream(stream).readFully(it) } }
                GameSystem.detect(name, header)
                if (extension == "iso") require(launcher.length() >= 32768 && launcher.length() % 2048 == 0L) { "ISO must contain complete 2048-byte sectors" }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$id.ps1")
            if (!destination.exists()) check(stage.renameTo(destination)) { "Cannot publish the imported disc" }
            return ImportedGame(GameRequest(GameFiles.resolve(directory, id, GameSystem.PS1), id, GameSystem.PS1), name.substringBeforeLast('.'))
        } finally { stage.deleteRecursively() }
    }
    companion object { const val MAX_DISC = 2L * 1024 * 1024 * 1024 }
}
