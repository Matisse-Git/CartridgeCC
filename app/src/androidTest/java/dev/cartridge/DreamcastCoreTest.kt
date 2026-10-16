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
 * The Dreamcast core on the device: the first one that draws through the host's OpenGL ES context and the first
 * whose pad has a stick. The original program from tools/probe/generate_dreamcast_probe.py runs on the core's
 * built-in BIOS replacement: its picture, a button, the analog stick, a state and the files it keeps per game.
 * A second test starts the same program from a GD-ROM image, the way a game is started.
 */
class DreamcastCoreTest {
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
        catch (failure: Throwable) { android.util.Log.e("CartridgeDreamcast", "Never showed $wanted: $seen"); throw failure }
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

    @Test fun aGdRomSheetAndItsTracksStartTheProgramTheDiscsOwnHeaderNames() {
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.DREAMCAST)
        val tree = File(context.filesDir, "m1-tree").apply { mkdirs() }
        for (file in listOf("disc.gdi", "track01.iso", "track02.raw")) File(context.filesDir, "cartridge-probe-dreamcast-$file").copyTo(File(tree, file), true)
        // A fresh disc: an identity of its own in a sector of the game track that nothing reads.
        val track = File(context.filesDir, "cartridge-probe-dreamcast-track03.iso").readBytes()
        UUID.randomUUID().toString().toByteArray().copyInto(track, 40 * 2048)
        File(tree, "track03.iso").writeBytes(track)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture-tree/tree/gdrom"))))
                    ui.tool("choose-folder").performClick()
                } finally { Intents.release() }
                ui.waitUntil(30_000) { ui.shown("import-done") }
                await(scenario) { !it.coordinator.state.value.busy }
                ui.onNodeWithTag("import-done").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.DREAMCAST }
                scenario.onActivity { activity ->
                    val game = activity.coordinator.state.value.selectedGame!!
                    // One game for the sheet and its three tracks, kept together under generated names.
                    assertEquals(1, activity.coordinator.state.value.allGames.size)
                    val launcher = GameFiles.resolve(File(context.filesDir, "roms"), game.id, game.system)
                    assertEquals("game.gdi", launcher.name)
                    assertEquals("3\n1 0 4 2048 track-00.bin 0\n2 450 0 2352 track-01.bin 0\n3 45000 4 2048 track-02.bin 0\n", launcher.readText())
                    for (index in 0..2) assertTrue(File(launcher.parentFile, "track-0$index.bin").isFile)
                }
                ui.tool("insert").performClick()
                try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                catch (failure: Throwable) {
                    scenario.onActivity { android.util.Log.e("CartridgeDreamcast", "Disc did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
                    throw failure
                }
                shows(scenario, "red"); button(scenario); shows(scenario, "green")
                android.util.Log.i("CartridgeDreamcast", "Disc ran: header read, 1ST_READ.BIN found and started, red, green on a button")
                pause(scenario); ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                var disc = ""
                scenario.onActivity { disc = it.coordinator.state.value.selectedGame!!.id }
                // Its memory unit is named for the disc's own product number, in the game's own folder.
                assertTrue(File(context.filesDir, "saves/$disc.files").list()!!.any { it.endsWith(".A1.bin") })
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name) }
    }

    @Test fun anOriginalProgramDrawsThroughOpenGlReadsButtonsAndTheStickAndRestoresItsState() {
        val name = "m1-test-${UUID.randomUUID()}"
        val made = TestGames(context, GameSystem.DREAMCAST)
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        // A fresh identity per run, appended after the program where nothing reads it.
        File(fixtures, "probe.elf").writeBytes(File(context.filesDir, "cartridge-probe.elf").readBytes() + UUID.randomUUID().toString().toByteArray())
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.elf"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.DREAMCAST }
                var id = ""
                scenario.onActivity { activity ->
                    id = activity.coordinator.state.value.selectedGame!!.id
                    // The built-in replacement is enough to start: no setup is asked for.
                    assertTrue(activity.shell.firmwareReady(Core.Flycast)); assertEquals(Media.entries.size * CollectionLayout.POOL, activity.collectionSurface!!.meshInstanceCount)
                }
                assertEquals("game.elf", GameFiles.resolve(File(context.filesDir, "roms"), id, GameSystem.DREAMCAST).name)
                ui.tool("insert").performClick()
                try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                catch (failure: Throwable) {
                    scenario.onActivity { android.util.Log.e("CartridgeDreamcast", "Did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
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
                    frames.complete(SaveBridge.frameCount(File(context.applicationInfo.nativeLibraryDir, GameSystem.DREAMCAST.coreFile).path)) } }
                assertTrue(frames.get(3, TimeUnit.SECONDS) >= 2)
                // The variables the save design fixes are in force, and the option offered for the console is one this build declares.
                val running = variables(scenario)
                assertEquals("All VMUs", running["reicast_per_content_vmus"]); assertEquals("enabled", running["reicast_digital_triggers"])
                val sharper = GameOptions.all.single { it.id == "dreamcast.sharp3d" }
                assertEquals(sharper.default, running[sharper.variable])
                assertNull(saves.read(id, "sram")) // Its memory units are files of its own, not battery memory.
                ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                scenario.onActivity { assertNull(it.engine.status.value.saveWarning); assertNull(it.coordinator.state.value.message?.takeIf { message -> "could not" in message || "Invalid" in message }) }
                val state = saves.read(id, "state")!!
                assertTrue(String(state, 0, 64, Charsets.US_ASCII).contains(GameSystem.DREAMCAST.coreRevision))
                android.util.Log.i("CartridgeDreamcast", "Program ran: red, green on a button, blue on the stick; state=${state.size} bytes")
                resume(scenario); button(scenario); shows(scenario, "red")
                pause(scenario); ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                resume(scenario); shows(scenario, "green")
                pause(scenario)
                scenario.onActivity { assertNotNull(it.engine.frozenFrame.value); assertNull(it.engine.status.value.saveWarning) }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                val own = File(context.filesDir, "saves/$id.files")
                // The memory unit in the pad's first slot, whichever game of the session this is.
                assertTrue(own.list()!!.any { it.endsWith(".A1.bin") })
                android.util.Log.i("CartridgeDreamcast", "Core's own files for this game: ${own.list()!!.sorted()}")
                // The library's own renderer and a second start both survive the hand-over from an OpenGL core.
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                shows(scenario, "red"); pause(scenario); ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); made.forget(name) }
    }
}
