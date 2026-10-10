package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.cartridge.library.CollectionRepository
import dev.cartridge.library.DirectoryRepository
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.IndexedDocument
import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext

data class ScanResult(val added: Int, val duplicates: Int, val failed: Int, val visited: Int, val errors: List<String>, val unchanged: Int = 0) {
    val message get() = "Scan complete: $added added · $duplicates duplicates · $failed failed" +
        " · $unchanged unchanged" + errors.firstOrNull()?.let { ". $it" }.orEmpty()
}

/** Read-only traversal of a user-granted tree; completed imports are committed individually. */
class DirectoryScanner(private val resolver: ContentResolver, private val roms: File,
    private val repository: CollectionRepository, private val directories: DirectoryRepository) {
    suspend fun scan(tree: Uri, progress: suspend (String) -> Unit, verifyAll: Boolean = false): ScanResult = withContext(Dispatchers.IO) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a ROM folder through the Android picker" }
        directories.rememberDirectory(tree.toString())
        val indexed = directories.indexed(tree.toString()).associateBy { it.documentUri }
        val documents = mutableSetOf<String>()
        var complete = true
        var folderName = DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':')
        runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) folderName = it.getString(0).orEmpty()
            }
        }
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val seen = mutableSetOf<String>()
        var added = 0; var duplicates = 0; var failed = 0; var visited = 0; var unchanged = 0
        val errors = mutableListOf<String>()
        fun failure(message: String) { failed++; if (errors.size < 8) errors += message }
        val importer = RomImporter(resolver, roms)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (parent, depth) = pending.removeFirst()
            if (!seen.add(parent)) continue
            if (depth > 16) { complete = false; failure("A folder exceeds the 16-level scan limit"); continue }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            try {
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val cursor = checkNotNull(resolver.query(children, projection, null, null, null)) { "The folder is unavailable" }
                cursor.use {
                    while (it.moveToNext()) {
                        coroutineContext.ensureActive()
                        check(++visited <= 10_000) { "Scan stopped at 10,000 entries; choose a smaller folder" }
                        val documentId = it.getString(0); val name = it.getString(1).orEmpty()
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(documentId to depth + 1)
                        } else if (GameSystem.supports(name)) {
                            withContext(Dispatchers.Main) { progress("Scanning $name · $added added · $duplicates duplicates · $failed failed") }
                            try {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                                documents += uri.toString()
                                val bytes = if (it.isNull(3)) -1 else it.getLong(3)
                                val modified = if (it.isNull(4)) 0 else it.getLong(4)
                                val old = indexed[uri.toString()]
                                val cachedSystem = old?.let { entry -> GameSystem.entries.firstOrNull { GameFiles.resolve(roms, entry.gameId, it).isFile } }
                                val cached = old?.let { entry -> cachedSystem?.let { GameFiles.resolve(roms, entry.gameId, it) } }
                                if (!verifyAll && old != null && bytes > 0 && modified > 0 &&
                                    !name.endsWith(".cue", true) && old.bytes == bytes && old.modified == modified && cached?.isFile == true && cached.length() == bytes) {
                                    if (repository.add(LibraryGame(old.gameId, name.substringBeforeLast('.'), uri.toString(), system = checkNotNull(cachedSystem)))) added++ else duplicates++
                                    directories.index(old.copy(missing = false)); unchanged++
                                    continue
                                }
                                val taskContext = coroutineContext
                                val game = importer.import(uri, siblings = { track ->
                                    val matches = mutableListOf<Uri>()
                                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { tracks ->
                                        var count = 0
                                        while (tracks.moveToNext()) {
                                            taskContext.ensureActive(); require(++count <= 10_000) { "Too many files in the track folder" }
                                            if (tracks.getString(1).equals(track,ignoreCase=true)) matches += DocumentsContract.buildDocumentUriUsingTree(tree, tracks.getString(0))
                                        }
                                    }
                                    require(matches.size <= 1) { "Ambiguous CUE track: $track" }; matches.singleOrNull()
                                }) { taskContext.ensureActive() }
                                if (repository.add(LibraryGame(game.request.id, game.title, uri.toString(), system = game.request.system))) added++ else duplicates++
                                directories.index(IndexedDocument(tree.toString(), uri.toString(), game.request.id, bytes, modified))
                            } catch (exception: Exception) {
                                if (exception is CancellationException) throw exception
                                failure("$name: ${exception.message ?: "Cannot import this file"}")
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                complete = false
                failure(exception.message ?: "Cannot read this folder")
                if (visited >= 10_000) break
            }
        }
        directories.finishScan(tree.toString(), folderName, documents, complete, errors.firstOrNull())
        ScanResult(added, duplicates, failed, visited, errors, unchanged)
    }
}
