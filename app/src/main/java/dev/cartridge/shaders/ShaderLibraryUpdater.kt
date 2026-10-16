package dev.cartridge.shaders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * The one Update button's work (ADR 0027): the newest commit of the shader library, downloaded only when it is
 * not the one installed, read entry by entry within fixed limits, and put in place only once it is complete.
 * Until then the installed library is untouched, so a failed or cancelled update changes nothing.
 *
 * Under [base]: `library` is the installed library (with [VERSION_FILE] in it), `staging` the one being
 * written, `previous` the one being replaced during the swap. [recover] finishes or undoes a swap that a
 * killed process left half done.
 */
class ShaderLibraryUpdater(
    private val base: File,
    private val source: ShaderSource,
    /** Bytes the app may still write under [base]; on the device, what the storage manager can allocate. */
    private val freeSpace: (File) -> Long,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val compressed: Long = 256L * 1024 * 1024,
        val expanded: Long = 512L * 1024 * 1024,
        val entries: Int = 20_000,
        val entry: Long = 32L * 1024 * 1024,
        val freeSpace: Long = 128L * 1024 * 1024,
    )

    /** What is installed: the library's commit and its date, how many presets and bytes it holds. */
    data class Version(val commit: String, val date: String, val presets: Int, val files: Int, val bytes: Long)

    sealed interface Outcome {
        data class UpToDate(val version: Version) : Outcome
        data class Installed(val version: Version, val replaced: Version?) : Outcome
    }

    /** [downloaded] bytes of the archive so far, of [total] when the server says, else -1. */
    data class Progress(val downloaded: Long, val total: Long)

    private val library = File(base, "library")
    private val staging = File(base, "staging")
    private val previous = File(base, "previous")
    private val running = Mutex()

    /** The installed library, or null when there is none. */
    suspend fun installed(): Version? = withContext(Dispatchers.IO) { running.withLock { recover(); read() } }

    /**
     * Checks the newest commit and, unless it is installed, downloads and installs it. Cancelling the calling
     * coroutine stops it and leaves the installed library as it was. Throws with a reason the player can read.
     */
    suspend fun update(progress: (Progress) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        check(running.tryLock()) { "The shader library is already being updated" }
        try {
            recover()
            val installed = read()
            val newest = source.newest()
            require(COMMIT.matches(newest.commit)) { "The shader library's newest version could not be read" }
            if (installed != null && installed.commit == newest.commit) return@withContext Outcome.UpToDate(installed)
            check(freeSpace(base.apply { mkdirs() }) >= limits.freeSpace) { "There is not enough free space to update the shader library" }
            staging.deleteRecursively()
            try {
                val counts = source.archive(newest.commit).use { download ->
                    extract(download.stream, download.length, progress)
                }
                require(counts.first > 0) { "The download holds no shader presets" }
                val version = Version(newest.commit, newest.date, counts.first, counts.second, counts.third)
                write(version)
                swap()
                Outcome.Installed(version, installed)
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            running.unlock()
        }
    }

    /** Presets, files and bytes written into [staging]. */
    private suspend fun extract(stream: InputStream, length: Long, progress: (Progress) -> Unit): Triple<Int, Int, Long> {
        staging.mkdirs()
        val root = staging.canonicalFile
        var presets = 0
        var files = 0
        var expanded = 0L
        var entries = 0
        var prefix: String? = null
        var lastReport = 0L
        val counted = object : FilterInputStream(stream) {
            var consumed = 0L
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int = super.read(buffer, offset, size).also { if (it > 0) count(it) }
            fun count(bytes: Int) {
                consumed += bytes
                require(consumed <= limits.compressed) { "The download is larger than a shader library can be" }
                if (consumed - lastReport >= 256 * 1024) { lastReport = consumed; progress(Progress(consumed, length)) }
            }
        }
        ZipInputStream(counted).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= limits.entries) { "The download has more files than a shader library can have" }
                // Every entry is in one folder named after the repository and commit; that folder is dropped.
                val top = entry.name.substringBefore('/')
                if (prefix == null) prefix = top
                require(top == prefix && entry.name.length > top.length) { "The download is not one library" }
                val name = entry.name.substring(top.length + 1)
                if (entry.isDirectory || name.isEmpty()) { zip.closeEntry(); continue }
                if (!wanted(name)) { zip.closeEntry(); continue }
                val file = File(root, name).canonicalFile
                require(file.path.startsWith(root.path + File.separator)) { "The download names a file outside the library" }
                file.parentFile!!.mkdirs()
                var size = 0L
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        expanded += read
                        require(size <= limits.entry) { "A file in the download is larger than a shader file can be" }
                        require(expanded <= limits.expanded) { "The download unpacks to more than a shader library can be" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                files++
                if (name.endsWith(".glslp", ignoreCase = true)) presets++
                zip.closeEntry()
            }
        }
        progress(Progress(counted.consumed, length))
        return Triple(presets, files, expanded)
    }

    private fun write(version: Version) {
        val properties = Properties().apply {
            setProperty("commit", version.commit)
            setProperty("date", version.date)
            setProperty("presets", version.presets.toString())
            setProperty("files", version.files.toString())
            setProperty("bytes", version.bytes.toString())
        }
        FileOutputStream(File(staging, VERSION_FILE)).use { output ->
            properties.store(output, "Shader library installed by Cartridge")
            output.fd.sync()
        }
    }

    private fun read(): Version? {
        val file = File(library, VERSION_FILE)
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        val commit = properties.getProperty("commit")?.takeIf(COMMIT::matches) ?: return null
        return Version(commit, properties.getProperty("date").orEmpty(), properties.getProperty("presets")?.toIntOrNull() ?: 0,
            properties.getProperty("files")?.toIntOrNull() ?: 0, properties.getProperty("bytes")?.toLongOrNull() ?: 0)
    }

    /** The complete new library takes the old one's place; the old one goes only once the new one is there. */
    private fun swap() {
        previous.deleteRecursively()
        if (library.exists()) check(library.renameTo(previous)) { "The installed shader library could not be moved aside" }
        if (!staging.renameTo(library)) {
            if (previous.exists()) previous.renameTo(library)
            error("The new shader library could not be put in place")
        }
        previous.deleteRecursively()
    }

    /** A swap the process did not finish: the old library comes back unless the new one is already in place. */
    private fun recover() {
        if (!library.exists() && previous.exists()) previous.renameTo(library)
        if (library.exists()) previous.deleteRecursively()
        staging.deleteRecursively()
    }

    companion object {
        const val VERSION_FILE = ".cartridge-library.properties"
        private val COMMIT = Regex("[0-9a-f]{40}")
        private val NAME = Regex("""[A-Za-z0-9._+ -]+(/[A-Za-z0-9._+ -]+)*""")
        private val KEPT = setOf("glsl", "glslp", "png", "jpg", "jpeg", "bmp", "tga")

        /** A shader, a preset or an image, by a path made only of plain segments; nothing else is written. */
        fun wanted(name: String): Boolean {
            if (name.length > 240 || !NAME.matches(name)) return false
            val segments = name.split('/')
            if (segments.size > 16 || segments.any { it.isBlank() || it.startsWith(".") || it != it.trim() }) return false
            return name.substringAfterLast('.', "").lowercase() in KEPT
        }
    }
}

/** Where the library comes from: its newest commit, and that commit's archive. */
interface ShaderSource {
    data class Commit(val commit: String, val date: String)
    class Download(val stream: InputStream, val length: Long, private val closing: () -> Unit = {}) : java.io.Closeable {
        override fun close() { try { stream.close() } finally { closing() } }
    }

    suspend fun newest(): Commit
    suspend fun archive(commit: String): Download
}
