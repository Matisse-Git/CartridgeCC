package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.swordfish.libretrodroid.ShaderConfig
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.environment.CoreFamily
import dev.cartridge.environment.OptionStore
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.SaveStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A visible setting must do something. This reads the options back from the running PCSX core and the
 * emulator view, so the PlayStation rows in Settings and Game options are proven against the pinned build.
 */
class GameOptionsDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun row(tag: String) { ui.onNodeWithTag(tag).performScrollTo().performClick(); ui.waitForIdle() }
    private fun variables(scenario: ActivityScenario<DebugCollectionActivity>): Map<String?, String?> {
        val result = CompletableFuture<Map<String?, String?>>()
        scenario.onActivity { it.engine.surface.value!!.let { view -> view.queueEvent { result.complete(view.getVariables().associate { variable -> variable.key to variable.value }) } } }
        return result.get(3, TimeUnit.SECONDS)
    }

    @Test fun playStationConsoleOptionsReachTheCoreAndAGameOverrideChangesTheLivePicture() {
        val name = "m1-test-${UUID.randomUUID()}"
        File(context.filesDir, "cartridge-probe.exe").copyTo(File(context.filesDir, "m1-fixtures/probe.exe").apply { parentFile!!.mkdirs() }, true)
        val preferences = context.getSharedPreferences("$name-presentation", 0)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                // Journey H: Settings › Gameplay names each console, and the PlayStation rows sit under PlayStation.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-gameplay").performClick()
                row("option-playstation.sharp3d"); row("option-playstation.dither")
                repeat(3) { row("option-playstation.effect") } // Standard → Sharp pixels → Handheld LCD → Television
                ui.onNodeWithTag("settings-help").assertIsDisplayed()
                val console = OptionStore(preferences).console(CoreFamily.PlayStation)
                assertEquals(mapOf("playstation.sharp3d" to "enabled", "playstation.dither" to "disabled", "playstation.effect" to "crt"), console)
                assertTrue(OptionStore(preferences).console(CoreFamily.Handheld).isEmpty()) // Another console's choices are untouched.
                ui.home()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.exe"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.PS1 }
                var id = ""; scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id }
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                val running = variables(scenario)
                assertEquals("enabled", running["pcsx_rearmed_neon_enhancement_enable"]); assertEquals("disabled", running["pcsx_rearmed_dithering"])
                // The save design's own variables cannot be displaced by a user option.
                assertEquals("libretro", running["pcsx_rearmed_memcard1"]); assertEquals("disabled", running["pcsx_rearmed_memcard2"]); assertEquals("auto", running["pcsx_rearmed_bios"])
                scenario.onActivity { assertEquals(ShaderConfig.CRT, it.engine.surface.value!!.shader) }

                ui.onNodeWithTag("pause").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                ui.onNodeWithTag("menu-4").performClick()
                // One step from "console setting" is this game's own Standard picture; it applies to the paused game at once.
                row("option-playstation.effect")
                await(scenario) { !it.coordinator.state.value.busy }
                assertEquals(mapOf("playstation.effect" to "standard"), OptionStore(preferences).game(id))
                assertEquals("crt", OptionStore(preferences).console(CoreFamily.PlayStation)["playstation.effect"])
                scenario.onActivity { assertEquals(ShaderConfig.Default, it.engine.surface.value!!.shader) }
                // Core options wait for the next start, and say so; the running core keeps what it started with.
                row("option-playstation.sharp3d")
                await(scenario) { !it.coordinator.state.value.busy }
                assertEquals("disabled", OptionStore(preferences).game(id)["playstation.sharp3d"])
                assertEquals("enabled", variables(scenario)["pcsx_rearmed_neon_enhancement_enable"])
                row("option-reset")
                await(scenario) { !it.coordinator.state.value.busy }
                assertTrue(OptionStore(preferences).game(id).isEmpty())
                scenario.onActivity { assertEquals(ShaderConfig.CRT, it.engine.surface.value!!.shader) }
                ui.onNodeWithTag("back").performClick(); ui.onNodeWithTag("menu-0").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                ui.onNodeWithTag("pause").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady && it.engine.status.value.state == SessionState.Idle }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); preferences.edit().clear().commit() }
    }

    /** A hash of the middle of the emulator's presented frame, or 0 while it is flat or unavailable. */
    private fun picture(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        val result = CompletableFuture<Int>()
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.engine.surface.value)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), bitmap, { status ->
                val colors = IntArray(64)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(colors, 0, 8, 0, 0, 8, 8)
                result.complete(if (status == PixelCopy.SUCCESS && colors.toSet().size > 1) colors.contentHashCode() else 0)
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }

    @Test fun startFromSaveStateContinuesAtBootKeepsTheBatterySaveAndNeverBlocksAStart() {
        val name = "m1-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("$name-presentation", 0)
        // The original Game Boy probe: A flips its picture and one byte of battery memory, and a start draws what that byte says.
        val rom = File(context.filesDir, "cartridge-probe.gb").readBytes().also { UUID.randomUUID().toString().take(8).toByteArray().copyInto(it, 0x134) }
        val id = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
        File(context.filesDir, "roms/$id.gb").apply { parentFile!!.mkdirs(); writeBytes(rom) }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking { RoomCollectionRepository(context, database).apply { initialize(); add(LibraryGame(id, "Original start probe", "content://fixture", system = GameSystem.GB)) } }
        database.close()
        val saves = SaveStore(File(context.filesDir, "saves"))
        val state = File(context.filesDir, "saves/$id.state")
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                fun start() { ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                fun shown(): Int { var seen = 0; ui.waitUntil(8000) { seen = picture(scenario); seen != 0 }; return seen }
                fun button() {
                    ui.waitForIdle()
                    scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
                    scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
                }
                fun pause() { ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused } }
                fun resume() { ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                fun leave() { ui.onNodeWithTag("menu-3").performClick()
                    await(scenario) { val now = it.coordinator.state.value; !now.busy && now.screen == CollectionScreen.Library && now.rendererReady && !now.curtain } }
                fun battery() = saves.read(id, "sram")!![1].toInt()

                // One session: flip the picture, save a state there, then flip it back and leave. The battery ends on 0.
                start(); val before = shown()
                button(); var changed = 0; ui.waitUntil(8000) { changed = picture(scenario); changed != 0 && changed != before }
                pause(); assertEquals(1, battery())
                ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                assertTrue(state.length() > 0)
                resume(); button(); ui.waitUntil(8000) { picture(scenario) == before }
                pause(); assertEquals(0, battery()); leave()

                // Off, as it is until asked for: a game starts from the beginning even though it has a state.
                assertTrue(OptionStore(preferences).console(CoreFamily.Handheld).isEmpty())
                start(); assertEquals(before, shown()); pause(); leave()

                // On, once, for every console.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-gameplay").performClick(); row("option-startFromState")
                ui.onNodeWithTag("settings-help").assertIsDisplayed()
                assertEquals("enabled", OptionStore(preferences).console(CoreFamily.Handheld)["startFromState"])
                assertEquals("enabled", OptionStore(preferences).console(CoreFamily.PlayStation)["startFromState"])
                ui.home()
                start()
                // The picture the state was saved on, with nothing said about it.
                ui.waitUntil(8000) { picture(scenario) == changed }
                scenario.onActivity { assertNull(it.engine.status.value.notice); assertNull(it.coordinator.state.value.message) }
                pause()
                // The state was made while the battery byte was 1. The cartridge keeps the 0 that was saved last.
                assertEquals(0, battery())

                // Only this game goes back to starting from the beginning; the console keeps its setting.
                ui.onNodeWithTag("menu-4").performClick(); row("option-startFromState")
                assertEquals(mapOf("startFromState" to "disabled"), OptionStore(preferences).game(id))
                assertEquals("enabled", OptionStore(preferences).console(CoreFamily.Handheld)["startFromState"])
                ui.onNodeWithTag("back").performClick(); leave()
                start(); assertEquals(before, shown()); pause()
                ui.onNodeWithTag("menu-4").performClick(); row("option-reset"); assertTrue(OptionStore(preferences).game(id).isEmpty())
                ui.onNodeWithTag("back").performClick(); leave()

                // A state the core cannot use: the game still starts, from the beginning, says so, and the file is left alone.
                val junk = ByteArray(4096) { (it * 31 + 7).toByte() }
                state.writeBytes(junk)
                start(); assertEquals(before, shown())
                ui.waitUntil(5000) { var said = false; scenario.onActivity { said = it.coordinator.state.value.message?.contains("save state could not be loaded") == true }; said }
                assertArrayEquals(junk, state.readBytes())
                pause(); assertEquals(0, battery()); leave()

                // No state at all: an ordinary start with nothing to report.
                assertTrue(state.delete())
                start(); assertEquals(before, shown())
                scenario.onActivity { assertNull(it.engine.status.value.notice); assertNull(it.coordinator.state.value.message) }
                pause(); leave()
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); preferences.edit().clear().commit(); state.delete() }
    }
}
