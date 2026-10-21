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
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.SaveBridge
import dev.cartridge.session.CollectionScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BatterySaveTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, condition: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = condition(it) }; ready }
    }
    private fun fixture(): Pair<ActivityScenario<DebugCollectionActivity>, File> {
        val nonce = UUID.randomUUID().toString()
        val bytes = File(context.filesDir, "battery-probe.gba").readBytes() + nonce.toByteArray()
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(context.filesDir, "m1-fixtures").mkdirs()
        File(context.filesDir, "m1-fixtures/battery-probe.gba").writeBytes(bytes)
        val scenario = ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java)
            .putExtra("testDatabase", "m1-test-$nonce"))
        await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://$FIXTURE/battery-probe.gba"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
        await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == id }
        return scenario to File(context.filesDir, "saves/$id.sram")
    }
    private fun insert(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.tool("insert").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        var paused = false; scenario.onActivity { paused = it.engine.status.value.state == SessionState.Paused }
        if (!paused) ui.onNodeWithTag("pause").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    private fun eject(scenario: ActivityScenario<DebugCollectionActivity>) {
        pause(scenario); ui.onNodeWithTag("menu-3").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    private fun color(scenario: ActivityScenario<DebugCollectionActivity>, green: Boolean) {
        ui.waitForIdle()
        ui.waitUntil(8_000) {
            val pixel = CompletableFuture<Int>()
            scenario.onActivity {
                it.engine.frozenFrame.value?.let { frame ->
                    if (it.engine.status.value.state == SessionState.Paused) {
                        pixel.complete(frame.getPixel(frame.width / 2, frame.height / 2)); return@onActivity
                    }
                }
                val view = checkNotNull(it.engine.surface.value)
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                val x = view.width / 2; val y = view.height / 2
                PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), bitmap, { result ->
                    pixel.complete(if (result == PixelCopy.SUCCESS) bitmap.getPixel(4, 4) else 0); bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
            val value = pixel.get(3, TimeUnit.SECONDS)
            if (green) Color.green(value) > 180 && Color.red(value) < 40 && Color.blue(value) < 40
            else Color.red(value) in 40..180 && kotlin.math.abs(Color.red(value) - Color.green(value)) < 15 && kotlin.math.abs(Color.green(value) - Color.blue(value)) < 15
        }
    }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int, green: Boolean) {
        scenario.onActivity { it.engine.key(KeyEvent.ACTION_DOWN, code) }
        color(scenario, green)
        scenario.onActivity { it.engine.key(KeyEvent.ACTION_UP, code) }
    }
    @Test fun batterySaveRestoresBeforeBootAndSurvivesPauseEjectBackgroundAndBadFiles() {
        val (scenario, save) = fixture()
        scenario.use {
            insert(it); color(it, green = false)
            key(it, KeyEvent.KEYCODE_BUTTON_A, green = true)
            pause(it)
            assertEquals(32 * 1024, save.length().toInt()); assertEquals(1, save.readBytes()[0].toInt())
            ui.onNodeWithTag("menu-0").performClick()
            await(it) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            eject(it); insert(it); color(it, green = true) // Boot reads SRAM once: late restoration fails.
            key(it, KeyEvent.KEYCODE_BUTTON_B, green = false)
            eject(it); insert(it); color(it, green = false)
            key(it, KeyEvent.KEYCODE_BUTTON_A, green = true)
            it.moveToState(Lifecycle.State.CREATED)
            await(it) { it.engine.status.value.state == SessionState.Idle && !it.coordinator.state.value.busy }
            it.moveToState(Lifecycle.State.RESUMED)
            await(it) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
            color(it, green = true)
            eject(it) // Clear automatic session; subsequent cold boot proves battery data independently.
            insert(it); color(it, green = true)
            pause(it)
            val rejected = CompletableFuture<Boolean>()
            it.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
                val core = File(context.applicationInfo.nativeLibraryDir, "libmgba_libretro.so").path
                val before = SaveBridge.read(core)
                rejected.complete(!SaveBridge.restore(core, ByteArray(128 * 1024 + 1)) && before.contentEquals(SaveBridge.read(core)))
            } }
            assertTrue("Oversized restore must reject without changing core memory", rejected.get(3, TimeUnit.SECONDS))
            ui.onNodeWithTag("menu-3").performClick()
            await(it) { !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady }
            val valid = save.readBytes()
            val invalid = valid.copyOf(128 * 1024 + 1)
            save.writeBytes(invalid)
            ui.tool("insert").performClick()
            await(it) { !it.coordinator.state.value.busy && it.coordinator.state.value.message.orEmpty().contains("too large") }
            assertArrayEquals("Failed preparation must preserve the stored file", invalid, save.readBytes())
            save.writeBytes(valid)
            insert(it); color(it, green = true)
            val id = save.nameWithoutExtension
            assertFalse("Battery test must not rely on an emulator save state", File(context.filesDir, "saves/$id.state").exists())
            eject(it)
        }
        DebugCollectionActivity.closeDatabases()
    }
    @Test fun productionIntervalCheckpointSurvivesAbruptNativeReleaseAndActivityRecreation() {
        val (scenario, save) = fixture()
        scenario.use {
            insert(it); key(it, KeyEvent.KEYCODE_BUTTON_A, green = true)
            assertFalse(save.exists())
            assertTrue(save.mkdir()) // Only this unique test game's destination; never the user's saves.
            pause(it)
            it.onActivity { activity -> assertNotNull(activity.engine.status.value.saveWarning) }
            ui.onNodeWithTag("message").assertTextContains("Save checkpoint could not be written", substring = true)
            assertTrue(save.isDirectory); assertTrue(save.delete())
            ui.onNodeWithTag("menu-0").performClick()
            await(it) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            ui.waitUntil(25_000) { save.isFile && save.readBytes().firstOrNull()?.toInt() == 1 }
            assertEquals(32 * 1024, save.length().toInt())
            it.onActivity { activity ->
                assertEquals(SessionState.Running, activity.engine.status.value.state)
                assertNull(activity.engine.status.value.saveWarning)
                activity.coordinator.destroy()
                activity.engine.releaseOnDestroy() // Deliberately skip pause/stop/final save.
            }
            it.recreate()
            await(it) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            insert(it); color(it, green = true)
            eject(it)
        }
        DebugCollectionActivity.closeDatabases()
    }
}
