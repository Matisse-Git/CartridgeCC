package dev.cartridge

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.LaunchTransition
import dev.cartridge.domain.SessionState
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.library.Shelf
import dev.cartridge.session.CollectionScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/**
 * Starting and leaving a game behind the closed screen, the setting that chooses how it closes, turning the
 * focused object with the right stick, and where a shelf opens. Original probe games only.
 */
class LaunchExperienceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, millis: Long = 30_000, check: (DebugCollectionActivity) -> Boolean) =
        ui.waitUntil(millis) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, vararg codes: Int) {
        scenario.onActivity { activity -> codes.forEach { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, it)) } }; Thread.sleep(80)
        scenario.onActivity { activity -> codes.forEach { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, it)) } }; ui.waitForIdle()
    }
    /** The right stick as the Retroid Pocket 5 reports it: AXIS_Z and AXIS_RZ on a joystick source. */
    private fun rightStick(scenario: ActivityScenario<DebugCollectionActivity>, x: Float, y: Float) = scenario.onActivity { activity ->
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
        val coordinates = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_Z, x); setAxisValue(MotionEvent.AXIS_RZ, y) }
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try { activity.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }
    private fun disc(index: Int) = LibraryGame((index + 0xd0).toString(16).padStart(64, '0'), "Original disc $index", "content://fixture", system = GameSystem.PS1)
    /** Three playable probe cartridges and three discs that are only ever looked at. */
    private fun library(name: String): List<String> {
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        val ids = mutableListOf<String>()
        runBlocking {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            repeat(3) { index ->
                val rom = probe + byteArrayOf((index + 90).toByte()); val id = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
                ids += id; File(context.filesDir, "roms/$id.gba").apply { parentFile!!.mkdirs(); writeBytes(rom) }
                repository.add(LibraryGame(id, "Original launch $index", "content://fixture"))
            }
            repeat(3) { repository.add(disc(it)) }
            repository.select(2)
        }
        database.close()
        return ids
    }
    private fun withLibrary(test: (ActivityScenario<DebugCollectionActivity>, List<String>) -> Unit) {
        val name = "m1-test-${UUID.randomUUID()}"
        val ids = library(name)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                test(scenario, ids)
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }

    @Test fun gameStartIsChosenInSettingsAndTheScreenStaysClosedUntilTheGameShowsAPicture() = withLibrary { scenario, ids ->
        scenario.onActivity { assertEquals(LaunchTransition.Fade, it.shell.transition); assertFalse(it.coordinator.state.value.curtain) }
        ui.tool("transition-setting").performClick()
        scenario.onActivity { assertEquals(LaunchTransition.Iris, it.shell.transition); assertEquals("Iris", it.shell.preferences.getString("launchTransition", null)) }
        ui.home()
        scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
        scenario.onActivity { assertEquals(LaunchTransition.Iris, it.shell.transition); assertEquals(ids[2], it.coordinator.state.value.selectedGame!!.id) }

        // Every state the launch passes through, with what the emulator was doing at that moment.
        class Moment(val screen: CollectionScreen, val curtain: Boolean, val ready: Boolean, val session: SessionState)
        val moments = Collections.synchronizedList(mutableListOf<Moment>())
        scenario.onActivity { activity -> activity.lifecycleScope.launch {
            activity.coordinator.state.collect { moments.add(Moment(it.screen, it.curtain, it.rendererReady, activity.engine.status.value.state)) } } }
        ui.tool("insert").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running && !it.coordinator.state.value.curtain }
        val start = moments.toList()
        assertTrue("The screen closes while the library stage is still whole", start.any { it.screen == CollectionScreen.Inserting && it.curtain })
        assertTrue("Nothing of the hand-off or of a starting core is shown",
            start.none { it.screen in setOf(CollectionScreen.Handoff, CollectionScreen.Emulator) && !it.curtain && it.session != SessionState.Running })
        assertTrue("It is still closed when the emulator's surface takes the stage", start.any { it.screen == CollectionScreen.Emulator && it.curtain })
        assertEquals(CollectionScreen.Emulator, start.last().screen); assertFalse(start.last().curtain)

        // The pause menu: a row that cannot be used still shows where the focus is.
        key(scenario, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
        ui.onNodeWithTag("menu-0").assertIsSelected()
        key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN)
        ui.onNodeWithTag("menu-2").assertIsSelected().assertIsNotEnabled()

        moments.clear()
        ui.onNodeWithTag("menu-3").performClick()
        await(scenario) { val state = it.coordinator.state.value; !state.busy && state.screen == CollectionScreen.Library && state.rendererReady && !state.curtain }
        val leave = moments.toList()
        assertTrue("The screen closes over the game before it is taken down", leave.any { it.screen == CollectionScreen.Emulator && it.curtain })
        assertTrue("The rebuilt library is never shown before it has drawn",
            leave.none { it.screen in setOf(CollectionScreen.Ejecting, CollectionScreen.Library) && !it.curtain && !it.ready })
        scenario.onActivity { assertEquals(ids[2], it.coordinator.state.value.selectedGame!!.id) }
    }

    @Test fun rightStickTurnsTheFocusedObjectAndItComesHomeOnItsOwn() = withLibrary { scenario, _ ->
        fun angles(): Pair<Float, Float> { var value = 0f to 0f; scenario.onActivity { value = it.collectionSurface!!.spinAngles }; return value }
        assertEquals(0f to 0f, angles())
        rightStick(scenario, 1f, 0f)
        await(scenario, 5_000) { it.collectionSurface!!.spinAngles.first > 60f }
        rightStick(scenario, 0f, -1f)
        await(scenario, 5_000) { it.collectionSurface!!.spinAngles.second < -20f }
        rightStick(scenario, 0f, 0f)
        val held = angles(); assertTrue(held.first > 60f)
        await(scenario, 6_000) { it.collectionSurface!!.spinAngles == 0f to 0f }

        // Moving the focus returns the object at once rather than carrying the turn to its neighbour.
        rightStick(scenario, 1f, 0f); await(scenario, 5_000) { it.collectionSurface!!.spinAngles.first > 30f }
        rightStick(scenario, 0f, 0f)
        key(scenario, KeyEvent.KEYCODE_DPAD_LEFT)
        await(scenario, 1_000) { it.collectionSurface!!.spinAngles == 0f to 0f }

        // A menu over the shelf is not about the object; its details are.
        key(scenario, KeyEvent.KEYCODE_BUTTON_START); ui.onNodeWithTag("system-menu-panel").assertIsDisplayed()
        rightStick(scenario, 1f, 0f); Thread.sleep(400); assertEquals(0f to 0f, angles())
        rightStick(scenario, 0f, 0f); key(scenario, KeyEvent.KEYCODE_BUTTON_B)
        key(scenario, KeyEvent.KEYCODE_BUTTON_X); ui.onNodeWithTag("details-panel").assertIsDisplayed()
        rightStick(scenario, -1f, 0f)
        await(scenario, 5_000) { it.collectionSurface!!.spinAngles.first < -30f }
        rightStick(scenario, 0f, 0f)
    }

    @Test fun turningToAnotherConsoleAlwaysStartsAtItsFirstGame() = withLibrary { scenario, ids ->
        scenario.onActivity { assertEquals(Shelf.Console(GameSystem.GBA), it.coordinator.state.value.shelf); assertEquals(2, it.coordinator.state.value.selection.index) }
        key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
        scenario.onActivity { assertEquals(Shelf.Console(GameSystem.PS1), it.coordinator.state.value.shelf); assertEquals(disc(0).id, it.coordinator.state.value.selectedGame!!.id) }
        key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT); key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity { assertEquals(disc(2).id, it.coordinator.state.value.selectedGame!!.id) }
        key(scenario, KeyEvent.KEYCODE_BUTTON_L1)
        // Not the cartridge this shelf showed last.
        scenario.onActivity { assertEquals(Shelf.Console(GameSystem.GBA), it.coordinator.state.value.shelf); assertEquals(ids[0], it.coordinator.state.value.selectedGame!!.id) }
        key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
        scenario.onActivity { assertEquals(disc(0).id, it.coordinator.state.value.selectedGame!!.id) }
        key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT); key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
        // The unified shelf holds that disc, and still opens on its first game.
        scenario.onActivity { assertEquals(Shelf.All, it.coordinator.state.value.shelf); assertEquals(0, it.coordinator.state.value.selection.index); assertEquals(ids[0], it.coordinator.state.value.selectedGame!!.id) }
        ui.onNodeWithTag("position").assertTextEquals("1 / 6")
    }

    @Test fun frameRateIsShownOnlyWhenAskedFor() = withLibrary { scenario, _ ->
        ui.onNodeWithTag("frame-rate").assertDoesNotExist()
        ui.tool("framerate-setting").performClick()
        scenario.onActivity { assertTrue(it.shell.showFrameRate); assertTrue(it.shell.preferences.getBoolean("showFrameRate", false)) }
        ui.home()
        ui.waitUntil(5_000) { ui.shown("frame-rate") }
        // The stage's own count of frames handed to the display in a full second, on a 60 Hz panel.
        ui.waitUntil(5_000) { var rate = 0; scenario.onActivity { rate = it.shell.frameRate }; rate in 55..61 }
        ui.tool("framerate-setting").performClick(); ui.home()
        ui.onNodeWithTag("frame-rate").assertDoesNotExist()
    }
}
