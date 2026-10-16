package dev.cartridge.storage

import android.content.res.AssetManager
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * An engine's own data files (fonts it draws with, per-game compatibility settings, a shader). They are built
 * from the pinned source, packaged with the app as one archive, and unpacked into the private system folder
 * once for each build of the core. Nothing of the owner's is kept there.
 */
object CoreData {
    private const val MAX_FILES = 8192
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val STAMP = ".cartridge"

    fun unpack(assets: AssetManager, archive: String, target: File, revision: String) {
        val stamp = File(target, STAMP)
        if (stamp.isFile && stamp.readText() == revision) return
        val parent = checkNotNull(target.parentFile).apply { check(isDirectory || mkdirs()) }
        val stage = File(parent, "unpack-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val root = stage.canonicalPath + File.separator
            var files = 0; var total = 0L
            ZipInputStream(assets.open(archive)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // The archive is this build's own, and its names are still only ever used below the staging folder.
                    val name = entry.name
                    require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && ':' !in name && name.split('/').none { it == ".." }) { "Core data has an invalid name" }
                    val file = File(stage, name)
                    require((file.canonicalPath + File.separator).startsWith(root)) { "Core data has an invalid name" }
                    if (entry.isDirectory) { check(file.isDirectory || file.mkdirs()); continue }
                    require(++files <= MAX_FILES) { "Core data has too many files" }
                    checkNotNull(file.parentFile).let { check(it.isDirectory || it.mkdirs()) }
                    file.outputStream().use { output ->
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            total += count; require(total <= MAX_BYTES) { "Core data is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(files > 0) { "Core data is empty" }
            File(stage, STAMP).writeText(revision)
            // Only what an earlier build unpacked is replaced.
            if (target.exists()) check(target.deleteRecursively()) { "Cannot replace the core's data" }
            check(stage.renameTo(target)) { "Cannot publish the core's data" }
        } finally { stage.deleteRecursively() }
    }
}
