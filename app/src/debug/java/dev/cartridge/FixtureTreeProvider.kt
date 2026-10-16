package dev.cartridge

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Read-only, own-UID SAF fixture: nested originals, a duplicate, an invalid ROM and a non-ROM. */
class FixtureTreeProvider : DocumentsProvider() {
    private val ids = (0..5).map { "g$it" }.toSet() + setOf("root", "nested", "duplicate", "invalid", "readme", "m5-gba", "m5-gb", "m5-gbc", "ps1-root", "ps1-cue", "ps1-bin", "ps1-sbi", "discs", "saturn-cue", "saturn-bin", "ps2", "ps2-iso", "gdrom") + gdrom.keys
    // "ps2" is a folder named for its console: a CD-sized image in it is that console's by the folder alone.
    // "gdrom" is named for none: a GDI sheet is its console's anywhere, and brings its tracks with it.
    private val folders = setOf("root", "nested", "ps1-root", "discs", "ps2", "gdrom")
    private fun name(id: String) = gdrom[id] ?: if (id.startsWith("ps1-")) "probe.${id.removePrefix("ps1-")}" else if (id.startsWith("saturn-")) "saturn.${id.removePrefix("saturn-")}" else if (id.startsWith("ps2-")) "ps2.${id.removePrefix("ps2-")}" else if (id.startsWith("m5-")) "$id.${id.removePrefix("m5-")}" else "$id.gba"
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
        addRow(columnNames.map { when (it) { Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"; Root.COLUMN_TITLE -> "Original fixture"; else -> 0 } })
    }
    private fun append(cursor: MatrixCursor, id: String) {
        require(id in ids)
        val folder = id in folders
        cursor.addRow(cursor.columnNames.map { when (it) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> when { folder -> id; id == "readme" -> "readme.txt"; else -> name(id) }
            Document.COLUMN_MIME_TYPE -> if (folder) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_SIZE -> if (folder) 0L else file(id).length()
            Document.COLUMN_LAST_MODIFIED -> if (folder) 0L else file(id).lastModified()
            else -> null
        } })
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).also { append(it, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: columns).also { cursor ->
        if (parentDocumentId == "nested" && failNested) throw java.io.FileNotFoundException("Original fixture folder unavailable")
        val children = when (parentDocumentId) { "root" -> listOf("g0", "g1", "g2", "g3", "nested", "invalid", "readme", "m5-gba", "m5-gb", "m5-gbc"); "nested" -> listOf("g4", "g5", "duplicate"); "ps1-root" -> listOf("ps1-cue", "ps1-bin", "ps1-sbi"); "discs" -> listOf("saturn-cue", "saturn-bin"); "ps2" -> listOf("ps2-iso"); "gdrom" -> gdrom.keys.toList(); else -> error("Unknown fixture folder") }
        children.filter { it == "nested" || file(it).isFile }.forEach { append(cursor, it) }
    }
    private fun file(id: String): File {
        require(id in ids && id !in folders)
        return File(checkNotNull(context).filesDir, "m1-tree/${name(id)}")
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        reads++
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId in ids &&
        (parentDocumentId == "root" || parentDocumentId == "nested" && documentId in setOf("g4", "g5", "duplicate") || parentDocumentId == "ps1-root" && documentId in setOf("ps1-cue", "ps1-bin", "ps1-sbi") ||
        parentDocumentId == "discs" && documentId in setOf("saturn-cue", "saturn-bin") || parentDocumentId == "ps2" && documentId == "ps2-iso" ||
        parentDocumentId == "gdrom" && documentId in gdrom)
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    companion object {
        @Volatile var failNested = false; @Volatile var reads = 0
        private val gdrom = mapOf("gd-sheet" to "disc.gdi", "gd-one" to "track01.iso", "gd-two" to "track02.raw", "gd-three" to "track03.iso")
    }
}
