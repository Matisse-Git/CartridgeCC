package dev.cartridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.SessionState
import dev.cartridge.library.*
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CollectionLayoutDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        try { ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready } }
        catch (failure: Throwable) {
            scenario.onActivity { Log.e("CartridgeScale", "life=${it.lifecycle.currentState} loaded=${it.coordinator.state.value.loaded} ready=${it.coordinator.state.value.rendererReady} screen=${it.coordinator.state.value.screen} generation=${it.coordinator.state.value.viewportGeneration} message=${it.coordinator.state.value.message} renderer=${it.collectionSurface?.diagnosticState}") }
            throw failure
        }
    }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(80)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }; ui.waitForIdle()
    }
    private fun stick(scenario: ActivityScenario<DebugCollectionActivity>, y: Float) {
        scenario.onActivity { activity ->
            val now = SystemClock.uptimeMillis()
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
            val coordinates = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_Y, y) }
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, arrayOf(properties),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
            try { activity.dispatchGenericMotionEvent(event) } finally { event.recycle() }
        }
    }
    @Test fun threeLayoutsRetainFocusRenderRealLabelsAndHandOffToNativeGameplay() {
        val name = "m1-test-${UUID.randomUUID()}"
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val ids = mutableListOf<String>()
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
        val label = LabelStore(File(context.filesDir, "m1-test-labels")).importStream("Original layout fixture") { bytes.inputStream() }
        runBlocking {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            repeat(12) { index ->
                val rom = probe + byteArrayOf((index + 70).toByte()); val id = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
                ids += id; File(context.filesDir, "roms/$id.gba").apply { parentFile!!.mkdirs(); writeBytes(rom) }
                repository.add(LibraryGame(id, "Original layout $index", "content://fixture")); repository.setLabel(id, label)
            }
            repository.select(4)
        }
        database.close()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                repeat(7) { key(scenario, KeyEvent.KEYCODE_DPAD_DOWN) }
                ui.onNodeWithTag("layout-setting").assertIsDisplayed()
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                scenario.onActivity { assertEquals(CollectionLayout.Vertical, it.coordinator.state.value.layout); assertEquals(ids[4], it.coordinator.state.value.selectedGame!!.id) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertEquals(ids[5], it.coordinator.state.value.selectedGame!!.id) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); ui.onNodeWithTag("layout-setting").performScrollTo().performClick(); key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                scenario.onActivity { assertEquals(CollectionLayout.Grid, it.coordinator.state.value.layout); assertEquals(ids[5], it.coordinator.state.value.selectedGame!!.id) }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT); scenario.onActivity { assertEquals(5, it.coordinator.state.value.selection.index) }
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); scenario.onActivity { assertEquals(8, it.coordinator.state.value.selection.index) }
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(CollectionLayout.Grid, it.coordinator.state.value.layout); assertEquals(ids[8], it.coordinator.state.value.selectedGame!!.id); assertEquals(9, it.collectionSurface!!.poolCapacity) }
                stick(scenario, .2f); Thread.sleep(150)
                scenario.onActivity { assertEquals(8, it.coordinator.state.value.selection.index) }
                stick(scenario, -1f)
                await(scenario) { it.coordinator.state.value.selection.index == 2 } // Initial move plus held repeat.
                stick(scenario, 1f)
                await(scenario) { it.coordinator.state.value.selection.index == 5 } // Reverse immediately.
                stick(scenario, 0f); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertEquals(8, it.coordinator.state.value.selection.index) }
                scenario.onActivity { it.coordinator.previewLabel { label.copy(quarterTurns = 1) } }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.labelPreviewReady }
                ui.onNodeWithTag("label-action-2").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library }
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                ui.onNodeWithTag("pause").performClick(); await(scenario) { it.engine.status.value.state == SessionState.Paused && !it.coordinator.state.value.busy }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady && !it.coordinator.state.value.busy }
                scenario.onActivity { assertEquals(CollectionLayout.Grid, it.coordinator.state.value.layout); assertEquals(ids[8], it.coordinator.state.value.selectedGame!!.id) }
                // Original blue print is visible in a real SurfaceView, rather than a Compose image.
                ui.waitUntil(8000) {
                    val pixel = CompletableFuture<Int>()
                    scenario.onActivity { activity ->
                        val view = activity.collectionSurface!!; val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                        // Selected grid cell is right/centre row for index 8 (row2) with viewport centre row2.
                        val x = (view.width * .82).toInt(); val y = view.height / 2
                        PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), image, { status -> pixel.complete(if (status == PixelCopy.SUCCESS) image.getPixel(4, 4) else 0); image.recycle() }, Handler(Looper.getMainLooper()))
                    }
                    val color = pixel.get(3, TimeUnit.SECONDS); Color.blue(color) > 70 && Color.blue(color) > Color.red(color) * 2
                }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    @Test fun fiveHundredTitleNavigationKeepsGpuAndLabelWindowBounded() {
        val name = "m1-test-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking { val repository = RoomCollectionRepository(context, database); repository.initialize(); repeat(500) { repository.add(LibraryGame((it + 1).toString(16).padStart(64, '0'), "Original scale fixture $it", "content://fixture")) } }
        database.close()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                val startPss = Debug.getPss()
                for (layout in CollectionLayout.entries) {
                    scenario.onActivity { it.coordinator.settings(layout = layout) }
                    repeat(80) { n -> scenario.onActivity { it.coordinator.navigate(if (n % 2 == 0) 47 else -13) }; Thread.sleep(20) }
                    scenario.onActivity {
                        assertEquals(500, it.coordinator.state.value.allGames.size)
                        assertTrue(it.coordinator.state.value.labels.size <= layout.visibleCount)
                        assertEquals(9, it.collectionSurface!!.poolCapacity)
                        assertTrue(it.coordinator.state.value.selection.index in 0..499)
                    }
                }
                repeat(8) {
                    scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                    scenario.onActivity { assertEquals(CollectionLayout.Grid, it.coordinator.state.value.layout); assertEquals(500, it.coordinator.state.value.allGames.size) }
                }
                Log.i("CartridgeScale", "500 original records; fixed nine-instance pool; PSS before=$startPss KiB, after=${Debug.getPss()} KiB")
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
}
