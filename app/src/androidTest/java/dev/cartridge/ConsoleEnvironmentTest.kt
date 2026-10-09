package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.environment.*
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ConsoleEnvironmentTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var done = false; scenario.onActivity { done = check(it) }; done }
    }
    private fun action(index: Int) { ui.onNodeWithTag("console-action-$index").performScrollTo().performClick(); ui.waitForIdle() }
    private fun press(scenario: ActivityScenario<DebugCollectionActivity>, key: Int) {
        ui.waitForIdle()
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)) }; Thread.sleep(120)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)) }; Thread.sleep(120)
    }
    @Test fun onboardingAndIndependentControllerCorePreferencesSurviveRecreationAndDriveNativeInput() {
        val name = "m1-test-${UUID.randomUUID()}"
        val fixture = File(context.filesDir, "m1-fixtures/probe.gb").apply { parentFile!!.mkdirs() }
        val bytes = File(context.filesDir, "cartridge-probe.gb").readBytes()
        UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes, 0x134); fixture.writeBytes(bytes)
        val preferences = context.getSharedPreferences("$name-presentation", 0)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java)
                .putExtra("testDatabase", name).putExtra("showSetup", true)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                ui.onNodeWithText("Welcome to Cartridge").assertExists(); action(2)
                assertTrue(preferences.getBoolean("setupComplete", false))
                ui.tool("console-settings").performClick(); action(1)
                action(1); action(2); action(4); action(5); action(0)
                val selected = ConsolePreferences(preferences).read()
                assertTrue(selected.swapMenuButtons); assertTrue(selected.swapGameButtons); assertEquals(.5f, selected.deadZone)
                assertTrue(preferences.getBoolean("alternateShortcut", false))
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                ui.onNodeWithText("Welcome to Cartridge").assertDoesNotExist()
                assertEquals(selected, ConsolePreferences(preferences).read())
                ui.tool("console-settings").performClick(); action(2); action(1); action(2); action(0)
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.gb"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                var id = ""; scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id }
                ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                val variables = CompletableFuture<Map<String?, String?>>()
                scenario.onActivity { it.engine.surface.value!!.let { view -> view.queueEvent { variables.complete(view.getVariables().associate { variable -> variable.key to variable.value }) } } }
                val options = variables.get(3, TimeUnit.SECONDS)
                assertEquals("enabled", options["mgba_audio_low_pass_filter"]); assertEquals("1", options["mgba_frameskip"])
                press(scenario, KeyEvent.KEYCODE_BUTTON_A) // Maps to B; diagnostic changes only on logical A.
                ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                val saves = SaveStore(File(context.filesDir, "saves")); assertEquals(0, saves.read(id, "sram")!![1].toInt())
                ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                press(scenario, KeyEvent.KEYCODE_BUTTON_B)
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)) }
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                assertEquals(1, saves.read(id, "sram")!![1].toInt())
                ui.onNodeWithTag("menu-4").performClick(); action(1)
                await(scenario) { !it.coordinator.state.value.busy }
                assertFalse(ConsolePreferences(preferences).read().audioFilter)
                action(0); ui.onNodeWithTag("menu-3").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.rendererReady }
                ui.tool("console-settings").performClick(); action(8)
                ui.waitUntil(3000) { ui.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("Apache", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); preferences.edit().clear().commit() }
    }
    @Test fun optionalHomeCandidateCanBeRegisteredWithoutChangingAndroidDefault() {
        val home = HomeRegistration(context)
        val component = ComponentName(context.packageName, "dev.cartridge.CartridgeHome")
        val previous = context.packageManager.getComponentEnabledSetting(component)
        val roles = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
        val held = roles?.isRoleHeld(RoleManager.ROLE_HOME)
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        try {
            home.enable(false); assertFalse(home.enabled())
            assertFalse(context.packageManager.queryIntentActivities(query, 0).any { it.activityInfo.name == component.className })
            home.enable(true); assertTrue(home.enabled())
            assertTrue(context.packageManager.queryIntentActivities(query, 0).any { it.activityInfo.name == component.className })
            assertNotNull(home.requestIntent()) // Never launch the role request or modify the owner's default.
            assertEquals(held, roles?.isRoleHeld(RoleManager.ROLE_HOME))
        } finally { context.packageManager.setComponentEnabledSetting(component, previous, PackageManager.DONT_KILL_APP) }
    }
}
