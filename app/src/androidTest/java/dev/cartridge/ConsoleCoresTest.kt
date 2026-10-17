package dev.cartridge

import dev.cartridge.domain.CollectionLayout
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.Core
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
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
 * The Super Nintendo, NES, Genesis, Master System and Game Gear cores, each run for real on the device with
 * an original program from tools/probe/generate_console_probes.py: a picture, a button that changes it,
 * battery memory that keeps the choice, a state that restores it and a boot that starts from the kept save.
 */
class ConsoleCoresTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    /**
     * [magic] and [flag] are where the program keeps 0x43 and its choice in battery memory; [tone] says it holds a
     * tone. [stateHoldsBattery] is the core's own design: Genesis Plus GX leaves cartridge memory out of a state,
     * so loading one restores the picture and never the save.
     */
    private class Probe(val system: GameSystem, val file: String, val magic: Int, val flag: Int, val tone: Boolean, val stateHoldsBattery: Boolean)
    private val probes = listOf(Probe(GameSystem.SNES, "probe.sfc", 0, 1, false, true), Probe(GameSystem.NES, "probe.nes", 0, 1, true, true),
        Probe(GameSystem.GENESIS, "probe.md", 1, 3, true, false), Probe(GameSystem.SMS, "probe.sms", 0, 1, true, false),
        Probe(GameSystem.GG, "probe.gg", 0, 1, true, false))
    private fun colour(choice: Int) = if (choice == 1) GREEN else RED
    /** The last reading of the picture, for a failure's log line. */
    @Volatile private var seen = ""
    private fun shows(scenario: ActivityScenario<DebugCollectionActivity>, system: GameSystem, wanted: Int) {
        try { ui.waitUntil(8000) { pixels(scenario) == wanted } }
        catch (failure: Throwable) { android.util.Log.e("CartridgeCores", "$system never showed ${if (wanted == RED) "red" else "green"}: $seen"); throw failure }
    }

    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun choose(file: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://$FIXTURE/$file"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    /**
     * What the middle of the presented picture shows: [RED] or [GREEN] for the program's checkerboard in
     * that colour, 0 while it is flat, unreadable or anything else. It reads colour, not position, because a
     * core may settle its picture's size a frame or two after it starts.
     */
    private fun pixels(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        val result = CompletableFuture<Int>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete(0) }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x - 16, y - 16, x + 16, y + 16), bitmap, { status ->
                val colors = IntArray(1024)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(colors, 0, 32, 0, 0, 32, 32)
                val red = colors.sumOf { android.graphics.Color.red(it) } / colors.size; val green = colors.sumOf { android.graphics.Color.green(it) } / colors.size
                val blue = colors.sumOf { android.graphics.Color.blue(it) } / colors.size
                seen = "status=$status colours=${colors.toSet().size} mean=$red,$green,$blue"
                // White and one colour in equal parts: that colour's channel stays full, the other two fall to about half.
                result.complete(when {
                    status != PixelCopy.SUCCESS || colors.toSet().size < 2 -> 0
                    red > 180 && red - green > 40 && red - blue > 40 -> RED
                    green > 180 && green - red > 40 && green - blue > 40 -> GREEN
                    else -> 0
                })
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun variables(scenario: ActivityScenario<DebugCollectionActivity>): Map<String?, String?> {
        val result = CompletableFuture<Map<String?, String?>>()
        scenario.onActivity { it.engine.surface.value!!.let { view -> view.queueEvent { result.complete(view.getVariables().associate { variable -> variable.key to variable.value }) } } }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun button(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.waitForIdle() // Route Compose's resumed state before injecting a physical gameplay key.
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(150)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(150)
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    private fun resume(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    private fun eject(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-3").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    private fun start(scenario: ActivityScenario<DebugCollectionActivity>, probe: Probe) {
        ui.tool("insert").performClick()
        try { await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
        catch (failure: Throwable) {
            scenario.onActivity { android.util.Log.e("CartridgeCores", "${probe.system} did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
            throw failure
        }
    }

    @Test fun originalProgramsRenderReceiveInputKeepBatteryMemoryAndRestoreStatesOnEveryNewCore() {
        val name = "m1-test-${UUID.randomUUID()}"
        val seed = UUID.randomUUID().toString().take(8).toByteArray()
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        // A fresh identity per run, written where no program or header reads: the last bytes of the image.
        for (probe in probes) {
            val bytes = File(context.filesDir, "cartridge-${probe.file}").readBytes()
            seed.copyInto(bytes, if (probe.system == GameSystem.NES) 16 + 0x3F00 else 0x7000)
            File(fixtures, probe.file).writeBytes(bytes)
        }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name).putExtra("stayInMenu", true)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                val saves = SaveStore(File(context.filesDir, "saves"))
                for (probe in probes) {
                    val system = probe.system
                    choose(probe.file); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == system }
                    var id = ""
                    scenario.onActivity {
                        id = it.coordinator.state.value.selectedGame!!.id
                        // No object or mesh is added for a game: the bounded pool is one set per template, whatever is imported.
                        assertEquals(Media.entries.size * CollectionLayout.POOL, it.collectionSurface!!.meshInstanceCount)
                        assertEquals(system.media, it.coordinator.state.value.selectedGame!!.shownAs)
                    }
                    assertTrue(GameFiles.resolve(File(context.filesDir, "roms"), id, system).isFile)
                    start(scenario, probe)
                    // The program's own colours reach the screen: white and red first, white and green after the button.
                    val before = RED; val changed = GREEN
                    shows(scenario, system, before)
                    button(scenario); shows(scenario, system, changed)
                    pause(scenario)
                    var ram = saves.read(id, "sram")!!
                    assertEquals("$system keeps its marker", 0x43, ram[probe.magic].toInt()); assertEquals("$system keeps the choice", 1, ram[probe.flag].toInt())
                    val audio = CompletableFuture<Long>()
                    scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
                        audio.complete(SaveBridge.audioFrames(File(context.applicationInfo.nativeLibraryDir, system.coreFile).path)) } }
                    val pcm = audio.get(3, TimeUnit.SECONDS)
                    android.util.Log.i("CartridgeCores", "$system picture and input verified, battery memory=${ram.size} bytes, non-silent PCM frames=$pcm")
                    if (probe.tone) assertTrue("$system delivers its tone as PCM", pcm > 0)
                    // Every option offered for this console is a variable this build of its core declares, and a paused
                    // game takes a changed one at once. One at a time: the console applies a change as its own operation.
                    val offered = GameOptions.forSystem(system).filter { it.variable != null }
                    assertTrue("$system offers a core option", offered.isNotEmpty())
                    for (option in offered) {
                        val wanted = option.choices.first { it.value != option.default }.value
                        assertEquals(option.default, variables(scenario)[option.variable])
                        scenario.onActivity { it.shell.setGameOption(it.coordinator.state.value.selectedGame!!, option, wanted) }
                        await(scenario) { !it.coordinator.state.value.busy }
                        assertEquals("$system ${option.id}", wanted, variables(scenario)[option.variable])
                    }
                    scenario.onActivity { assertNull(it.coordinator.state.value.message); it.shell.clearGameOptions(it.coordinator.state.value.selectedGame!!) }
                    await(scenario) { !it.coordinator.state.value.busy }
                    offered.forEach { assertEquals(it.default, variables(scenario)[it.variable]) }
                    ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    val state = saves.read(id, "state")!!
                    // A state names the core build that wrote it, so another core or a later pin cannot be handed it.
                    assertTrue(String(state, 0, 64.coerceAtMost(state.size), Charsets.US_ASCII).contains(system.coreRevision))
                    resume(scenario); button(scenario); shows(scenario, system, before)
                    pause(scenario); assertEquals(0, saves.read(id, "sram")!![probe.flag].toInt())
                    ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    // The state brings back the picture it was made with.
                    resume(scenario); shows(scenario, system, changed); pause(scenario)
                    var choice = if (probe.stateHoldsBattery) 1 else 0
                    assertEquals("$system battery memory after a state load", choice, saves.read(id, "sram")!![probe.flag].toInt())
                    choice = 1 - choice
                    resume(scenario); button(scenario); shows(scenario, system, colour(choice)); pause(scenario)
                    ram = saves.read(id, "sram")!!; assertEquals(choice, ram[probe.flag].toInt())
                    scenario.onActivity { assertNull(it.engine.status.value.saveWarning); assertNotNull(it.engine.frozenFrame.value) }
                    eject(scenario)
                    // A new core instance starts from the kept battery memory, before its first frame.
                    start(scenario, probe)
                    ui.waitUntil(8000) { pixels(scenario) != 0 }
                    assertEquals("$system starts with the choice it was left on", colour(choice), pixels(scenario))
                    choice = 1 - choice
                    button(scenario); shows(scenario, system, colour(choice))
                    pause(scenario); assertEquals(choice, saves.read(id, "sram")!![probe.flag].toInt()); eject(scenario)
                    // So does a new Activity.
                    scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                    scenario.onActivity { assertEquals(system, it.coordinator.state.value.selectedGame!!.system); assertEquals(id, it.coordinator.state.value.selectedGame!!.id) }
                    start(scenario, probe)
                    ui.waitUntil(8000) { pixels(scenario) != 0 }
                    assertEquals("$system boots with the retained battery choice", colour(choice), pixels(scenario))
                    pause(scenario); eject(scenario)
                }
                scenario.onActivity { activity ->
                    assertEquals(probes.map { it.system }.toSet(), activity.coordinator.state.value.allGames.map { it.system }.toSet())
                    // Each console is its own shelf, in the registry's order, and three cores served five consoles.
                    assertEquals(probes.map { it.system.title }, activity.coordinator.state.value.shelves.map { it.title }.take(probes.size))
                    assertEquals(setOf(Core.Snes9x, Core.Fceumm, Core.GenesisPlusGx), probes.map { it.system.core }.toSet())
                }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    private companion object { const val RED = 1; const val GREEN = 2 }
}
