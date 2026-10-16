package dev.cartridge

import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.shaders.GitHubShaderSource
import dev.cartridge.shaders.ShaderLibrary
import dev.cartridge.shaders.ShaderLibraryUpdater
import dev.cartridge.shaders.ShaderLibraryUpdater.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR 0027, step 3: the Update button's work against GitHub itself, on the device's own network, in a folder
 * of the test's own so the app's installed library is not touched. Needs the network.
 */
class ShaderLibraryUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val freeSpace: (File) -> Long = { folder ->
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(folder))
    }

    @Test fun downloadsTheNewestLibraryOnceAndKeepsItThroughCancelAndFailure() = runBlocking {
        val base = File(context.cacheDir, "shader-update-test").apply { deleteRecursively() }
        try {
            val updater = ShaderLibraryUpdater(base, GitHubShaderSource(), freeSpace)
            assertNull(updater.installed())

            val downloaded = AtomicLong()
            val began = System.nanoTime()
            val installed = updater.update { downloaded.set(it.downloaded) } as Outcome.Installed
            val seconds = (System.nanoTime() - began) / 1e9
            val version = installed.version
            android.util.Log.i(TAG, "installed ${version.commit} of ${version.date}: ${version.presets} presets, ${version.files} files, " +
                "${version.bytes} bytes from a ${downloaded.get()}-byte archive in ${"%.1f".format(seconds)} s")
            assertTrue(version.presets >= 500)
            val library = File(base, "library")
            assertTrue(File(library, "crt/crt-royale.glslp").isFile)
            assertFalse(File(base, "staging").exists() || File(base, "previous").exists())

            // What was downloaded is what the host draws from.
            val check = kotlinx.coroutines.withContext(Dispatchers.IO) {
                ShaderLibrary.check(ShaderLibrary.load(library, File(library, "crt/crt-royale.glslp")))
            }
            assertNull(check)

            // Pressed again: GitHub is asked, nothing is downloaded.
            val again = updater.update { fail("Nothing should be downloaded when the library is up to date") }
            assertTrue(again is Outcome.UpToDate)

            // An update cancelled while downloading leaves the installed library as it was. The installed commit is
            // made to look old so that a download starts.
            val versionFile = File(library, ShaderLibraryUpdater.VERSION_FILE)
            val genuine = versionFile.readText()
            val pretend = "0".repeat(40)
            versionFile.writeText(genuine.replace(version.commit, pretend))
            val cancelled = async(Dispatchers.IO) {
                updater.update { if (it.downloaded > 4L * 1024 * 1024) throw CancellationException("cancelled by the test") }
            }
            try { cancelled.await(); fail("The update should have been cancelled") } catch (expected: CancellationException) { }
            assertEquals(pretend, updater.installed()!!.commit)
            assertTrue(File(library, "crt/crt-royale.glslp").isFile)
            assertFalse(File(base, "staging").exists())

            // A source that fails leaves it too, with a reason the player can read.
            try {
                ShaderLibraryUpdater(base, GitHubShaderSource(repository = "libretro/no-such-shader-library"), freeSpace).update()
                fail("The update should have failed")
            } catch (expected: IllegalStateException) {
                android.util.Log.i(TAG, "a missing repository: ${expected.message}")
                assertTrue(expected.message.orEmpty().contains("not found"))
            }
            assertEquals(pretend, updater.installed()!!.commit)

            // And a real update after all that puts the newest one back.
            val restored = updater.update() as Outcome.Installed
            assertEquals(version.commit, restored.version.commit)
            assertEquals(pretend, restored.replaced!!.commit)
        } finally {
            base.deleteRecursively()
        }
    }

    private companion object { const val TAG = "CartridgeShaders" }
}
