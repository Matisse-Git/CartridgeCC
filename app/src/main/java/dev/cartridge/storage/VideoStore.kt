package dev.cartridge.storage

import java.io.File

/**
 * Gameplay videos a scrape downloaded, one per game, named by the game's id. They are kept apart from the library
 * database and from backups: they are large, and a scrape can always fetch them again. [MAX_BYTES] bounds each.
 */
class VideoStore(private val directory: File) {
    fun file(gameId: String): File? = path(gameId).takeIf { it.isFile && it.length() > 0 }
    fun has(gameId: String) = file(gameId) != null

    /** Writes through a temporary file, so a cancelled download never leaves half a video under the game's name. */
    suspend fun save(gameId: String, write: suspend (File) -> Unit) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the video folder" }
        val temporary = File.createTempFile("video-", ".tmp", directory)
        try {
            write(temporary)
            require(temporary.length() in 1..MAX_BYTES) { "The video is empty or too large" }
            val target = path(gameId)
            target.delete()
            check(temporary.renameTo(target)) { "Cannot store the video" }
        } finally { temporary.delete() }
    }
    fun delete(gameId: String) { path(gameId).delete() }
    fun totalBytes(): Long = directory.listFiles()?.filter { it.name.endsWith(".mp4") }?.sumOf(File::length) ?: 0L

    private fun path(gameId: String): File {
        require(gameId.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$gameId.mp4")
    }
    companion object { const val MAX_BYTES = 64L * 1024 * 1024 }
}
