package dev.cartridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.apps.AppEntry
import dev.cartridge.apps.AppLibrary
import dev.cartridge.apps.AppSource
import dev.cartridge.apps.InstalledApps
import dev.cartridge.domain.SessionState
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.InputMode
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.session.CollectionScreen
import dev.cartridge.shell.Destination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android apps beside the game library: where B leads and where it does not, the real launcher service
 * on this device, a real app started and returned from, and apps that change while they are shown.
 * Games are original probes in an isolated database; the owner's library and HOME choice are not touched.
 */
class AppsLibraryTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun useTheRealSourceAgain() { DebugCollectionActivity.testApps = null }

    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, millis: Long = 30_000, check: (DebugCollectionActivity) -> Boolean) =
        ui.waitUntil(millis) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int, times: Int = 1) = repeat(times) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(80)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }; ui.waitForIdle()
    }
    /** Three playable probe cartridges; the second is focused, so "the same game" is not simply the first. */
    private fun withLibrary(test: (ActivityScenario<DebugCollectionActivity>) -> Unit) {
        val name = "m1-test-${UUID.randomUUID()}"
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            repeat(3) { index ->
                val rom = probe + byteArrayOf((index + 40).toByte()); val id = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
                File(context.filesDir, "roms/$id.gba").apply { parentFile!!.mkdirs(); writeBytes(rom) }
                repository.add(LibraryGame(id, "Original app test $index", "content://fixture"))
            }
            repository.select(1)
        }
        database.close()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && !it.coordinator.state.value.busy }
                test(scenario)
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }

    @Test fun theRouterKeepsAndroidBackApartFromTheControllersBackButton() {
        val calls = mutableListOf<String>()
        val router = ControllerRouter(navigate = {}, select = { calls += "select" }, back = { calls += "back" }, pause = { calls += "pause" },
            gameKey = { action, code -> if (action == KeyEvent.ACTION_DOWN) calls += "game:$code" }, gameMotion = { _, _ -> },
            cancelOperation = { calls += "cancel" }, systemBack = { calls += "system" })
        fun press(code: Int): List<String> {
            calls.clear(); assertTrue(router.key(KeyEvent(KeyEvent.ACTION_DOWN, code))); assertTrue(router.key(KeyEvent(KeyEvent.ACTION_UP, code))); return calls.toList()
        }
        for (mode in listOf(InputMode.Library, InputMode.Menu)) {
            router.mode = mode
            assertEquals(listOf("back"), press(KeyEvent.KEYCODE_BUTTON_B))
            assertEquals(listOf("system"), press(KeyEvent.KEYCODE_BACK)); assertEquals(listOf("system"), press(KeyEvent.KEYCODE_ESCAPE))
        }
        // The owner's confirm/back swap moves the back role, and so the way to Apps, with it.
        router.swapMenuButtons = true
        assertEquals(listOf("back"), press(KeyEvent.KEYCODE_BUTTON_A)); assertEquals(listOf("select"), press(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(listOf("system"), press(KeyEvent.KEYCODE_BACK))
        router.swapMenuButtons = false
        // In a game B is the game's, Back pauses, and neither is ever reported as a console role.
        router.mode = InputMode.Gameplay
        assertEquals(listOf("game:${KeyEvent.KEYCODE_BUTTON_B}"), press(KeyEvent.KEYCODE_BUTTON_B)); assertEquals(listOf("pause"), press(KeyEvent.KEYCODE_BACK))
        // While the console is busy or its screen is closed, both only ask to stop what is running.
        router.mode = InputMode.Locked
        assertEquals(listOf("cancel"), press(KeyEvent.KEYCODE_BUTTON_B)); assertEquals(listOf("cancel"), press(KeyEvent.KEYCODE_BACK))
        // A router that is given no separate role for Android's Back treats it as the back button, as before.
        val legacy = ControllerRouter(navigate = {}, select = {}, back = { calls += "back" }, pause = {}, gameKey = { _, _ -> }, gameMotion = { _, _ -> })
        calls.clear(); legacy.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); assertEquals(listOf("back"), calls)
    }

    @Test fun backOpensAppsFromTheLibraryAtRestAndNowhereElse() {
        val source = FixtureAppSource(FixtureAppSource.TOUR); DebugCollectionActivity.testApps = source
        withLibrary { scenario ->
            var game = ""; var generation = -1; var position = -1
            scenario.onActivity { activity ->
                val state = activity.coordinator.state.value
                game = state.selectedGame!!.id; generation = state.viewportGeneration; position = state.selection.index
                assertEquals("Original app test 1", state.selectedGame!!.title)
            }
            // The way in is said once, in the hints, and is itself the touch control. There is no other button.
            ui.onNodeWithTag("open-apps").assertIsDisplayed()

            // Something open: B closes it, and the library is where it leaves you.
            key(scenario, KeyEvent.KEYCODE_BUTTON_START); scenario.onActivity { assertEquals(Destination.SystemMenu, it.shell.top) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B); scenario.onActivity { assertNull(it.shell.top) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_X); scenario.onActivity { assertEquals(Destination.Details, it.shell.top) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B); scenario.onActivity { assertNull(it.shell.top) }
            // Android's own Back at the root does what it did before: nothing.
            key(scenario, KeyEvent.KEYCODE_BACK); scenario.onActivity { assertNull(it.shell.top) }

            // Nothing open: B is Apps.
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == Destination.Apps && it.shell.apps.phase == AppLibrary.Phase.Ready }
            ui.onNodeWithTag("page-apps").assertIsDisplayed(); ui.onNodeWithTag("apps-title").assertTextEquals("Apps")
            ui.onNodeWithTag("apps-count").assertTextEquals("${FixtureAppSource.TOUR.size} apps")
            scenario.onActivity { activity ->
                val apps = activity.shell.apps
                assertEquals(FixtureAppSource.TOUR.sortedWith(Collator.getInstance()), apps.entries.map { it.label })
                assertEquals(0, apps.focusIndex); assertTrue(apps.columns in 3..8)
            }
            // The whole grid with the D-pad: across, down a row, and no further than its edges.
            var columns = 0; scenario.onActivity { columns = it.shell.apps.columns }
            key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, 2); scenario.onActivity { assertEquals(2, it.shell.apps.focusIndex) }
            key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); scenario.onActivity { assertEquals(2 + columns, it.shell.apps.focusIndex) }
            key(scenario, KeyEvent.KEYCODE_DPAD_LEFT, 9); scenario.onActivity { assertEquals(columns, it.shell.apps.focusIndex) }
            key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 9)
            scenario.onActivity { activity -> val apps = activity.shell.apps; assertEquals((apps.entries.size - 1) / columns, apps.focusIndex / columns) }
            // The focused app scrolled into view and is the one drawn as focused.
            var focused = ""; scenario.onActivity { focused = it.shell.apps.focused!!.id }
            ui.waitUntil(5_000) { ui.shown("app:$focused") }
            ui.onNodeWithTag("app:$focused").assertIsDisplayed().assertIsSelected()
            key(scenario, KeyEvent.KEYCODE_BUTTON_A); assertEquals(listOf(focused), source.launched.toList())
            // Touch does what A does.
            ui.onNodeWithTag("app:$focused").performClick(); assertEquals(2, source.launched.size)

            // B returns to the library exactly as it was: same game, same shelf, the same stage, drawn.
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == null && it.coordinator.state.value.rendererReady }
            scenario.onActivity { activity ->
                val state = activity.coordinator.state.value
                assertEquals(game, state.selectedGame!!.id); assertEquals(position, state.selection.index)
                assertEquals(generation, state.viewportGeneration); assertEquals(CollectionScreen.Library, state.screen)
            }
            // Coming back in finds the app that was left.
            key(scenario, KeyEvent.KEYCODE_BUTTON_B); await(scenario) { it.shell.top == Destination.Apps }
            scenario.onActivity { assertEquals(focused, it.shell.apps.focused!!.id) }
            ui.onNodeWithTag("back").performClick(); scenario.onActivity { assertNull(it.shell.top) }

            // In a game B belongs to the game: it neither opens Apps nor pauses.
            key(scenario, KeyEvent.KEYCODE_BUTTON_A)
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B, 2)
            scenario.onActivity { assertNull(it.shell.top); assertEquals(SessionState.Running, it.engine.status.value.state); assertEquals(CollectionScreen.Emulator, it.coordinator.state.value.screen) }
            // Paused, B resumes.
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR)) }
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBR)) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            scenario.onActivity { assertNull(it.shell.top) }
            // The game ends the usual way, and the library it returns to still leads to Apps.
            ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
            ui.onNodeWithTag("menu-3").performClick()
            await(scenario) { val state = it.coordinator.state.value; !state.busy && state.screen == CollectionScreen.Library && state.rendererReady && !state.curtain }
            scenario.onActivity { assertEquals(game, it.coordinator.state.value.selectedGame!!.id) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B); await(scenario) { it.shell.top == Destination.Apps }
        }
    }

    @Test fun installedAppsAreListedFromAndroidAndSettingsOpensAndReturnsToAnUntouchedConsole() {
        // The device's own launcher service; nothing about what it lists is written down except counts.
        val installed = InstalledApps(context)
        val began = SystemClock.elapsedRealtime()
        val listed = installed.list()
        val listMillis = SystemClock.elapsedRealtime() - began
        assertTrue("This device shows the console no launchable apps", listed.isNotEmpty())
        assertTrue(listed.none { it.packageName == context.packageName }); assertTrue(listed.all { it.label.isNotBlank() })
        assertEquals(listed.size, listed.map { it.id }.toSet().size)
        // Everything a home screen would be given for MAIN + LAUNCHER, and nothing else.
        val launchable = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }.filterNot { it.startsWith("${context.packageName}/") }.toSet()
        assertEquals(launchable, listed.map { it.id }.toSet())
        val settingsPackage = Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)?.packageName
        val settings = checkNotNull(listed.firstOrNull { it.packageName == settingsPackage }) { "Android Settings has no launcher entry here" }
        // Real icons at the size asked for, system and user apps alike.
        val iconsBegan = SystemClock.elapsedRealtime()
        AppLibrary.sort(listed).take(12).plus(settings).forEach { entry ->
            val icon = checkNotNull(installed.icon(entry, 126)) { "No icon for a listed app" }
            assertEquals(126, icon.width); assertEquals(126, icon.height)
        }
        android.util.Log.i("CartridgeApps", "Launcher service listed ${listed.size} entries from ${listed.map { it.packageName }.toSet().size} packages in $listMillis ms; " +
            "13 icons drawn in ${SystemClock.elapsedRealtime() - iconsBegan} ms")

        // The same service inside the console, with its reads and Android's change reports counted.
        val reads = AtomicInteger(); val reports = CopyOnWriteArrayList<String>()
        DebugCollectionActivity.testApps = object : AppSource by installed {
            override fun list(): List<AppEntry> { reads.incrementAndGet(); return installed.list() }
            override fun watch(changed: (String?) -> Unit) = installed.watch { name -> reports += name ?: "*"; changed(name) }
        }
        withLibrary { scenario ->
            var game = ""; var generation = -1; var instance = 0
            scenario.onActivity { game = it.coordinator.state.value.selectedGame!!.id; generation = it.coordinator.state.value.viewportGeneration; instance = System.identityHashCode(it) }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == Destination.Apps && it.shell.apps.phase == AppLibrary.Phase.Ready }
            scenario.onActivity { activity ->
                val shown = activity.shell.apps.entries
                assertEquals(listed.map { it.id }.toSet(), shown.map { it.id }.toSet())
                // Alphabetical as this locale reads it, the same on every read.
                val order = Collator.getInstance()
                assertTrue(shown.zipWithNext().all { (first, second) -> order.compare(first.label, second.label) <= 0 })
                activity.shell.apps.focus(settings)
            }
            ui.waitUntil(5_000) { ui.shown("app:${settings.id}") }

            // Android's own report of a changed package reaches the open list and it is read again. The change is to
            // one of this build's own debug-only activities, put back at once; no other app on the device is touched.
            val probe = ComponentName(context, "dev.cartridge.SurfaceProbeActivity")
            val previous = context.packageManager.getComponentEnabledSetting(probe)
            val readsBefore = reads.get()
            try {
                context.packageManager.setComponentEnabledSetting(probe, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                ui.waitUntil(15_000) { context.packageName in reports && reads.get() > readsBefore }
            } finally { context.packageManager.setComponentEnabledSetting(probe, previous, PackageManager.DONT_KILL_APP) }
            await(scenario) { it.shell.top == Destination.Apps && it.shell.apps.focused?.id == settings.id && it.shell.apps.entries.size == listed.size }
            ui.onNodeWithTag("app:${settings.id}").assertIsDisplayed().assertIsSelected()
            // What the real icons look like is a judgement: keep one capture on the device to be looked at. It is never committed.
            Thread.sleep(900)
            File(context.filesDir, "apps-check").apply { mkdirs() }.resolve("installed.png").outputStream().use { file ->
                checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); it.recycle() }
            }

            // A starts it as a home screen would: the app comes to the front and the console is left alone behind it.
            key(scenario, KeyEvent.KEYCODE_BUTTON_A)
            val deadline = System.currentTimeMillis() + 10_000
            while (scenario.state == Lifecycle.State.RESUMED && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertNotEquals("Settings did not come to the front", Lifecycle.State.RESUMED, scenario.state)
            Thread.sleep(1_200)
            val front = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            assertEquals("The window in front is not Settings", settingsPackage, front)
            scenario.onActivity { activity ->
                // Stopped, not finished and not rebuilt; no game session was touched.
                assertFalse(activity.isFinishing); assertEquals(Destination.Apps, activity.shell.top)
                assertEquals(SessionState.Idle, activity.engine.status.value.state)
            }

            // The player's way back. Cartridge is not forced forward; it is simply what is behind the app.
            repeat(6) {
                if (scenario.state != Lifecycle.State.RESUMED) { instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(900) }
            }
            if (scenario.state != Lifecycle.State.RESUMED) {
                // Settings was already deep in its own pages from earlier use: return as its task switcher would.
                context.startActivity(Intent(context, DebugCollectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Thread.sleep(1_500)
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            ui.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("The console was rebuilt while an app was in front", instance, System.identityHashCode(activity))
                // Apps is where it was left, on the app that was started, with focus still drawn.
                assertEquals(Destination.Apps, activity.shell.top); assertEquals(settings.id, activity.shell.apps.focused!!.id)
                val state = activity.coordinator.state.value
                assertTrue(state.loaded); assertEquals(game, state.selectedGame!!.id); assertEquals(generation, state.viewportGeneration)
            }
            ui.onNodeWithTag("app:${settings.id}").assertIsDisplayed().assertIsSelected()
            // And the library behind it is the one that was left: same game, same stage, still drawn.
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == null && it.coordinator.state.value.rendererReady && !it.coordinator.state.value.busy }
            scenario.onActivity { activity ->
                val state = activity.coordinator.state.value
                assertEquals(game, state.selectedGame!!.id); assertEquals(generation, state.viewportGeneration); assertNotNull(activity.collectionSurface)
            }
        }
    }

    @Test fun appsThatChangeWhileShownKeepFocusValidAndARebuiltConsoleReturnsToThem() {
        val source = FixtureAppSource(listOf("Atlas", "Beacon", "Compass", "Dial", "Ember", "Ledger"), withoutIcon = setOf("Ledger")); DebugCollectionActivity.testApps = source
        withLibrary { scenario ->
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == Destination.Apps && it.shell.apps.entries.size == 6 }
            key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, 2); scenario.onActivity { assertEquals("Compass", it.shell.apps.focused!!.label) }

            // Uninstalled by something else while it is focused: its neighbour takes the focus, at once.
            val compass = source.installed.first { it.label == "Compass" }
            source.installed.remove(compass); scenario.onActivity { source.report!!(compass.packageName) }
            await(scenario) { it.shell.apps.entries.size == 5 }
            scenario.onActivity { assertEquals("Dial", it.shell.apps.focused!!.label) }
            ui.onNodeWithTag("apps-count").assertTextEquals("5 apps")
            ui.onNodeWithTag("app:${compass.id}").assertDoesNotExist()
            // Installed meanwhile: it appears in its place without taking the focus.
            source.installed += AppEntry("example.added", "Main", "Abacus"); scenario.onActivity { source.report!!("example.added") }
            await(scenario) { it.shell.apps.entries.size == 6 }
            scenario.onActivity { assertEquals("Abacus", it.shell.apps.entries.first().label); assertEquals("Dial", it.shell.apps.focused!!.label) }

            // An app that can no longer be started says so in plain words and nothing breaks.
            source.refuse = true
            key(scenario, KeyEvent.KEYCODE_BUTTON_A)
            await(scenario) { it.coordinator.state.value.message?.contains("couldn't be opened") == true }
            scenario.onActivity { activity ->
                val message = activity.coordinator.state.value.message!!
                assertTrue(message.startsWith("Dial")); assertFalse(message.contains("example."))
                assertEquals(Destination.Apps, activity.shell.top); assertNotNull(activity.shell.apps.focused)
            }
            ui.onNodeWithTag("message").assertIsDisplayed()
            source.refuse = false

            // Search with the console's own keyboard, on the controller's Y.
            key(scenario, KeyEvent.KEYCODE_BUTTON_Y); scenario.onActivity { assertTrue(it.shell.top is Destination.TextEntry) }
            scenario.onActivity { "be".forEach(it.shell::type) }; key(scenario, KeyEvent.KEYCODE_BUTTON_START)
            scenario.onActivity { activity ->
                assertEquals(Destination.Apps, activity.shell.top)
                assertEquals(listOf("Beacon", "Ember"), activity.shell.apps.entries.map { it.label })
            }
            ui.onNodeWithTag("apps-count").assertTextEquals("“be” · 2 results")
            key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT); scenario.onActivity { assertEquals("Ember", it.shell.apps.focused!!.label) }
            // B clears the search first and keeps the app that was found; only then does it leave.
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            scenario.onActivity { assertEquals(Destination.Apps, it.shell.top); assertEquals(6, it.shell.apps.entries.size); assertEquals("Ember", it.shell.apps.focused!!.label) }

            // Android rebuilds the Activity while Apps is open: Apps comes back, on the same app.
            scenario.recreate()
            await(scenario) { it.coordinator.state.value.loaded && it.shell.top == Destination.Apps && it.shell.apps.entries.size == 6 }
            scenario.onActivity { assertEquals("Ember", it.shell.apps.focused!!.label) }
            ui.onNodeWithTag("app:${source.installed.first { it.label == "Ember" }.id}").assertIsDisplayed().assertIsSelected()
            // An app without an icon still has a tile.
            ui.onNodeWithTag("app:${source.installed.first { it.label == "Ledger" }.id}").assertIsDisplayed()

            // Nothing left to list is a state of its own, with a way out and a way to ask again.
            source.installed.clear(); scenario.onActivity { source.report!!(null) }
            await(scenario) { it.shell.apps.entries.isEmpty() }
            ui.onNodeWithTag("apps-empty").assertIsDisplayed()
            val reads = source.reads
            key(scenario, KeyEvent.KEYCODE_BUTTON_A); await(scenario) { source.reads > reads }
            key(scenario, KeyEvent.KEYCODE_BUTTON_B)
            await(scenario) { it.shell.top == null && it.coordinator.state.value.rendererReady }
            scenario.onActivity { assertNotNull(it.coordinator.state.value.selectedGame) }
        }
    }
}
