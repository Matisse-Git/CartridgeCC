package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.cartridge.library.CollectionRepository
import dev.cartridge.library.DirectoryRepository
import dev.cartridge.library.LibraryGame
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext

data class ScanResult(val added: Int, val duplicates: Int, val failed: Int, val visited: Int, val errors: List<String>) {
    val message get() = "Scan complete: $added added · $duplicates duplicates · $failed failed" +
        errors.firstOrNull()?.let { ". $it" }.orEmpty()
}

/** Read-only traversal of a user-granted tree; completed imports are committed individually. */
class DirectoryScanner(private val resolver: ContentResolver, private val roms: File,
    private val repository: CollectionRepository, private val directories: DirectoryRepository) {
    suspend fun scan(tree: Uri, progress: suspend (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a ROM folder through the Android picker" }
        directories.rememberDirectory(tree.toString())
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val seen = mutableSetOf<String>()
        var added = 0; var duplicates = 0; var failed = 0; var visited = 0
        val errors = mutableListOf<String>()
        fun failure(message: String) { failed++; if (errors.size < 8) errors += message }
        val importer = RomImporter(resolver, roms)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (parent, depth) = pending.removeFirst()
            if (!seen.add(parent)) continue
            if (depth > 16) { failure("A folder exceeds the 16-level scan limit"); continue }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            try {
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                val cursor = checkNotNull(resolver.query(children, projection, null, null, null)) { "The folder is unavailable" }
                cursor.use {
                    while (it.moveToNext()) {
                        coroutineContext.ensureActive()
                        check(++visited <= 10_000) { "Scan stopped at 10,000 entries; choose a smaller folder" }
                        val documentId = it.getString(0); val name = it.getString(1).orEmpty()
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(documentId to depth + 1)
                        } else if (name.endsWith(".gba", true)) {
                            withContext(Dispatchers.Main) { progress("Scanning $name · $added added · $duplicates duplicates · $failed failed") }
                            try {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                                val taskContext = coroutineContext
                                val game = importer.import(uri) { taskContext.ensureActive() }
                                if (repository.add(LibraryGame(game.request.id, game.title, uri.toString()))) added++ else duplicates++
                            } catch (exception: Exception) {
                                if (exception is CancellationException) throw exception
                                failure("$name: ${exception.message ?: "Cannot import this file"}")
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                failure(exception.message ?: "Cannot read this folder")
                if (visited >= 10_000) break
            }
        }
        ScanResult(added, duplicates, failed, visited, errors)
    }
}
