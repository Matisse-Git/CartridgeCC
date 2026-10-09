package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.SaveBridge
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Opt-in sustained test using original probes and a separate DB, never commercial games. */
class ConsoleStabilityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    private fun frames(scenario: ActivityScenario<DebugCollectionActivity>): Long {
        val result = CompletableFuture<Long>()
        scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent { result.complete(SaveBridge.frameCount("${activity.applicationInfo.nativeLibraryDir}/libmgba_libretro.so")) } }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun press(scenario: ActivityScenario<DebugCollectionActivity>) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
    }
    @Test fun sustainedInternalPlayWithCheckpointsSleepWakeAndRepeatedEjectHasNoSessionFailures() {
        val minutes = InstrumentationRegistry.getArguments().getString("soakMinutes")?.toInt() ?: error("Run tools/Test-Stability.ps1; this test is opt-in")
        require(minutes in 2..120)
        val name = "m1-test-${UUID.randomUUID()}"
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)
        val fixture = File(context.filesDir, "m1-fixtures/probe.gb").apply { parentFile!!.mkdirs() }
        val bytes = File(context.filesDir, "cartridge-probe.gb").readBytes()
        UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes, 0x134); fixture.writeBytes(bytes)
        val samples = mutableListOf<String>(); var id = ""
        val progress = File(context.filesDir, "m6-soak-progress.properties")
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.gb"))))
                    ui.tool("choose-game").performClick()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id }
                ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                repeat(minutes) { minute ->
                    val before = frames(scenario); val start = System.nanoTime()
                    while (System.nanoTime() - start < 60_000_000_000L) {
                        Thread.sleep(500)
                        scenario.onActivity { assertEquals(SessionState.Running, it.engine.status.value.state); assertNull(it.engine.status.value.saveWarning) }
                    }
                    val produced = frames(scenario) - before
                    val seconds = (System.nanoTime() - start) / 1e9
                    assertTrue("Core stopped producing video: $produced frames in $seconds seconds", produced > seconds * 35)
                    press(scenario)
                    ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                    scenario.onActivity { assertNotNull(it.engine.frozenFrame.value); assertNull(it.engine.status.value.saveWarning) }
                    assertEquals((minute + 1) % 2, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt() and 255)
                    ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                    try {
                        shell("input keyevent KEYCODE_SLEEP")
                        await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Idle }
                        assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                    } finally { shell("input keyevent KEYCODE_WAKEUP") }
                    await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                    scenario.onActivity { assertNotNull(it.engine.frozenFrame.value); assertNull(it.engine.status.value.saveWarning) }
                    assertEquals((minute + 1) % 2, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt() and 255)
                    ui.onNodeWithTag("menu-3").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady }
                    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val sample = "minute=${minute+1},pssKiB=${Debug.getPss()},javaUsedKiB=${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024},coreFps=${produced/seconds},thermal=${context.getSystemService(PowerManager::class.java).currentThermalStatus},batteryTenthsC=${battery?.getIntExtra("temperature", -1)},plugged=${battery?.getIntExtra("plugged", -1)}"
                    samples.add(sample); println(sample)
                    Properties().apply { setProperty("completed", (minute+1).toString()); setProperty("total", minutes.toString()); setProperty("samples", samples.joinToString("\n")) }
                        .let { values -> progress.outputStream().use { values.store(it, "Original probe stability") } }
                    if (minute < minutes - 1) { ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                }
            }
        } finally { shell("input keyevent KEYCODE_WAKEUP"); DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
}
