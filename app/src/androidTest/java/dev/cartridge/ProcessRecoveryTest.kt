package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
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

/** Two different instrumentation processes. The first is deliberately killed while core is running. */
class ProcessRecoveryTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(context.filesDir, "m6-process-ready.properties")
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun press(scenario: ActivityScenario<DebugCollectionActivity>) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
    }
    private fun pixels(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        var result = 0
        scenario.onActivity { val frame = checkNotNull(it.engine.frozenFrame.value); val colors = IntArray(64); frame.getPixels(colors, 0, 8, frame.width/2-4, frame.height/2-4, 8, 8); result = colors.contentHashCode(); assertTrue(colors.toSet().size > 1) }
        return result
    }
    @Test fun prepareCompletedCheckpointThenHoldUncheckpointedRunningCoreForHostKill() {
        val token = checkNotNull(InstrumentationRegistry.getArguments().getString("proofToken")) { "Use tools/Test-ProcessRecovery.ps1" }
        require(token.matches(Regex("[a-f0-9-]{36}")))
        val name = "m1-test-${UUID.randomUUID()}"
        val fixture = File(context.filesDir, "m1-fixtures/probe.gb").apply { parentFile!!.mkdirs() }
        val bytes = File(context.filesDir, "cartridge-probe.gb").readBytes(); UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes, 0x134); fixture.writeBytes(bytes)
        ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            Intents.init()
            try {
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.gb"))))
                ui.tool("choose-game").performClick()
            } finally { Intents.release() }
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
            var id = ""; scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id }
            ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            press(scenario); ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
            assertEquals(1, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt())
            val expected = pixels(scenario)
            ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }; press(scenario)
            val liveRam = CompletableFuture<ByteArray>()
            scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent { liveRam.complete(SaveBridge.read("${activity.applicationInfo.nativeLibraryDir}/libmgba_libretro.so")) } }
            assertEquals(0, liveRam.get(3, TimeUnit.SECONDS)[1].toInt()) // Original GB probe toggles its palette flag.
            assertEquals(1, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt())
            Properties().apply { setProperty("token", token); setProperty("database", name); setProperty("id", id); setProperty("pixels", expected.toString()); setProperty("pid", android.os.Process.myPid().toString()) }
                .let { values -> marker.outputStream().use { values.store(it, "Prepared original probe; host now force-stops") } }
            // No final lifecycle/save callback: host intentionally terminates this target process.
            Thread.sleep(120000)
            fail("Host did not kill the active process in time")
        }
    }
    @Test fun freshProcessRestoresLastCompletedCheckpointPausedWithRealPixelsAndBattery() {
        val token = checkNotNull(InstrumentationRegistry.getArguments().getString("proofToken"))
        val values = Properties().apply { marker.inputStream().use(::load) }; assertEquals(token, values.getProperty("token"))
        val name = values.getProperty("database"); require(name.matches(Regex("m1-test-[a-f0-9-]+")))
        val id = values.getProperty("id"); require(id.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(values.getProperty("pid").toInt(), android.os.Process.myPid())
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.coordinator.state.value.resumeSession?.id == id }
                assertEquals(1, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt())
                ui.tool("resume-session").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                assertEquals(values.getProperty("pixels").toInt(), pixels(scenario)); assertEquals(1, SaveStore(File(context.filesDir, "saves")).read(id, "sram")!![1].toInt())
                ui.onNodeWithTag("menu-3").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); marker.delete() }
    }
}
