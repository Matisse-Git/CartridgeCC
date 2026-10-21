package dev.cartridge.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.cartridge.library.CollectionRepository
import dev.cartridge.library.DirectoryRepository
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.IndexedDocument
import dev.cartridge.domain.ConsoleCatalog
import dev.cartridge.domain.DiscFile
import dev.cartridge.domain.DiscSets
import dev.cartridge.domain.FolderHint
import dev.cartridge.domain.Playlist
import dev.cartridge.domain.FoundFile
import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext

data class ScanResult(val added: Int, val duplicates: Int, val failed: Int, val visited: Int, val errors: List<String>, val unchanged: Int = 0,
    /** Newly listed games per console, and games this folder holds that the library already had. */
    val addedSystems: Map<GameSystem, Int> = emptyMap(), val knownSystems: Map<GameSystem, Int> = emptyMap(),
    val otherConsoles: Map<String, Int> = emptyMap(), val archives: Int = 0, val playlists: Int = 0,
    val firstAdded: String? = null, val complete: Boolean = true) {
    val message get() = "Scan complete: $added added · $duplicates duplicates · $failed failed" +
        " · $unchanged unchanged" + errors.firstOrNull()?.let { ". $it" }.orEmpty()
    operator fun plus(other: ScanResult) = ScanResult(added + other.added, duplicates + other.duplicates, failed + other.failed,
        visited + other.visited, (errors + other.errors).take(8), unchanged + other.unchanged,
        merge(addedSystems, other.addedSystems), merge(knownSystems, other.knownSystems), merge(otherConsoles, other.otherConsoles),
        archives + other.archives, playlists + other.playlists, firstAdded ?: other.firstAdded, complete && other.complete)
    private fun <K> merge(a: Map<K, Int>, b: Map<K, Int>) = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }
}

/** What a folder holds, counted from names alone. Nothing is read or imported. */
data class Discovery(val folder: String, val games: Map<GameSystem, Int> = emptyMap(), val otherConsoles: Map<String, Int> = emptyMap(),
    val archives: Int = 0, val playlists: Int = 0, val complete: Boolean = true) {
    val total get() = games.values.sum()
}

/** Read-only traversal of a user-granted tree; completed imports are committed individually. */
class DirectoryScanner(private val resolver: ContentResolver, private val roms: File,
    private val repository: CollectionRepository, private val directories: DirectoryRepository) {
    private fun folderName(tree: Uri): String {
        var name = DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':')
        runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0).orEmpty()
            }
        }
        return name
    }

    /** A names-only pass so an import can say what it found before the slower hashing begins. */
    suspend fun discover(tree: Uri): Discovery = withContext(Dispatchers.IO) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a game folder through the Android picker" }
        val name = folderName(tree)
        val games = mutableMapOf<GameSystem, Int>(); val others = mutableMapOf<String, Int>()
        var archives = 0; var playlists = 0; var visited = 0; var complete = true
        val pending = ArrayDeque<Triple<String, Int, FolderHint?>>()
        pending.add(Triple(DocumentsContract.getTreeDocumentId(tree), 0, ConsoleCatalog.folderHint(name)))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited < 10_000) {
            coroutineContext.ensureActive()
            val (parent, depth, hint) = pending.removeFirst()
            if (!seen.add(parent) || depth > 16) continue
            try {
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                    val files = mutableListOf<String>()
                    while (it.moveToNext() && ++visited <= 10_000) {
                        val child = it.getString(1).orEmpty()
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(Triple(it.getString(0), depth + 1, ConsoleCatalog.folderHint(child) ?: hint))
                        else files += child
                    }
                    for (child in files) {
                        if (SheetTracks.likely(child, files)) continue
                        when (val found = ConsoleCatalog.classify(child, hint)) {
                            // A stray Windows program or text file is not a game; it is counted only where its folder names the console.
                            is FoundFile.Game -> if (ConsoleCatalog.claims(child, hint)) games[found.system] = (games[found.system] ?: 0) + 1
                            is FoundFile.OtherConsole -> others[found.console] = (others[found.console] ?: 0) + 1
                            FoundFile.Archive -> archives++
                            FoundFile.Playlist -> playlists++
                            FoundFile.Ignored -> Unit
                        }
                    }
                } ?: run { complete = false }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                complete = false
            }
        }
        Discovery(name, games, others, archives, playlists, complete && visited < 10_000)
    }

    private data class Listed(val id: String, val name: String, val bytes: Long, val modified: Long)
    private companion object { const val SHEET_BYTES = 262144 }

    /** A playlist's text, or nothing if it is too large or cannot be read: it only ever puts discs together. */
    private fun readPlaylist(uri: Uri, bytes: Long): String? {
        if (bytes > Playlist.MAX_BYTES) return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(Playlist.MAX_BYTES + 1); var filled = 0
                while (filled < buffer.size) { val count = input.read(buffer, filled, buffer.size - filled); if (count < 0) break; filled += count }
                if (filled > Playlist.MAX_BYTES) null else String(buffer, 0, filled, Charsets.UTF_8)
            }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
    }

    /**
     * What the sheets of one folder name, read only where it can matter: when the folder holds a sheet and also
     * a file that would otherwise be imported on its own.
     */
    private fun sheetTracks(tree: Uri, listing: List<Listed>, hint: FolderHint?): Set<String> {
        val sheets = listing.filter { GameSystem.sheet(it.name) }
        if (sheets.isEmpty() || listing.none { !GameSystem.sheet(it.name) && ConsoleCatalog.classify(it.name, hint) is FoundFile.Game }) return emptySet()
        val named = mutableSetOf<String>()
        for (sheet in sheets.take(1_000)) {
            val text = try {
                resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, sheet.id))?.use { input ->
                    val bytes = ByteArray(SHEET_BYTES + 1); var filled = 0
                    while (filled < bytes.size) { val count = input.read(bytes, filled, bytes.size - filled); if (count < 0) break; filled += count }
                    if (filled > SHEET_BYTES) null else String(bytes, 0, filled, Charsets.UTF_8)
                }
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
            if (text != null) named += SheetTracks.named(sheet.name, text)
        }
        return named
    }

    suspend fun scan(tree: Uri, progress: suspend (String) -> Unit, verifyAll: Boolean = false,
        handled: suspend (String, Int) -> Unit = { _, _ -> }): ScanResult = withContext(Dispatchers.IO) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a ROM folder through the Android picker" }
        directories.rememberDirectory(tree.toString())
        val indexed = directories.indexed(tree.toString()).associateBy { it.documentUri }
        val documents = mutableSetOf<String>()
        var complete = true
        val folderName = folderName(tree)
        val pending = ArrayDeque<Triple<String, Int, FolderHint?>>()
        pending.add(Triple(DocumentsContract.getTreeDocumentId(tree), 0, ConsoleCatalog.folderHint(folderName)))
        val seen = mutableSetOf<String>()
        var added = 0; var duplicates = 0; var failed = 0; var visited = 0; var unchanged = 0; var games = 0
        var archives = 0; var playlists = 0; var firstAdded: String? = null
        val addedSystems = mutableMapOf<GameSystem, Int>(); val knownSystems = mutableMapOf<GameSystem, Int>(); val others = mutableMapOf<String, Int>()
        val errors = mutableListOf<String>()
        fun failure(message: String) { failed++; if (errors.size < 8) errors += message }
        // This folder's games by the name they have in it, and whether each was new, for putting discs together (ADR 0032).
        val folderGames = mutableListOf<DiscFile>(); val counted = mutableMapOf<String, Boolean>()
        fun listed(game: LibraryGame, new: Boolean, name: String) {
            folderGames += DiscFile(game.id, name, game.system)
            counted.putIfAbsent(game.id, new)
            if (new) { added++; addedSystems[game.system] = (addedSystems[game.system] ?: 0) + 1; if (firstAdded == null) firstAdded = game.id }
            else { duplicates++; knownSystems[game.system] = (knownSystems[game.system] ?: 0) + 1 }
        }
        /** A disc that joined another game is that game: it is neither added nor already known in its own right. */
        fun uncount(id: String, system: GameSystem) {
            val new = counted.remove(id) ?: return
            if (new) { added--; addedSystems[system] = (addedSystems[system] ?: 1) - 1; if (firstAdded == id) firstAdded = null }
            else { duplicates--; knownSystems[system] = (knownSystems[system] ?: 1) - 1 }
        }
        val importer = RomImporter(resolver, roms)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (parent, depth, hint) = pending.removeFirst()
            if (!seen.add(parent)) continue
            if (depth > 16) { complete = false; failure("A folder exceeds the 16-level scan limit"); continue }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            try {
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val cursor = checkNotNull(resolver.query(children, projection, null, null, null)) { "The folder is unavailable" }
                val listing = mutableListOf<Listed>()
                cursor.use {
                    while (it.moveToNext()) {
                        coroutineContext.ensureActive()
                        check(++visited <= 10_000) { "Scan stopped at 10,000 entries; choose a smaller folder" }
                        val documentId = it.getString(0); val name = it.getString(1).orEmpty()
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(Triple(documentId, depth + 1, ConsoleCatalog.folderHint(name) ?: hint))
                        else listing += Listed(documentId, name, if (it.isNull(3)) -1 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4))
                    }
                }
                val tracks = sheetTracks(tree, listing, hint)
                folderGames.clear()
                val playlistTexts = mutableMapOf<String, String>()
                for ((documentId, name, bytes, modified) in listing) {
                    coroutineContext.ensureActive()
                    // A file a sheet in this folder names is that disc's track, imported with it.
                    if (name.lowercase() in tracks && !GameSystem.sheet(name)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                    when (val found = ConsoleCatalog.classify(name, hint)) {
                        // A disc already in the library keeps its index entry even if its folder is named for another console.
                        is FoundFile.OtherConsole -> if (indexed[uri.toString()] == null) { others[found.console] = (others[found.console] ?: 0) + 1; continue }
                        FoundFile.Archive -> { archives++; continue }
                        FoundFile.Playlist -> { readPlaylist(uri, bytes)?.let { playlistTexts[name] = it }; continue }
                        FoundFile.Ignored -> continue
                        is FoundFile.Game -> Unit
                    }
                    if (GameSystem.supports(name)) {
                        withContext(Dispatchers.Main) { progress("Scanning $name · $added added · $duplicates duplicates · $failed failed"); handled(name, games) }
                        try {
                            documents += uri.toString()
                            val old = indexed[uri.toString()]
                            val cachedSystem = old?.let { entry -> GameSystem.entries.firstOrNull { GameFiles.resolve(roms, entry.gameId, it).isFile } }
                            val cached = old?.let { entry -> cachedSystem?.let { GameFiles.resolve(roms, entry.gameId, it) } }
                            if (!verifyAll && old != null && bytes > 0 && modified > 0 &&
                                !GameSystem.sheet(name) && old.bytes == bytes && old.modified == modified && cached?.isFile == true && cached.length() == bytes) {
                                val game = LibraryGame(old.gameId, name.substringBeforeLast('.'), uri.toString(), system = checkNotNull(cachedSystem))
                                listed(game, repository.add(game), name); games++
                                directories.index(old.copy(missing = false)); unchanged++
                                continue
                            }
                            val taskContext = coroutineContext
                            val game = importer.import(uri, folder = ConsoleCatalog.console(hint), siblings = { track ->
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
                            val entry = LibraryGame(game.request.id, game.title, uri.toString(), system = game.request.system)
                            listed(entry, repository.add(entry), name); games++
                            directories.index(IndexedDocument(tree.toString(), uri.toString(), game.request.id, bytes, modified))
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            // Windows programs and text files often sit beside games. Only a folder named for the console makes one a claim.
                            if (!ConsoleCatalog.claims(name, hint)) continue
                            failure("$name: ${exception.message ?: "Cannot import this file"}")
                        }
                    }
                }
                // The discs of one game, by a playlist beside them or by their names, are listed as that game (ADR 0032).
                val groups = DiscSets.group(folderGames, playlistTexts)
                playlists += (playlistTexts.keys - groups.mapNotNull { it.playlist }.toSet()).size
                if (groups.isNotEmpty()) {
                    val systems = folderGames.associate { it.id to it.system }
                    repository.groupDiscs(groups).forEach { uncount(it, systems.getValue(it)) }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                complete = false
                failure(exception.message ?: "Cannot read this folder")
                if (visited >= 10_000) break
            }
        }
        directories.finishScan(tree.toString(), folderName, documents, complete, errors.firstOrNull())
        ScanResult(added, duplicates, failed, visited, errors, unchanged, addedSystems, knownSystems, others, archives, playlists, firstAdded, complete)
    }
}
