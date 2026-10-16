package dev.cartridge

import dev.cartridge.shaders.ShaderLibraryUpdater
import dev.cartridge.shaders.ShaderLibraryUpdater.Outcome
import dev.cartridge.shaders.ShaderSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Update button's work against made-up archives; nothing here touches the network. */
class ShaderLibraryUpdaterTest {
    @get:Rule val folder = TemporaryFolder()

    private val first = "a".repeat(40)
    private val second = "b".repeat(40)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> for ((name, data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()

    private fun library(commit: String, vararg extra: Pair<String, ByteArray>) = zip(
        "glsl-shaders-$commit/" to ByteArray(0),
        "glsl-shaders-$commit/crt/crt-test.glslp" to "shaders = 1\nshader0 = shaders/test.glsl\n".toByteArray(),
        "glsl-shaders-$commit/crt/shaders/test.glsl" to "void main() {}".toByteArray(),
        "glsl-shaders-$commit/crt/shaders/mask image.png" to byteArrayOf(1, 2, 3),
        "glsl-shaders-$commit/README.md" to "not kept".toByteArray(),
        *extra,
    )

    private class Source(var commit: String, var date: String = "2026-10-16T00:00:00Z", var archive: () -> InputStream) : ShaderSource {
        var downloads = 0
        override suspend fun newest() = ShaderSource.Commit(commit, date)
        override suspend fun archive(commit: String): ShaderSource.Download { downloads++; return ShaderSource.Download(archive(), -1) }
    }

    private val base get() = File(folder.root, "shaders")
    private val free: (File) -> Long = { Long.MAX_VALUE }
    private val installedDir get() = File(base, "library")

    @Test fun installsTheNewestAndThenSaysItIsUpToDate() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first)) }
        val updater = ShaderLibraryUpdater(base, source, free)
        assertNull(updater.installed())
        val progress = mutableListOf<ShaderLibraryUpdater.Progress>()
        val outcome = updater.update { progress.add(it) } as Outcome.Installed
        assertEquals(first, outcome.version.commit)
        assertEquals(1, outcome.version.presets)
        assertEquals(3, outcome.version.files)
        assertNull(outcome.replaced)
        // The repository's own folder is dropped; only shaders, presets and images are kept.
        assertTrue(File(installedDir, "crt/crt-test.glslp").isFile)
        assertTrue(File(installedDir, "crt/shaders/mask image.png").isFile)
        assertFalse(File(installedDir, "README.md").exists())
        assertTrue(progress.isNotEmpty())

        val again = updater.update()
        assertTrue(again is Outcome.UpToDate)
        assertEquals(1, source.downloads)
        assertEquals(first, updater.installed()!!.commit)
    }

    @Test fun aNewerCommitReplacesTheWholeLibrary() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first, "glsl-shaders-$first/old/gone.glslp" to "x".toByteArray())) }
        val updater = ShaderLibraryUpdater(base, source, free)
        updater.update()
        assertTrue(File(installedDir, "old/gone.glslp").isFile)
        source.commit = second
        source.archive = { ByteArrayInputStream(library(second)) }
        val outcome = updater.update() as Outcome.Installed
        assertEquals(first, outcome.replaced!!.commit)
        assertEquals(second, updater.installed()!!.commit)
        assertFalse(File(installedDir, "old/gone.glslp").exists())
        assertFalse(File(base, "staging").exists() || File(base, "previous").exists())
    }

    private fun installedThenFails(failing: () -> InputStream, containing: String, limits: ShaderLibraryUpdater.Limits = ShaderLibraryUpdater.Limits()) = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first)) }
        ShaderLibraryUpdater(base, source, free, limits).update()
        source.commit = second
        source.archive = failing
        try {
            ShaderLibraryUpdater(base, source, free, limits).update()
            fail("The update should have failed")
        } catch (expected: Exception) {
            assertTrue("${expected.message} should mention $containing", expected.message.orEmpty().contains(containing))
        }
        // The installed library is exactly as it was, and nothing half written is left.
        assertEquals(first, ShaderLibraryUpdater(base, source, free).installed()!!.commit)
        assertTrue(File(installedDir, "crt/crt-test.glslp").isFile)
        assertFalse(File(base, "staging").exists())
    }

    @Test fun aFailedUpdateLeavesTheInstalledLibrary() {
        installedThenFails({ ByteArrayInputStream(library(second, "glsl-shaders-$second/big.glsl" to ByteArray(4096))) }, "larger than a shader file",
            ShaderLibraryUpdater.Limits(entry = 1024))
        installedThenFails({ ByteArrayInputStream(library(second).copyOf(200)) }, "")
        installedThenFails({ ByteArrayInputStream(zip("one/a.glslp" to ByteArray(1), "two/b.glslp" to ByteArray(1))) }, "not one library")
        installedThenFails({ ByteArrayInputStream(zip("glsl-shaders-$second/notes.txt" to ByteArray(1))) }, "no shader presets")
        installedThenFails({ ByteArrayInputStream(library(second)) }, "larger than a shader library", ShaderLibraryUpdater.Limits(compressed = 100))
    }

    @Test fun pathsThatLeaveTheLibraryAreNeverWritten() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first,
            "glsl-shaders-$first/../escaped.glsl" to ByteArray(1),
            "glsl-shaders-$first/crt/../../escaped2.glsl" to ByteArray(1),
            "glsl-shaders-$first/a\\..\\b.glsl" to ByteArray(1))) }
        ShaderLibraryUpdater(base, source, free).update()
        assertFalse(File(base, "escaped.glsl").exists() || File(folder.root, "escaped2.glsl").exists())
        assertEquals(3, installedDir.walkTopDown().count { it.isFile && it.name != ShaderLibraryUpdater.VERSION_FILE })
    }

    @Test fun cancellingLeavesTheInstalledLibrary() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first)) }
        ShaderLibraryUpdater(base, source, free).update()
        val started = CompletableDeferred<Unit>()
        val stall = CompletableDeferred<Unit>()
        source.commit = second
        val bytes = library(second, "glsl-shaders-$second/large.glsl" to ByteArray(2 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) })
        source.archive = {
            object : InputStream() {
                val inner = ByteArrayInputStream(bytes)
                var given = 0
                override fun read(): Int = inner.read()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (given > 64 * 1024) { started.complete(Unit); runBlocking { stall.await() } }
                    return inner.read(buffer, offset, minOf(length, 8192)).also { given += maxOf(it, 0) }
                }
            }
        }
        val update = async(kotlinx.coroutines.Dispatchers.IO) { ShaderLibraryUpdater(base, source, free).update() }
        started.await()
        update.cancel()
        stall.complete(Unit)
        try { update.await(); fail("The update should have been cancelled") } catch (expected: CancellationException) { }
        assertEquals(first, ShaderLibraryUpdater(base, source, free).installed()!!.commit)
        assertFalse(File(base, "staging").exists())
    }

    @Test fun aSwapCutShortIsFinishedOrUndone() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first)) }
        ShaderLibraryUpdater(base, source, free).update()
        // Killed after the old library was moved aside and before the new one was put in its place.
        assertTrue(installedDir.renameTo(File(base, "previous")))
        File(base, "staging/half").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(first, ShaderLibraryUpdater(base, source, free).installed()!!.commit)
        assertFalse(File(base, "previous").exists() || File(base, "staging").exists())
    }

    @Test fun aFullDeviceIsSaidSoBeforeAnythingIsWritten() = runBlocking {
        val source = Source(first) { ByteArrayInputStream(library(first)) }
        try {
            ShaderLibraryUpdater(base, source, { 1024L }).update()
            fail("The update should have stopped")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("free space"))
        }
        assertEquals(0, source.downloads)
    }

    @Test fun onlyPlainPathsToShadersAndImagesAreWanted() {
        for (name in listOf("crt/crt-royale.glslp", "a/b/c.glsl", "borders/sgb/sgb-tvout+interlacing.glslp", "x/mask image.PNG", "y/z.jpg"))
            assertTrue(name, ShaderLibraryUpdater.wanted(name))
        for (name in listOf("../a.glsl", "a/../b.glsl", "/abs.glsl", "a\\b.glsl", "a//b.glsl", ".hidden/x.glsl", "a/.x.glsl", "readme.md",
                "a/ b.glsl", "x.glsl ", "c:/x.glsl", "a/./b.glsl", "a".repeat(250) + ".glsl"))
            assertFalse(name, ShaderLibraryUpdater.wanted(name))
    }
}
