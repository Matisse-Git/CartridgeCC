package dev.cartridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Debug-only original test documents. The test stubs picker results, never rendering/emulation. */
class FixtureDocumentProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment
        require(name in setOf("probe-one.gba", "probe-two.gba", "battery-probe.gba", "probe.gb", "probe.gbc", "probe.sfc", "probe.nes", "probe.md", "probe.sms", "probe.gg", "probe.elf", "probe-ps2.elf", "probe.dol", "probe.exe", "probe.iso", "probe.chd", "probe.cue", "probe.bin", "red-label.png", "blue-label.png", "invalid.png", "stand-in-bios.bin", "original-pack.zip", "backup.zip"))
        return File(checkNotNull(context).filesDir, "m1-fixtures/$name")
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (file(uri).extension == "png") "image/png" else "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment == "backup.zip" && mode in setOf("w", "wt")) return ParcelFileDescriptor.open(file(uri),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val source = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) source.name else source.length() }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
