package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.cartridge.domain.DiscOwner
import dev.cartridge.domain.GameSystem
import dev.cartridge.emulation.GameRequest
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * A disc is kept whole: its launcher under its real extension, every track beside it under a generated name.
 * The bundle is promoted only after every bounded file has been copied and synced.
 */
class DiscImporter(private val resolver: ContentResolver, private val directory: File) {
    /** [folder] is the console the disc's folder is named for. What the disc says about itself wins over it. */
    fun import(uri: Uri, name: String, siblings: ((String) -> Uri?)?, folder: GameSystem?, checkCancelled: () -> Unit): ImportedGame {
        check(directory.isDirectory || directory.mkdirs())
        val stage = File(directory, "disc-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val digest = MessageDigest.getInstance("SHA-256")
        val candidates = GameSystem.candidates(name).filter { it.bundle }
        var limit = candidates.maxOf { it.maxBytes }
        var total = 0L
        fun refuse(): Nothing = throw IllegalArgumentException(if (limit == PS1_LIMIT) "PS1 disc import exceeds 2 GiB" else "The disc is larger than its console allows")
        // A provider that does not say leaves the length unknown; the copy is bounded either way.
        fun length(source: Uri): Long = try {
            resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
        } catch (failure: RuntimeException) { -1L }
        fun pump(input: InputStream, output: FileOutputStream, bound: Long, hashing: Boolean, already: Long = 0) {
            val buffer = ByteArray(128 * 1024); var size = already
            while (true) {
                checkCancelled(); val count = input.read(buffer); if (count < 0) break
                size += count; total += count
                if (size > bound || total > limit) refuse()
                output.write(buffer, 0, count); if (hashing) digest.update(buffer, 0, count)
            }
            require(size > 0) { "The disc file is empty" }; output.fd.sync()
        }
        fun copy(source: Uri, output: File, bound: Long, hashing: Boolean = true) {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "The disc file is unavailable" }
                FileOutputStream(output).use { pump(input, it, bound, hashing) }
            }
        }
        fun first(source: Uri, count: Int): ByteArray = resolver.openInputStream(source).use { input ->
            requireNotNull(input) { "The disc file is unavailable" }
            val bytes = ByteArray(count); var filled = 0
            while (filled < count) { val read = input.read(bytes, filled, count - filled); if (read < 0) break; filled += read }
            bytes.copyOf(filled)
        }
        try {
            val extension = name.substringAfterLast('.').lowercase()
            val launcher = File(stage, "game.$extension")
            val system: GameSystem
            if (extension == "gdi") {
                requireNotNull(siblings) { "Import a GDI with its tracks using Folder so Cartridge can read all of them" }
                copy(uri, launcher, 65536, false)
                val sheet = GdiSheet.parse(launcher.readText(Charsets.UTF_8))
                system = candidates.single()
                limit = system.maxBytes
                val normalized = sheet.text.toByteArray(Charsets.UTF_8)
                digest.update("Cartridge ${system.name} GDI v1\u0000".toByteArray()); digest.update(normalized)
                FileOutputStream(launcher).use { it.write(normalized); it.fd.sync() }
                sheet.files.forEachIndexed { index, track ->
                    val file = File(stage, "track-${index.toString().padStart(2, '0')}.bin")
                    copy(requireNotNull(siblings(track)) { "GDI track missing: $track" }, file, limit)
                    digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
                }
            } else if (extension == "cue") {
                requireNotNull(siblings) { "Import CUE + BIN using Folder so Cartridge can read all tracks" }
                copy(uri, launcher, 262144, false)
                val original = launcher.readText(Charsets.UTF_8)
                val cue = CueSheet.parse(original)
                val tracks = cue.files.map { track -> requireNotNull(siblings(track)) { "CUE track missing: $track" } }
                // The disc names its own console; the folder settles it when the disc says nothing; PlayStation is the rest.
                system = when (val owner = GameSystem.discOwner(first(tracks.first(), 64), original)) {
                    is DiscOwner.Playable -> owner.system
                    is DiscOwner.Other -> throw IllegalArgumentException("This is a ${owner.console} disc, which Cartridge can't play yet")
                    null -> folder?.takeIf { it in candidates } ?: candidates.first()
                }
                limit = system.maxBytes
                val normalized = cue.text.toByteArray(Charsets.UTF_8)
                digest.update("Cartridge ${system.name} CUE v1\u0000".toByteArray()); digest.update(normalized)
                FileOutputStream(launcher).use { it.write(normalized); it.fd.sync() }
                tracks.forEachIndexed { index, track ->
                    val file = File(stage, "track-${index.toString().padStart(2, '0')}.bin")
                    copy(track, file, limit)
                    digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
                }
                // Protected PlayStation discs carry their subchannel data beside the sheet.
                if (system == GameSystem.PS1) siblings(name.substringBeforeLast('.') + ".sbi")?.let { source ->
                    val sbi=File(stage,"game.sbi");copy(source,sbi,131072,false)
                    val bytes=sbi.readBytes();SbiFile.validate(bytes)
                    digest.update("SBI\u0000".toByteArray());digest.update(bytes)
                }
            } else {
                val size = length(uri)
                system = resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The disc file is unavailable" }
                    val header = ByteArray(512); var filled = 0
                    while (filled < header.size) { val count = input.read(header, filled, header.size - filled); if (count < 0) break; filled += count }
                    val found = GameSystem.detect(name, header.copyOf(filled), folder, size)
                    limit = found.maxBytes
                    // A DVD is copied whole into private storage: say so before filling it, not after.
                    val free = android.system.Os.statvfs(directory.path).let { it.f_bavail * it.f_frsize }
                    if (size > 0) require(size <= limit && free - size >= RESERVE) {
                        if (size > limit) "The disc is larger than its console allows"
                        else "Not enough free storage to add this disc: it needs ${(size + RESERVE + (1L shl 30) - 1) shr 30} GB"
                    }
                    // PlayStation identities are the file's own hash, as they always were. A later console's disc is
                    // marked with its name, so an image once listed under PlayStation by mistake can be listed again.
                    if (found != GameSystem.PS1) digest.update("Cartridge ${found.name} disc v1\u0000".toByteArray())
                    FileOutputStream(launcher).use { output ->
                        output.write(header, 0, filled); digest.update(header, 0, filled); total += filled
                        pump(input, output, limit, true, filled.toLong())
                    }
                    found
                }
                // A GameCube image is one flat file of the console's own layout, whatever it is named.
                if (extension == "iso" && system != GameSystem.GAMECUBE) require(launcher.length() >= 32768 && launcher.length() % 2048 == 0L) { "ISO must contain complete 2048-byte sectors" }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$id.${system.extension}")
            if (!destination.exists()) check(stage.renameTo(destination)) { "Cannot publish the imported disc" }
            return ImportedGame(GameRequest(GameFiles.resolve(directory, id, system), id, system), name.substringBeforeLast('.'))
        } finally { stage.deleteRecursively() }
    }
    private companion object {
        const val PS1_LIMIT = 2L * 1024 * 1024 * 1024
        /** Left free after a disc is copied, so saves and the library still have room to be written. */
        const val RESERVE = 256L * 1024 * 1024
    }
}
