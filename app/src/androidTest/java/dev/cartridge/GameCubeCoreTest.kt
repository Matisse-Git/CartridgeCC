package dev.cartridge

import androidx.lifecycle.Lifecycle
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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import dev.cartridge.environment.GameOptions
import dev.cartridge.session.CollectionScreen
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
 * The GameCube core on the device. The original program from tools/probe/generate_gamecube_probe.py is started
 * directly, without the console's own menu: its picture through the host's OpenGL ES context, a button, the analog
 * stick and the folder the core keeps for the game. A state of this console is too large to hold, so none is offered.
 */
class GameCubeCoreTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    @Volatile private var seen = ""
    /** Which of the program's colours the stripes in the middle of the presented picture carry. */
    private fun colour(scenario: ActivityScenario<DebugCollectionActivity>): String {
        val result = CompletableFuture<String>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete("none") }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x - 16, y - 16, x + 16, y + 16), bitmap, { status ->
                val colors = IntArray(1024)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(colors, 0, 32, 0, 0, 32, 32)
                val red = colors.sumOf { Color.red(it) } / 1024; val green = colors.sumOf { Color.green(it) } / 1024; val blue = colors.sumOf { Color.blue(it) } / 1024
                seen = "status=$status colours=${colors.toSet().size} mean=$red,$green,$blue"
                // White lines and one colour in equal parts: that colour's channel stays full, the others fall to about half.
                result.complete(when {
                    status != PixelCopy.SUCCESS -> "none"
                    red > 180 && red - green > 40 && red - blue > 40 -> "red"
                    green > 180 && green - red > 40 && green - blue > 40 -> "green"
                    blue > 180 && blue - red > 40 && blue - green > 40 -> "blue"
                    else -> "none"
                })
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun shows(scenario: ActivityScenario<DebugCollectionActivity>, wanted: String) {
        try { ui.waitUntil(10_000) { colour(scenario) == wanted } }
        catch (failure: Throwable) { android.util.Log.e("CartridgeGameCube", "Never showed $wanted: $seen"); throw failure }
    }
    private fun button(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.waitForIdle()
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(200)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(200)
    }
    /** The left stick as a pad reports it: AXIS_X on a joystick source. */
    private fun stick(scenario: ActivityScenario<DebugCollectionActivity>, x: Float) {
        scenario.onActivity { activity ->
            val coordinates = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_X, x) }
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
            try { activity.dispatchGenericMotionEvent(event) } finally { event.recycle() }
        }
    }
    private fun variables(scenario: ActivityScenario<DebugCollectionActivity>): Map<String?, String?> {
        val result = CompletableFuture<Map<String?, String?>>()
        scenario.onActivity { it.engine.surface.value!!.let { view -> view.queueEvent { result.complete(view.getVariables().associate { variable -> variable.key to variable.value }) } } }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    private fun resume(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    /** What the screen's sleep does to the Activity: stopped, its surface taken away, then brought back. */
    private fun away(scenario: ActivityScenario<DebugCollectionActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(1500); scenario.moveToState(Lifecycle.State.RESUMED)
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused && it.engine.surface.value != null }
    }

    @Test fun anOriginalProgramDrawsThroughOpenGlAndReadsButtonsAndTheStick() {
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.GAMECUBE)
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        // A fresh identity per run, appended after the program where nothing reads it.
        File(fixtures, "probe.dol").writeBytes(File(context.filesDir, "cartridge-probe.dol").readBytes() + UUID.randomUUID().toString().toByteArray())
        var closing = 0L
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.dol"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.GAMECUBE }
                var id = ""
                scenario.onActivity { activity ->
                    id = activity.coordinator.state.value.selectedGame!!.id
                    // Nothing of the owner's is needed to start.
                    assertTrue(activity.shell.firmwareReady(Core.Dolphin)); assertEquals(Media.entries.size * CollectionLayout.POOL, activity.collectionSurface!!.meshInstanceCount)
                }
                assertEquals("game.dol", GameFiles.resolve(File(context.filesDir, "roms"), id, GameSystem.GAMECUBE).name)
                // The program paints the memory the video chip reads, as homebrew does, where a game draws through the
                // graphics processor. This game's own setting shows that picture; the console's default stays as it is.
                val exact = GameOptions.all.single { it.id == "gamecube.exactVideo" }
                scenario.onActivity { it.shell.setGameOption(it.coordinator.state.value.selectedGame!!, exact, "disabled") }
                ui.tool("insert").performClick()
                try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                catch (failure: Throwable) {
                    scenario.onActivity { android.util.Log.e("CartridgeGameCube", "Did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
                    throw failure
                }
                shows(scenario, "red")
                button(scenario); shows(scenario, "green")
                // The stick is the game's own: held right the program paints blue, and released it returns.
                stick(scenario, 1f); shows(scenario, "blue")
                stick(scenario, 0f); shows(scenario, "green")
                pause(scenario)
                val saves = SaveStore(File(context.filesDir, "saves"))
                val frames = CompletableFuture<Long>()
                scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
                    frames.complete(SaveBridge.frameCount(File(context.applicationInfo.nativeLibraryDir, GameSystem.GAMECUBE.coreFile).path)) } }
                assertTrue(frames.get(3, TimeUnit.SECONDS) >= 2)
                // The variables the save design fixes are in force, and the option offered for the console is one this build declares.
                val running = variables(scenario)
                assertEquals("disabled", running["dolphin_osd_enabled"]); assertEquals("enabled", running["dolphin_skip_gc_bios"]); assertEquals("Hardware", running["dolphin_renderer"])
                assertEquals("disabled", running[exact.variable])
                val sharper = GameOptions.all.single { it.id == "gamecube.sharp3d" }
                assertEquals(sharper.default, running[sharper.variable])
                assertNull(saves.read(id, "sram")) // Its memory card is a folder of its own, not battery memory.
                // No state is offered, pausing writes none and warns of nothing, and the game carries on where it was.
                ui.onNodeWithTag("menu-1").assertDoesNotExist(); ui.onNodeWithTag("menu-2").assertDoesNotExist()
                scenario.onActivity { assertNull(it.engine.status.value.saveWarning) }
                assertNull(saves.read(id, "state")); assertTrue(GameOptions.forSystem(GameSystem.GAMECUBE).none { it.id == "startFromState" })
                android.util.Log.i("CartridgeGameCube", "Program ran: red, green on a button, blue on the stick")
                resume(scenario); shows(scenario, "green")
                // With no state to come back from, leaving the app must not end the game. It is found paused and still
                // loaded: green is what the button left it showing, and a program started again would be red.
                away(scenario); resume(scenario); shows(scenario, "green")
                button(scenario); shows(scenario, "red")
                pause(scenario)
                scenario.onActivity { assertNotNull(it.engine.frozenFrame.value); assertNull(it.engine.status.value.saveWarning) }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                val own = File(context.filesDir, "saves/$id.files")
                assertTrue(File(own, "User").isDirectory)
                // The engine's own data was unpacked into the system folder this activity gives its cores.
                assertTrue(File(context.filesDir, "m1-test-system/dolphin-emu/Sys/GC/dsp_coef.bin").isFile)
                android.util.Log.i("CartridgeGameCube", "Core's own files for this game: ${own.list()!!.sorted()}")
                // The library's own renderer and a second start both survive the hand-over from an OpenGL core.
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                shows(scenario, "red"); pause(scenario); ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                // The app closed with the game still loaded, as when Android takes it from the recent apps.
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                shows(scenario, "red")
                closing = System.nanoTime()
            }
            // The engine has to be unloaded on the thread it ran on, which the interface takes away as it closes.
            // Unloaded from any other it ends the whole process, ten seconds later; this line is only reached, and
            // soon, when it was unloaded there.
            val took = (System.nanoTime() - closing) / 1_000_000
            assertTrue("Closing took $took ms", took < 8_000)
            android.util.Log.i("CartridgeGameCube", "Closed with the game loaded in $took ms")
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name) }
    }
}
