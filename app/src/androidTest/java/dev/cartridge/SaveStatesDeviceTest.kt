package dev.cartridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.environment.ConsolePreferences
import dev.cartridge.environment.GameOptions
import dev.cartridge.input.ButtonCombo
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.session.CollectionScreen
import dev.cartridge.shell.Destination
import dev.cartridge.storage.SlotKind
import dev.cartridge.storage.StateSlots
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
 * The quick save and its combos, the list of saves and their thumbnails (docs/SAVE_RELIABILITY), on the original
 * Game Boy probe: A flips its picture, so every state is told apart by what it shows when it is loaded.
 */
class SaveStatesDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    /** A row of a scrolling list: a page or a sheet. */
    private fun row(tag: String) { ui.onNodeWithTag(tag).performScrollTo().performClick(); ui.waitForIdle() }
    /** A row of the pause menu or a dialog, which do not scroll. */
    private fun tap(tag: String) { ui.onNodeWithTag(tag).performClick(); ui.waitForIdle() }
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
    private fun chord(scenario: ActivityScenario<DebugCollectionActivity>, vararg codes: Int, release: Boolean = true) {
        codes.forEach { code -> scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(40) }
        if (release) { Thread.sleep(80); codes.reversed().forEach { code -> scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) } } }
        ui.waitForIdle()
    }

    @Test fun quickButtonsWorkDuringPlayAndTheListKeepsSavesWithTheirPictures() {
        val name = "m1-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("$name-presentation", 0)
        val rom = File(context.filesDir, "cartridge-probe.gb").readBytes().also { UUID.randomUUID().toString().take(8).toByteArray().copyInto(it, 0x134) }
        val id = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
        File(context.filesDir, "roms/$id.gb").apply { parentFile!!.mkdirs(); writeBytes(rom) }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking { RoomCollectionRepository(context, database).apply { initialize(); add(LibraryGame(id, "Original save probe", "content://fixture", system = GameSystem.GB)) } }
        database.close()
        val slots = StateSlots(File(context.filesDir, "saves"))
        fun listed() = slots.list(id, GameSystem.GB)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                fun running() = await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                fun paused() = await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                fun start() { ui.tool("insert").performClick(); running() }
                fun shown(): Int { var seen = 0; ui.waitUntil(8000) { seen = picture(scenario); seen != 0 }; return seen }
                fun shows(expected: Int) = ui.waitUntil(8000) { picture(scenario) == expected }
                fun button() {
                    ui.waitForIdle()
                    scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
                    scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
                }
                fun pause() { ui.onNodeWithTag("pause").performClick(); paused() }
                fun said(message: String) = await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message == message }
                fun leave() { ui.onNodeWithTag("menu-3").performClick()
                    await(scenario) { val now = it.coordinator.state.value; !now.busy && now.screen == CollectionScreen.Library && now.rendererReady && !now.curtain } }
                fun saved() = listed().filter { it.kind == SlotKind.Saved }
                fun thumbnailHeight(file: File?) = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(checkNotNull(file).path, this) }.outHeight

                start(); val first = shown()
                button(); var second = 0; ui.waitUntil(8000) { second = picture(scenario); second != 0 && second != first }

                // L3 + Y during play: a quick save with its picture, and the game never stops.
                chord(scenario, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_Y)
                said("Quick saved"); running()
                assertEquals(listOf(SlotKind.Quick), listed().map { it.kind })
                assertEquals(180, thumbnailHeight(listed().single().thumbnail))
                // Back to the first picture, then L3 + X brings the quick save's back.
                button(); shows(first)
                chord(scenario, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_X)
                said("Quick loaded"); running(); shows(second)
                // A combo held down fires once, and the game hears nothing until it is let go.
                chord(scenario, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_Y, release = false)
                said("Quick saved"); running()
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
                Thread.sleep(300); assertEquals(second, picture(scenario))
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
                // The second quick save kept the first as the one it replaced.
                assertEquals(listOf(SlotKind.Quick, SlotKind.Previous), listed().map { it.kind })

                // The list, from the pause menu.
                pause()
                tap("menu-5")
                scenario.onActivity { assertEquals(Destination.SaveStates, it.shell.top) }
                await(scenario) { it.shell.stateList?.second?.size == 2 }
                ui.onNodeWithTag("slot-quick").assertIsDisplayed(); ui.onNodeWithTag("slot-previous").assertIsDisplayed()
                row("slots-new"); await(scenario) { !it.coordinator.state.value.busy && it.shell.stateList?.second?.size == 3 }
                val kept = saved().single()
                assertEquals(180, thumbnailHeight(kept.thumbnail))
                // A new save leaves the menu open, on the save just made.
                scenario.onActivity { assertEquals(Destination.SaveStates, it.shell.top); assertEquals(SessionState.Paused, it.engine.status.value.state) }

                // Loading one asks first, then returns to the game, as it does until the owner says otherwise.
                scenario.onActivity { it.shell.home() }
                ui.onNodeWithTag("menu-0").performClick(); running()
                button(); shows(first)
                pause(); tap("menu-5"); await(scenario) { it.shell.stateList?.second?.size == 3 }
                row("slot-${kept.name}"); row("slot-load")
                scenario.onActivity { assertTrue(it.shell.top is Destination.ConfirmSlot) }
                tap("slot-confirm"); said("Loaded"); running(); shows(second)

                // Overwrite takes the moment paused on; the list still holds one save.
                button(); shows(first)
                pause(); tap("menu-5"); await(scenario) { it.shell.stateList?.second?.size == 3 }
                row("slot-${kept.name}"); row("slot-overwrite"); tap("slot-confirm"); said("Save overwritten")
                scenario.onActivity { assertEquals(Destination.SaveStates, it.shell.top) }
                val overwritten = saved().single()
                assertNotEquals(kept.name, overwritten.name)
                assertFalse(File(context.filesDir, "saves/$id.slots/${kept.name}.state").exists())
                // Cancelling a delete keeps it; confirming removes the state, its record and its picture.
                row("slot-${overwritten.name}"); row("slot-delete"); tap("slot-cancel")
                assertEquals(1, saved().size)
                scenario.onActivity { it.shell.home(); it.shell.push(Destination.SaveStates) }
                await(scenario) { it.shell.stateList?.second?.size == 3 }
                row("slot-${overwritten.name}"); row("slot-delete"); tap("slot-confirm"); said("Save deleted")
                assertTrue(saved().isEmpty())
                assertTrue(File(context.filesDir, "saves/$id.slots").listFiles().orEmpty().none { it.name.startsWith(overwritten.name) })
                // The quick save and the one it replaced cannot be overwritten or deleted.
                row("slot-quick"); ui.onNodeWithTag("slot-overwrite").assertDoesNotExist(); ui.onNodeWithTag("slot-delete").assertDoesNotExist()
                scenario.onActivity { it.shell.home() }

                // Stay in menu: Quick Load from the pause menu leaves it open.
                tap("menu-4"); row("option-controls"); row("controls-after")
                assertFalse(ConsolePreferences(preferences).read().returnToGame)
                scenario.onActivity { it.shell.home() }
                tap("menu-2"); said("Quick loaded"); paused()

                // A new combo is whatever is held until the first button is let go; one that would take a single
                // button is refused and listening goes on; five quiet seconds keep what was there.
                tap("menu-4"); row("option-controls"); row("controls-quick-save")
                scenario.onActivity { assertTrue(it.shell.top is Destination.RecordCombo) }
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)) }
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)) }
                ui.waitForIdle()
                assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R1), ConsolePreferences(preferences).read().quickSave)
                scenario.onActivity { assertFalse(it.shell.top is Destination.RecordCombo) }
                row("controls-quick-load")
                chord(scenario, KeyEvent.KEYCODE_BUTTON_A)
                scenario.onActivity { assertNotNull(it.shell.recorder?.problem); assertTrue(it.shell.top is Destination.RecordCombo) }
                await(scenario) { it.shell.top !is Destination.RecordCombo }
                assertEquals(ButtonCombo.QUICK_LOAD, ConsolePreferences(preferences).read().quickLoad)
                // The new combo is the one a game answers to.
                scenario.onActivity { it.shell.home() }
                ui.onNodeWithTag("menu-0").performClick(); running()
                chord(scenario, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R1); said("Quick saved")
                pause(); tap("menu-4"); row("option-controls"); row("controls-quick-reset")
                assertEquals(ButtonCombo.QUICK_SAVE, ConsolePreferences(preferences).read().quickSave)
                scenario.onActivity { it.shell.home() }

                // Start from save state, from the newest save: a list save made after the quick save is the one used.
                ui.onNodeWithTag("menu-0").performClick(); running()
                var quickPicture = 0; ui.waitUntil(8000) { quickPicture = picture(scenario); quickPicture != 0 }
                chord(scenario, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_Y); said("Quick saved")
                button(); var newest = 0; ui.waitUntil(8000) { newest = picture(scenario); newest != 0 && newest != quickPicture }
                pause(); tap("menu-5"); row("slots-new"); await(scenario) { !it.coordinator.state.value.busy && saved().size == 1 }
                scenario.onActivity { it.shell.home() }
                leave()
                scenario.onActivity {
                    it.shell.setConsoleOption(GameOptions.all.single { option -> option.id == "startFromState" }, "enabled")
                    it.shell.changeStartLatest(true)
                }
                start(); shows(newest); pause(); leave()
                scenario.onActivity { it.shell.changeStartLatest(false) }
                start(); shows(quickPicture); pause(); leave()
            }
        } finally {
            DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); preferences.edit().clear().commit()
            File(context.filesDir, "saves").listFiles { _, file -> file.startsWith("$id.") }?.forEach { it.deleteRecursively() }
            File(context.filesDir, "roms/$id.gb").delete()
        }
    }
}
