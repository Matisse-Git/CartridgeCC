package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
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
}
