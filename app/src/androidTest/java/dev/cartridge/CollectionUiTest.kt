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
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.library.LabelFit
import dev.cartridge.session.CollectionScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CollectionUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): ActivityScenario<DebugCollectionActivity> {
        val probe = File(context.filesDir, "cartridge-probe.gba")
        check(probe.isFile) { "Run tools/Test-Device.ps1 to push the original diagnostic first" }
        val root = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        val bytes = probe.readBytes()
        File(root, "probe-one.gba").writeBytes(bytes)
        File(root, "probe-two.gba").writeBytes(bytes.copyOf().also { it[0xab] = '1'.code.toByte() })
        for ((name, color) in listOf("red-label.png" to Color.RED, "blue-label.png" to Color.BLUE)) {
            val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            File(root, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(root, "invalid.png").writeText("This is not an image")
        return ActivityScenario.launch(Intent(context, DebugCollectionActivity::class.java)
            .putExtra("testDatabase", "m1-test-${UUID.randomUUID()}"))
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, condition: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = condition(it) }; ready }
    }
    private fun choose(tag: String, filename: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://dev.cartridge.fixture/$filename"))))
            ui.onNodeWithTag(tag).performClick()
            ui.waitForIdle()
        } finally { Intents.release() }
    }
    private fun awaitLabelColor(scenario: ActivityScenario<DebugCollectionActivity>, red: Boolean) {
        // Flush Compose's test clock before polling the independently clocked SurfaceView.
        ui.waitForIdle()
        val deadline = System.nanoTime() + 8_000_000_000L
        var last = 0
        while (System.nanoTime() < deadline) {
            val result = CompletableFuture<Int>()
            scenario.onActivity { activity ->
                val view = checkNotNull(activity.collectionSurface)
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                val x = view.width / 2; val y = view.height / 2
                PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), bitmap, { status ->
                    result.complete(if (status == PixelCopy.SUCCESS) bitmap.getPixel(4, 4) else 0)
                    bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
            last = result.get(3, TimeUnit.SECONDS)
            val dominant = if (red) Color.red(last) else Color.blue(last)
            val other = if (red) Color.blue(last) else Color.red(last)
            if (dominant > 100 && dominant > other * 2 && dominant > Color.green(last) * 2) return
            Thread.sleep(100)
        }
        fail("Actual 3D label pixels did not become ${if (red) "red" else "blue"}: ${Integer.toHexString(last)}")
    }

    @Test fun independentLabelsPersistAcrossRecreationAndCancelledPreview() {
        var databaseName: String? = null
        var acceptedLabel: String? = null
        fixture().use { scenario ->
            scenario.onActivity { databaseName = it.intent.getStringExtra("testDatabase") }
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            choose("choose-game", "probe-one.gba")
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
            choose("choose-label", "red-label.png")
            await(scenario) { it.coordinator.state.value.labelPreviewReady }
            awaitLabelColor(scenario, red = true)
            ui.onNodeWithTag("label-action-0").performClick()
            ui.onNodeWithTag("label-action-1").performClick()
            await(scenario) { it.coordinator.state.value.labelPreviewReady }
            ui.onNodeWithTag("label-action-2").performClick()
            await(scenario) { it.coordinator.state.value.screen == CollectionScreen.Library && !it.coordinator.state.value.busy }
            choose("choose-game", "probe-two.gba")
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
            scenario.onActivity { assertEquals("Add did not select the new game", 1, it.coordinator.state.value.selection.index) }
            choose("choose-label", "blue-label.png")
            await(scenario) { it.coordinator.state.value.labelPreviewReady }
            awaitLabelColor(scenario, red = false)
            ui.onNodeWithTag("label-action-2").performClick()
            await(scenario) { it.coordinator.state.value.screen == CollectionScreen.Library && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("previous").performClick()
            ui.waitForIdle()
            scenario.onActivity {
                assertEquals("Previous did not select slot 0", 0, it.coordinator.state.value.selection.index)
                assertEquals("Viewport did not receive selection", 0, it.collectionSurface!!.selection)
                assertNotEquals("ROM identities were merged", it.coordinator.state.value.slots[0]!!.id, it.coordinator.state.value.slots[1]!!.id)
                assertNotEquals("Label selections were merged", it.coordinator.state.value.slots[0]!!.label!!.assetId, it.coordinator.state.value.slots[1]!!.label!!.assetId)
            }
            awaitLabelColor(scenario, red = true)
            scenario.recreate()
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            scenario.onActivity {
                assertEquals(0, it.coordinator.state.value.selection.index)
                assertEquals(1, it.coordinator.state.value.selectedGame!!.label!!.quarterTurns)
                assertEquals(LabelFit.Crop, it.coordinator.state.value.selectedGame!!.label!!.fit)
                acceptedLabel = it.coordinator.state.value.selectedGame!!.label!!.assetId
                assertNotEquals(it.coordinator.state.value.slots[0]!!.label!!.assetId, it.coordinator.state.value.slots[1]!!.label!!.assetId)
            }
            awaitLabelColor(scenario, red = true)
            choose("choose-label", "blue-label.png")
            await(scenario) { it.coordinator.state.value.labelPreviewReady }
            awaitLabelColor(scenario, red = false)
            ui.onNodeWithTag("label-action-3").performClick()
            awaitLabelColor(scenario, red = true)
            choose("choose-game", "probe-one.gba") // Reimport must preserve this game's label.
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message == "Selected the existing entry" }
            awaitLabelColor(scenario, red = true)
            choose("choose-label", "invalid.png")
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message?.contains("Choose a PNG") == true }
            awaitLabelColor(scenario, red = true)
        }
        DebugCollectionActivity.closeDatabases()
        // Reopen the on-disk Room database rather than only reusing its in-process instance.
        ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java)
            .putExtra("testDatabase", checkNotNull(databaseName))).use { scenario ->
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            scenario.onActivity {
                assertEquals(0, it.coordinator.state.value.selection.index)
                assertEquals(acceptedLabel, it.coordinator.state.value.selectedGame!!.label!!.assetId)
                assertEquals(LabelFit.Crop, it.coordinator.state.value.selectedGame!!.label!!.fit)
                assertEquals(1, it.coordinator.state.value.selectedGame!!.label!!.quarterTurns)
                assertNotNull(it.coordinator.state.value.slots[1])
            }
            awaitLabelColor(scenario, red = true)
        }
        DebugCollectionActivity.closeDatabases()
    }

    @Test fun ordinaryUiSessionsRecoverAfterBackgroundRecreationAndCancelledInsertion() {
        fixture().use { scenario ->
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            choose("choose-game", "probe-one.gba")
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
            ui.onNodeWithTag("insert").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Running && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("pause").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Paused && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("menu-1").performClick()
            await(scenario) { it.coordinator.state.value.message == "State saved" && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("menu-2").performClick()
            await(scenario) { it.coordinator.state.value.message == "State loaded" && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("menu-0").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Running && !it.coordinator.state.value.busy }
            scenario.moveToState(Lifecycle.State.CREATED)
            await(scenario) { it.engine.status.value.state == SessionState.Idle && !it.coordinator.state.value.busy }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario) { it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
            ui.onNodeWithTag("insert").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Running && !it.coordinator.state.value.busy }
            scenario.recreate()
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            scenario.onActivity { it.coordinator.settings(reducedMotion = false); it.coordinator.insert(); it.coordinator.background() }
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Idle }
            scenario.onActivity { it.coordinator.foreground() }
            await(scenario) { it.coordinator.state.value.rendererReady }
            ui.onNodeWithTag("insert").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Running && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("pause").performClick()
            await(scenario) { it.engine.status.value.state == SessionState.Paused && !it.coordinator.state.value.busy }
            ui.onNodeWithTag("menu-3").performClick()
            await(scenario) { it.coordinator.state.value.screen == CollectionScreen.Library && !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady }
        }
        DebugCollectionActivity.closeDatabases()
    }
}
