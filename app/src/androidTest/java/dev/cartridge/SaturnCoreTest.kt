package dev.cartridge

import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.Media
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.Core
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.SaveBridge
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.FirmwareStore
import dev.cartridge.storage.GameFiles
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Saturn core on the device, as far as it can be taken without Sega's BIOS: the disc is recognised by
 * its own first sector, a console with no BIOS explains itself instead of failing, and with the original
 * stand-in image from tools/probe/generate_saturn_probe.py the core really loads, runs, draws and
 * serializes. No BIOS and no game are involved, so this says nothing about how any game plays.
 */
class SaturnCoreTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val system get() = File(context.filesDir, "m1-test-system")
    private val images = listOf("mpr-17933.bin", "sega_101.bin")
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    /** A fresh disc in the fixture folder, which is not named for any console: only its content says Saturn. */
    private fun disc() {
        val tree = File(context.filesDir, "m1-tree").apply { mkdirs() }
        File(context.filesDir, "cartridge-probe-saturn.cue").copyTo(File(tree, "saturn.cue"), true)
        val track = File(context.filesDir, "cartridge-probe-saturn.bin").readBytes()
        UUID.randomUUID().toString().toByteArray().copyInto(track, 4096)
        File(tree, "saturn.bin").writeBytes(track)
    }
    private fun addFolder() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://$FIXTURE_TREE/tree/discs"))))
            ui.tool("choose-folder").performClick()
        } finally { Intents.release() }
    }
    /** Mean colour of the middle of the presented picture and how many colours it holds. */
    private fun picture(scenario: ActivityScenario<DebugCollectionActivity>): Pair<Int, Int> {
        val result = CompletableFuture<Pair<Int, Int>>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete(0 to 0) }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x - 16, y - 16, x + 16, y + 16), bitmap, { status ->
                val colors = IntArray(1024)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(colors, 0, 32, 0, 0, 32, 32)
                result.complete(Color.rgb(colors.sumOf { Color.red(it) } / 1024, colors.sumOf { Color.green(it) } / 1024, colors.sumOf { Color.blue(it) } / 1024) to colors.toSet().size)
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }

    @Test fun aSaturnDiscWithoutItsBiosIsListedAndExplainsWhatItNeedsInsteadOfFailing() {
        val name = "m1-test-${UUID.randomUUID()}"
        images.forEach { File(system, it).delete() }; disc()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name).putExtra("stayInMenu", true)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                addFolder()
                // The import result names what the new console still needs and offers the one thing that supplies it.
                ui.waitUntil(30_000) { ui.shown("import-done") }
                assertTrue(ui.shown("import-bios-beetlesaturn"))
                await(scenario) { !it.coordinator.state.value.busy }
                ui.onNodeWithTag("import-done").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.SATURN }
                scenario.onActivity { activity ->
                    val game = activity.coordinator.state.value.selectedGame!!
                    // One game for the sheet and its track, kept together under generated names.
                    assertEquals(1, activity.coordinator.state.value.allGames.size)
                    val launcher = GameFiles.resolve(File(context.filesDir, "roms"), game.id, game.system)
                    assertEquals("game.cue", launcher.name); assertTrue(File(launcher.parentFile, "track-00.bin").isFile)
                    assertTrue(launcher.readText().contains("FILE \"track-00.bin\" BINARY")); assertTrue(launcher.parentFile!!.name.endsWith(".saturn"))
                    assertFalse(activity.shell.firmwareReady(Core.BeetleSaturn)); assertTrue(activity.shell.firmwareReady(Core.Pcsx))
                }
                // Starting it does not take the disc off the shelf and fail: the console says what it needs.
                ui.tool("insert").performClick()
                ui.waitUntil(5_000) { ui.shown("dialog-setup") }
                assertTrue(ui.shown("setup-mpr-17933") && ui.shown("setup-sega_101") && ui.shown("setup-close"))
                scenario.onActivity { assertEquals(SessionState.Idle, it.engine.status.value.state); assertEquals(CollectionScreen.Library, it.coordinator.state.value.screen)
                    assertNull(it.coordinator.state.value.launchFailure) }
                ui.onNodeWithTag("setup-close").performClick(); ui.waitUntil(5_000) { !ui.shown("dialog-setup") }
                // Settings names both regional files as missing, under Saturn.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-gameplay").performClick()
                ui.onNodeWithTag("settings-bios-mpr-17933").performScrollTo().assertIsDisplayed(); ui.onNodeWithTag("settings-bios-sega_101").performScrollTo().assertIsDisplayed()
                // A file that is not a known Saturn BIOS is refused in words, and nothing is written.
                File(context.filesDir, "m1-fixtures/invalid.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(512 * 1024)) }
                val refusal = runCatching { FirmwareStore(system).import(context.contentResolver, Uri.parse("content://$FIXTURE/invalid.png"), Core.BeetleSaturn) }.exceptionOrNull()
                assertTrue(refusal is IllegalArgumentException && refusal.message!!.contains("Saturn BIOS")); assertTrue(images.none { File(system, it).exists() })
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }

    @Test fun withAnOriginalStandInImageTheCoreLoadsRunsDrawsSerializesAndKeepsItsFilesToOneGame() {
        val name = "m1-test-${UUID.randomUUID()}"
        system.mkdirs(); disc()
        // The stand-in goes where the core looks, under both regional names. It is test equipment, not a BIOS.
        images.forEach { File(context.filesDir, "cartridge-probe-saturn-boot.bin").copyTo(File(system, it), true) }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name).putExtra("stayInMenu", true)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                addFolder()
                ui.waitUntil(30_000) { ui.shown("import-done") }
                assertFalse(ui.shown("import-bios-beetlesaturn"))
                await(scenario) { !it.coordinator.state.value.busy }
                ui.onNodeWithTag("import-done").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.SATURN }
                var id = ""
                scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id; assertTrue(it.shell.firmwareReady(Core.BeetleSaturn)); assertEquals(Media.entries.size * CollectionLayout.POOL, it.collectionSurface!!.meshInstanceCount) }
                ui.tool("insert").performClick()
                try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                catch (failure: Throwable) {
                    scenario.onActivity { android.util.Log.e("CartridgeSaturn", "Did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
                    throw failure
                }
                // The program's alternating white and red lines, drawn by the core's own video processor.
                var seen = 0 to 0
                try { ui.waitUntil(8000) { seen = picture(scenario); Color.red(seen.first) > 180 && Color.red(seen.first) - Color.green(seen.first) > 40 && seen.second > 1 } }
                catch (failure: Throwable) { android.util.Log.e("CartridgeSaturn", "Picture mean=${Integer.toHexString(seen.first)} colours=${seen.second}"); throw failure }
                ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                val saves = SaveStore(File(context.filesDir, "saves"))
                // The console's internal memory travels the same battery path as a cartridge's.
                val memory = CompletableFuture<Pair<Int, Long>>()
                scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
                    val core = File(context.applicationInfo.nativeLibraryDir, GameSystem.SATURN.coreFile).path
                    memory.complete(SaveBridge.read(core).size to SaveBridge.frameCount(core)) } }
                val (bytes, frames) = memory.get(3, TimeUnit.SECONDS)
                assertEquals(32 * 1024, bytes); assertTrue(frames >= 2); assertEquals(bytes, saves.read(id, "sram")!!.size)
                ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                val state = saves.read(id, "state")!!
                assertTrue(String(state, 0, 80, Charsets.US_ASCII).contains(GameSystem.SATURN.coreRevision))
                android.util.Log.i("CartridgeSaturn", "Stand-in image ran: picture=${Integer.toHexString(seen.first)}, internal memory=$bytes bytes, state=${state.size} bytes, frames=$frames")
                ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                scenario.onActivity { assertNull(it.engine.status.value.saveWarning); assertNull(it.coordinator.state.value.message?.takeIf { message -> "rejected" in message }) }
                ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                ui.waitUntil(8000) { picture(scenario).let { (mean, colours) -> Color.red(mean) > 180 && Color.red(mean) - Color.green(mean) > 40 && colours > 1 } }
                ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                // What the core writes for itself stays in this game's own folder, never beside another game's.
                val own = File(context.filesDir, "saves/$id.files")
                assertTrue(own.isDirectory); assertTrue(File(context.filesDir, "saves").listFiles()!!.none { it.isFile && it.name.startsWith("game.") })
                android.util.Log.i("CartridgeSaturn", "Core's own files for this game: ${own.list()!!.sorted()}")
            }
        } finally {
            images.forEach { File(system, it).delete() }
            DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db")
        }
    }
}
