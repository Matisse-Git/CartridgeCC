package dev.cartridge

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
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.session.CollectionScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LibraryGrowthTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun describe(scenario: ActivityScenario<DebugCollectionActivity>) = scenario.onActivity {
        android.util.Log.e("CartridgeGrowth", "top=${it.shell.top} import=${it.shell.import} lastScan=${it.shell.lastScan} roots=${it.shell.roots.size} " +
            "busy=${it.coordinator.state.value.busy} scanning=${it.coordinator.state.value.scanning} screen=${it.coordinator.state.value.screen} message=${it.coordinator.state.value.message}")
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, predicate: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = predicate(it) }; ready }
    }
    private fun choose(tag: String, action: String, uri: Uri) {
        Intents.init()
        try {
            intending(hasAction(action)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ui.tool(tag).performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    private fun bluePixels(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.waitForIdle()
        ui.waitUntil(8000) {
            val color = CompletableFuture<Int>()
            scenario.onActivity {
                val view = checkNotNull(it.collectionSurface)
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                val x = (view.width * it.labelCentre()).toInt(); val y = view.height / 2
                PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), bitmap, { result ->
                    color.complete(if (result == PixelCopy.SUCCESS) bitmap.getPixel(4, 4) else 0); bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
            val pixel = color.get(3, TimeUnit.SECONDS)
            Color.blue(pixel) > 100 && Color.blue(pixel) > Color.red(pixel) * 2 && Color.blue(pixel) > Color.green(pixel) * 2
        }
    }
    @Test fun nestedSafImportGrowsAndRemovalRestoresEnrichmentAndSavesBeyondThreeSlots() {
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val tree = File(context.filesDir, "m1-tree").apply { mkdirs() }
        repeat(6) { File(tree, "g$it.gba").writeBytes(if (it == 0) probe else probe + byteArrayOf(it.toByte())) }
        File(tree, "duplicate.gba").writeBytes(probe)
        File(tree, "invalid.gba").writeBytes(byteArrayOf(1, 2))
        File(tree, "readme.gba").writeText("Original ignored fixture")
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        File(fixtures, "probe-one.gba").writeBytes(probe)
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        File(fixtures, "blue-label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val databaseName = "m1-test-${UUID.randomUUID()}"
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", databaseName)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                choose("choose-game", Intent.ACTION_OPEN_DOCUMENT, Uri.parse("content://dev.cartridge.fixture/probe-one.gba"))
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                var originalId = ""
                scenario.onActivity { originalId = it.coordinator.state.value.selectedGame!!.id }
                choose("choose-folder", Intent.ACTION_OPEN_DOCUMENT_TREE, Uri.parse("content://dev.cartridge.fixture-tree/tree/root"))
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.slots.size == 6 }
                scenario.onActivity {
                    assertEquals(originalId, it.coordinator.state.value.selectedGame!!.id)
                    assertEquals(6, it.coordinator.state.value.selection.count)
                    it.shell.lastScan!!.let { scan -> assertEquals(5, scan.added); assertEquals(2, scan.duplicates); assertEquals(1, scan.failed) }
                }
                ui.tool("rescan").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.shell.lastScan?.let { scan -> scan.added == 0 && scan.duplicates == 7 && scan.failed == 1 } == true }
                scenario.onActivity { assertEquals(6, it.coordinator.state.value.slots.size); it.coordinator.navigate(5) }
                ui.waitForIdle()
                choose("choose-label", Intent.ACTION_OPEN_DOCUMENT, Uri.parse("content://dev.cartridge.fixture/blue-label.png"))
                await(scenario) { it.coordinator.state.value.labelPreviewReady }
                bluePixels(scenario)
                ui.onNodeWithTag("label-action-2").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.labelDraft == null }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(5, it.coordinator.state.value.selection.index); assertNotNull(it.coordinator.state.value.selectedGame!!.label) }
                bluePixels(scenario)
            }
            DebugCollectionActivity.closeDatabases()
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(6, it.coordinator.state.value.slots.size); assertEquals(5, it.coordinator.state.value.selection.index) }
                bluePixels(scenario)
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                ui.onNodeWithTag("pause").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                ui.onNodeWithTag("menu-1").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message == "State saved" }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(5, it.coordinator.state.value.selection.index) }
                bluePixels(scenario)
                ui.tool("rescan").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.shell.lastScan?.let { scan -> scan.added == 0 && scan.duplicates == 7 && scan.failed == 1 } == true }
                var gameId = ""
                scenario.onActivity { gameId = it.coordinator.state.value.selectedGame!!.id }
                val save = File(context.filesDir, "saves/$gameId.state")
                val savedBytes = save.readBytes()
                assertTrue(savedBytes.isNotEmpty())
                ui.tool("hide").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.slots.size == 5 }
                var earlier = 0; scenario.onActivity { earlier = it.shell.scansFinished }
                ui.tool("rescan").performClick()
                try { await(scenario) { !it.coordinator.state.value.busy && it.shell.scansFinished > earlier } } catch (failure: Throwable) { describe(scenario); throw failure }
                scenario.onActivity { assertEquals(5, it.coordinator.state.value.slots.size) }
                assertArrayEquals(savedBytes, save.readBytes())
                assertTrue(File(context.filesDir, "roms/$gameId.gba").isFile)
                assertTrue(File(tree, "g5.gba").isFile)
                ui.tool("undo-hide").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == gameId }
                scenario.onActivity { assertEquals(6, it.coordinator.state.value.slots.size); assertEquals(5, it.coordinator.state.value.selection.index) }
                bluePixels(scenario)
                ui.tool("hide").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.slots.size == 5 }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                ui.tool("rescan").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.shell.lastScan != null }
                scenario.onActivity { assertEquals(5, it.coordinator.state.value.slots.size) }
                File(fixtures, "probe-two.gba").writeBytes(File(tree, "g5.gba").readBytes())
                choose("choose-game", Intent.ACTION_OPEN_DOCUMENT, Uri.parse("content://dev.cartridge.fixture/probe-two.gba"))
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == gameId }
                scenario.onActivity { assertEquals(6, it.coordinator.state.value.slots.size); assertNotNull(it.coordinator.state.value.selectedGame!!.label) }
                bluePixels(scenario)
                assertArrayEquals(savedBytes, save.readBytes())
            }
        } finally { DebugCollectionActivity.closeDatabases() }
    }
}
