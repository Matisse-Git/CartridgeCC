package dev.cartridge

import dev.cartridge.storage.BoundedArchive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundedArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }; output.toByteArray()
    }
    @Test fun pathsCannotEscapeAndOutputFilenamesAreGenerated() {
        listOf("../label.png", "/label.png", "a/../../label.png", "a\\label.png", "C:/label.png", "a//b", "a/./b").forEach { assertFalse(it, BoundedArchive.safeName(it)) }
        val staging = temporary.newFolder()
        val result = BoundedArchive.read(ByteArrayInputStream(zip("media/label.png", byteArrayOf(1, 2))), staging)
        assertEquals(setOf("media/label.png"), result.keys)
        assertEquals(staging.canonicalFile, result.values.single().parentFile!!.canonicalFile)
        assertArrayEquals(byteArrayOf(1, 2), result.values.single().readBytes())
        assertFalse(result.values.single().name.contains("label"))
    }
    @Test fun aCompressedBombStopsAtTheDecompressedEntryBound() {
        val bytes = zip("label.png", ByteArray(13 * 1024 * 1024))
        assertTrue(bytes.size < 100_000)
        try { BoundedArchive.read(ByteArrayInputStream(bytes), temporary.newFolder()); fail("Oversized expanded image must fail") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("limits")) }
    }
    @Test fun unsafeAndDataBearingDirectoryEntriesAreRejected() {
        for (name in listOf("../escape", "folder/")) {
            try { BoundedArchive.read(ByteArrayInputStream(zip(name, byteArrayOf(1))), temporary.newFolder()); fail("Unsafe archive must fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
