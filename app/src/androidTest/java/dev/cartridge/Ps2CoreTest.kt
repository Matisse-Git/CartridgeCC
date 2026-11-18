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
import dev.cartridge.storage.FirmwareStore
import dev.cartridge.storage.GameFiles
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The PlayStation 2 core on the device. It runs nothing without the console's own BIOS, which only the user
 * has, so the first test is the console without one: it explains itself instead of failing, and takes a BIOS
 * by what the file's own table says. The other two need the user's BIOS on the device (see [userFirmware])
 * and are skipped where it is not: the original program from tools/probe/generate_ps2_probe.py is started on
 * it, alone and from a disc image, and its picture arrives through the host's OpenGL ES context. The program
 * reads no pad, so the pad is not part of this test; no state is offered for this console.
 */
class Ps2CoreTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val system get() = File(context.filesDir, "m1-test-system")
    private val bios get() = File(system, Core.Armsx2.firmware.single().file)
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    @Volatile private var seen = ""
    /** Which of the program's colours the middle of the presented picture carries. */
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
                result.complete(when {
                    status != PixelCopy.SUCCESS -> "none"
                    red > 180 && red - green > 40 && red - blue > 40 -> "red"
                    else -> "none"
                })
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun shows(scenario: ActivityScenario<DebugCollectionActivity>, wanted: String) {
        try { ui.waitUntil(20_000) { colour(scenario) == wanted } }
        catch (failure: Throwable) { android.util.Log.e("CartridgePs2", "Never showed $wanted: $seen"); throw failure }
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
    private fun leave(scenario: ActivityScenario<DebugCollectionActivity>) {
        pause(scenario); ui.onNodeWithTag("menu-3").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    private fun start(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.tool("insert").performClick()
        try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
        catch (failure: Throwable) {
            scenario.onActivity { android.util.Log.e("CartridgePs2", "Did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
            throw failure
        }
    }
    /** What the screen's sleep does to the Activity: stopped, its surface taken away, then brought back. */
    private fun away(scenario: ActivityScenario<DebugCollectionActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(1500); scenario.moveToState(Lifecycle.State.RESUMED)
        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused && it.engine.surface.value != null }
    }
    private fun frames(scenario: ActivityScenario<DebugCollectionActivity>): Long {
        val frames = CompletableFuture<Long>()
        scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
            frames.complete(SaveBridge.frameCount(File(context.applicationInfo.nativeLibraryDir, GameSystem.PS2.coreFile).path)) } }
        return frames.get(3, TimeUnit.SECONDS)
    }
    /** A fresh disc in a folder named for the console: an identity of its own in a sector nothing reads. */
    private fun disc() {
        val image = File(context.filesDir, "cartridge-probe-ps2.iso").readBytes()
        UUID.randomUUID().toString().toByteArray().copyInto(image, 40 * 2048)
        File(context.filesDir, "m1-tree").apply { mkdirs() }.let { File(it, "ps2.iso").writeBytes(image) }
    }
    private fun addFolder() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://$FIXTURE_TREE/tree/ps2"))))
            ui.tool("choose-folder").performClick()
        } finally { Intents.release() }
    }

    @Test fun aDiscWithoutTheConsolesBiosIsListedAndExplainsWhatItNeedsAndABiosIsTakenByItsOwnFileTable() {
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.PS2)
        bios.delete(); disc()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                addFolder()
                // The import result names what the new console still needs and offers the one thing that supplies it.
                ui.waitUntil(30_000) { ui.shown("import-done") }
                assertTrue(ui.shown("import-bios-armsx2"))
                await(scenario) { !it.coordinator.state.value.busy }
                ui.onNodeWithTag("import-done").performClick()
                // 128 KiB is no DVD, and its first bytes are a PlayStation disc's as much as anything's: the folder decides.
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.PS2 }
                scenario.onActivity { activity ->
                    assertEquals(1, activity.coordinator.state.value.allGames.size)
                    assertEquals("game.iso", GameFiles.resolve(File(context.filesDir, "roms"), activity.coordinator.state.value.selectedGame!!.id, GameSystem.PS2).name)
                    assertFalse(activity.shell.firmwareReady(Core.Armsx2)); assertTrue(activity.shell.firmwareReady(Core.Dolphin))
                }
                // Starting it does not take the disc off the shelf and fail: the console says what it needs.
                ui.tool("insert").performClick()
                ui.waitUntil(5_000) { ui.shown("dialog-setup") }
                assertTrue(ui.shown("setup-ps2-bios") && ui.shown("setup-close"))
                scenario.onActivity { assertEquals(SessionState.Idle, it.engine.status.value.state); assertEquals(CollectionScreen.Library, it.coordinator.state.value.screen)
                    assertNull(it.coordinator.state.value.launchFailure) }
                ui.onNodeWithTag("setup-close").performClick(); ui.waitUntil(5_000) { !ui.shown("dialog-setup") }
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-systems").performClick()
                ui.onNodeWithTag("settings-bios-ps2-bios").performScrollTo().assertIsDisplayed()
                // A file of the right size that is not one is refused in words, and nothing is written.
                val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
                File(fixtures, "invalid.png").writeBytes(ByteArray(4 * 1024 * 1024))
                val refusal = runCatching { FirmwareStore(system).import(context.contentResolver, Uri.parse("content://$FIXTURE/invalid.png"), Core.Armsx2) }.exceptionOrNull()
                assertTrue(refusal is IllegalArgumentException && refusal.message!!.contains("PlayStation 2 BIOS")); assertFalse(bios.exists())
                // An original stand-in for the shape of a dump, not a BIOS: a table that begins with RESET, sixteen bytes
                // a row, and the fourteen characters of ROMVER where the table says that file is. Whatever its region, it
                // is taken as this console's and put where the core looks, in a folder of the core's own.
                val shaped = ByteArray(4 * 1024 * 1024).also { bytes ->
                    fun row(index: Int, name: String, length: Int) {
                        name.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x2000 + index * 16)
                        for (n in 0..3) bytes[0x2000 + index * 16 + 12 + n] = (length shr (8 * n)).toByte()
                    }
                    row(0, "RESET", 0x2740); row(1, "ROMDIR", 0x45); row(2, "ROMVER", 16)
                    "0160JC20020207".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x2740 + 0x50)
                }
                File(fixtures, "stand-in-bios.bin").writeBytes(shaped)
                val (core, firmware) = FirmwareStore(system).import(context.contentResolver, Uri.parse("content://$FIXTURE/stand-in-bios.bin"))
                assertEquals(Core.Armsx2, core); assertEquals("pcsx2/bios/ps2-bios.bin", firmware.file); assertArrayEquals(shaped, bios.readBytes())
                File(fixtures, "stand-in-bios.bin").delete(); File(fixtures, "invalid.png").delete()
            }
        } finally {
            // The stand-in is no BIOS and must not be left for a game to be started on.
            bios.delete(); userFirmware(context, Core.Armsx2)
            DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name)
        }
    }

    @Test fun onTheUsersBiosADiscInAFolderNamedForTheConsoleStartsWhatItsSystemFileNamesAndTheAppCanBeClosedOnIt() {
        assumeTrue("The user's PlayStation 2 BIOS is not in files/m1-owner-firmware on this device", userFirmware(context, Core.Armsx2))
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.PS2)
        disc()
        var closing = 0L
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                addFolder()
                ui.waitUntil(30_000) { ui.shown("import-done") }
                assertFalse(ui.shown("import-bios-armsx2"))
                await(scenario) { !it.coordinator.state.value.busy }
                ui.onNodeWithTag("import-done").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.PS2 }
                scenario.onActivity { assertTrue(it.shell.firmwareReady(Core.Armsx2)) }
                start(scenario); shows(scenario, "red")
                android.util.Log.i("CartridgePs2", "Disc ran: SYSTEM.CNF read, its program started, red")
                leave(scenario)
                // The app closed with the game still loaded, as when Android takes it from the recent apps.
                start(scenario); shows(scenario, "red")
                closing = System.nanoTime()
            }
            // The engine draws on a thread of its own and has to be let go in its own order, on the thread it ran
            // on, which the interface takes away as it closes. Let go any other way it ended the whole process, ten
            // seconds later; this line is only reached, and soon, when it was let go properly.
            val took = (System.nanoTime() - closing) / 1_000_000
            assertTrue("Closing took $took ms", took < 8_000)
            // And it is really gone: the same game starts again in a new Activity of the same process.
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.coordinator.state.value.selectedGame?.system == GameSystem.PS2 }
                start(scenario); shows(scenario, "red"); leave(scenario)
            }
            android.util.Log.i("CartridgePs2", "Closed with the game loaded in $took ms, and started it again")
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name) }
    }

    @Test fun onTheUsersBiosAnOriginalProgramDrawsThroughOpenGlOffersNoStateAndIsStillLoadedAfterTheAppIsLeft() {
        assumeTrue("The user's PlayStation 2 BIOS is not in files/m1-owner-firmware on this device", userFirmware(context, Core.Armsx2))
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.PS2)
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        // A fresh identity per run, appended after the program where nothing reads it.
        File(fixtures, "probe-ps2.elf").writeBytes(File(context.filesDir, "cartridge-probe-ps2.elf").readBytes() + UUID.randomUUID().toString().toByteArray())
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://$FIXTURE/probe-ps2.elf"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.PS2 }
                var id = ""
                scenario.onActivity { activity ->
                    id = activity.coordinator.state.value.selectedGame!!.id
                    assertTrue(activity.shell.firmwareReady(Core.Armsx2)); assertEquals(Media.entries.size * CollectionLayout.POOL, activity.collectionSurface!!.meshInstanceCount)
                }
                assertEquals("game.elf", GameFiles.resolve(File(context.filesDir, "roms"), id, GameSystem.PS2).name)
                start(scenario); shows(scenario, "red")
                pause(scenario)
                val saves = SaveStore(File(context.filesDir, "saves"))
                assertTrue(frames(scenario) >= 2)
                // The variables the save design fixes are in force, and the option offered for the console is one this build declares.
                val running = variables(scenario)
                for ((key, value) in Core.Armsx2.fixed) assertEquals(key, value, running[key])
                val sharper = GameOptions.all.single { it.id == "ps2.sharp3d" }
                assertEquals(sharper.default, running[sharper.variable])
                assertNull(saves.read(id, "sram")) // Its memory cards are files of its own, not battery memory.
                // No state is offered, pausing writes none and warns of nothing, and the game carries on where it was.
                ui.onNodeWithTag("menu-1").assertDoesNotExist(); ui.onNodeWithTag("menu-2").assertDoesNotExist()
                scenario.onActivity { assertNull(it.engine.status.value.saveWarning) }
                assertNull(saves.read(id, "state")); assertTrue(GameOptions.forSystem(GameSystem.PS2).none { it.id == "startFromState" })
                android.util.Log.i("CartridgePs2", "Program ran: red")
                resume(scenario); shows(scenario, "red")
                // With no state to come back from, leaving the app must not end the game. It is found paused and still
                // loaded: the core's count of pictures carries on from where it was instead of starting again.
                Thread.sleep(3000)
                val before = frames(scenario)
                away(scenario); resume(scenario); shows(scenario, "red")
                val after = frames(scenario)
                assertTrue("$before pictures before leaving, $after after", before > 120 && after > before)
                pause(scenario)
                scenario.onActivity { assertNotNull(it.engine.frozenFrame.value); assertNull(it.engine.status.value.saveWarning) }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                // One console's two memory cards, kept by the core beside its BIOS and shared by every game; nothing of
                // this game's own was written beside other games' saves.
                val cards = File(system, "pcsx2/memcards")
                assertTrue(File(cards, "Mcd001.ps2").length() > 0 && File(cards, "Mcd002.ps2").length() > 0)
                assertFalse(File(context.filesDir, "saves/$id.files").exists())
                android.util.Log.i("CartridgePs2", "The core's own files: ${File(system, "pcsx2").list()!!.sorted()}")
                // The library's own renderer and a second start both survive the hand-over from a core that draws on a
                // thread of its own.
                start(scenario); shows(scenario, "red"); leave(scenario)
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name) }
    }
}
