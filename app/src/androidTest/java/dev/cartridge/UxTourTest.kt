package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.library.*
import dev.cartridge.session.CollectionScreen
import dev.cartridge.shell.Destination
import dev.cartridge.shell.ImportFlow
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Walks the redesigned console with a controller and saves what the display really showed, 3D surface
 * included, to files/ux-tour. Every title, label and game here is an original fixture in an isolated
 * database; the owner's library is never opened.
 */
class UxTourTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val shots get() = File(context.filesDir, "ux-tour")

    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int, times: Int = 1) = repeat(times) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(80)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }; ui.waitForIdle()
    }
    private fun shot(name: String) {
        ui.waitForIdle(); Thread.sleep(650) // Let springs settle and the surface present.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "The display could not be captured" }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun artwork(base: Int, accent: Int, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(base)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            drawCircle(width * .72f, height * .46f, height * .34f, paint)
            paint.color = Color.argb(150, 255, 255, 255); drawRect(width * .08f, height * .70f, width * .52f, height * .76f, paint)
            drawRect(width * .08f, height * .80f, width * .34f, height * .84f, paint)
        }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
    }

    @Test fun tourEveryRedesignedStateOnTheRealRenderer() {
        shots.deleteRecursively(); check(shots.mkdirs())
        val name = "m1-test-${UUID.randomUUID()}"
        val tree = File(context.filesDir, "m1-tree").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        repeat(6) { File(tree, "g$it.gba").writeBytes(probe + byteArrayOf((it + 90).toByte())) }
        File(tree, "invalid.gba").writeBytes(byteArrayOf(1, 2))
        for (extension in listOf("gb", "gbc")) File(context.filesDir, "cartridge-probe.$extension").readBytes().let { bytes ->
            UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes, 0x134); File(tree, "m5-$extension.$extension").writeBytes(bytes)
        }
        // A shader library of the tour's own: names only, as the picker lists them (ADR 0027).
        val shaderBase = "m1-test-shaders-tour"
        File(context.filesDir, shaderBase).apply {
            deleteRecursively()
            for (preset in listOf("crt/crt-royale.glslp", "crt/crt-geom.glslp", "crt/crt-guest-dr-venom.glslp", "crt/crt-lottes.glslp",
                    "crt/zfast-crt.glslp", "handheld/lcd-grid-v2.glslp", "handheld/gb-dot-matrix-DMG.glslp", "scanlines/scanline.glslp",
                    "xbr/xbr-lv2.glslp", "sharpen/adaptive-sharpen.glslp"))
                File(this, "library/$preset").apply { parentFile!!.mkdirs(); writeText("shaders = 0\n") }
            File(this, "library/${dev.cartridge.shaders.ShaderLibraryUpdater.VERSION_FILE}")
                .writeText("commit=148ce728155232a6b63be772c1810bd84728f359\ndate=2026-10-11T23:22:17Z\npresets=621\nfiles=1463\nbytes=34336068\n")
        }
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name).putExtra("testShaderBase", shaderBase)
        // Invented apps with drawn icons: what the owner has installed is never in a screenshot.
        DebugCollectionActivity.testApps = FixtureAppSource(FixtureAppSource.TOUR, withoutIcon = setOf("Ledger"))
        try {
            // Journey A: an empty console, one action, a folder, a summary.
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                ui.onNodeWithTag("welcome").assertIsDisplayed()
                shot("01-welcome")
                // An empty library is still the library's root: the Android apps are one press away from it too.
                ui.onNodeWithTag("open-apps").assertIsDisplayed()
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); await(scenario) { it.shell.top == Destination.Apps }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); scenario.onActivity { assertNull(it.shell.top) }
                ui.onNodeWithTag("welcome").assertIsDisplayed()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture-tree/tree/root"))))
                    key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                } finally { Intents.release() }
                await(scenario) { it.shell.import is ImportFlow.Done }
                scenario.onActivity { activity ->
                    val result = (activity.shell.import as ImportFlow.Done).result
                    assertEquals(mapOf(GameSystem.GBA to 6, GameSystem.GB to 1, GameSystem.GBC to 1), result.addedSystems); assertEquals(1, result.failed)
                }
                shot("02-import-summary")
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { it.shell.top == null && !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                scenario.onActivity { assertEquals(Shelf.Console(GameSystem.GBA), it.coordinator.state.value.shelf) }
            }
            DebugCollectionActivity.closeDatabases()

            // Dress the fixture library: original titles, three drawn labels, a favourite, some history, and discs.
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
            val labels = LabelStore(File(context.filesDir, "m1-test-labels"))
            val broken = "d15c".padEnd(64, '0')
            runBlocking {
                val repository = RoomCollectionRepository(context, database)
                fun value(text: String) = MetadataValue(text, "Fixture", "tour", "", "Original fixture", 1)
                val games = repository.initialize().slots.filterNotNull()
                val titles = mapOf(GameSystem.GBA to listOf("Orbit Field", "Aster Pilot", "Lumen Drift", "Tidewater Run", "Paper Kites", "Night Garden"),
                    GameSystem.GB to listOf("Pocket Almanac"), GameSystem.GBC to listOf("Signal Bloom"))
                titles.forEach { (system, names) ->
                    games.filter { it.system == system }.forEachIndexed { index, game ->
                        repository.setMetadata(game.id, mapOf("title" to value(names[index]), "year" to value("${2001 + index}"), "developer" to value("Studio Meridian"),
                            "genre" to value("Adventure"), "description" to value("A quiet expedition across a drifting archipelago, told in short chapters. An original fixture written for interface testing.")))
                    }
                }
                val gba = games.filter { it.system == GameSystem.GBA }
                listOf(Color.rgb(34, 58, 94) to Color.rgb(240, 180, 90), Color.rgb(120, 46, 52) to Color.rgb(240, 214, 190), Color.rgb(36, 84, 72) to Color.rgb(214, 230, 150))
                    .forEachIndexed { index, (base, accent) -> repository.setLabel(gba[index * 2].id, labels.importStream("tour-$index") { artwork(base, accent, 512, 256).inputStream() }) }
                repository.setFavorite(gba[1].id, true); repository.recordPlay(gba[0].id, 5_400_000, System.currentTimeMillis() - 86_400_000)
                repository.recordPlay(gba[2].id, 780_000, System.currentTimeMillis() - 3_600_000)
                listOf("Glass Meridian", "Harbor Lights", "Quiet Engines").forEachIndexed { index, title ->
                    val id = if (index == 0) broken else (index + 0xa0).toString(16).padStart(64, '0')
                    repository.add(LibraryGame(id, title, "content://fixture/$index", system = GameSystem.PS1))
                    repository.setMetadata(id, mapOf("year" to value("${1997 + index}"), "developer" to value("Northern Circuit")))
                    if (index == 1) repository.setLabel(id, labels.importStream("tour-disc") { artwork(Color.rgb(226, 222, 212), Color.rgb(196, 70, 60), 512, 512).inputStream() })
                }
                // The consoles shown as their own cartridges. A Super Nintendo game named as a North American release
                // has that market's shell; every other one has the console's own. One of each carries artwork.
                val cartridges = listOf(
                    GameSystem.SNES to listOf("Lantern Coast (Japan)", "Lantern Coast (USA)", "Winter Signal (Europe)"),
                    GameSystem.NES to listOf("Copper Kingdom", "Marble Run"), GameSystem.GENESIS to listOf("Velvet Circuit", "Salt and Ember"))
                cartridges.forEachIndexed { console, (system, names) ->
                    names.forEachIndexed { index, title ->
                        val id = (0xb0 + console * 8 + index).toString(16).padStart(64, '0')
                        val game = LibraryGame(id, title, "content://fixture/cartridge-$console-$index", system = system)
                        repository.add(game); repository.setMetadata(id, mapOf("year" to value("${1990 + console + index}"), "developer" to value("Studio Meridian")))
                        if (index == 1) repository.setLabel(id, labels.importStream("tour-${system.name.lowercase()}") {
                            artwork(Color.rgb(30 + console * 40, 70, 96 - console * 20), Color.rgb(236, 200, 120), 512, game.shownAs.labelHeight).inputStream() })
                    }
                }
                repository.select(0)
            }
            database.close()

            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                // Journey D: browse, switch console with the shoulders.
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
                shot("03-library-game-boy-advance")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.GB) }
                shot("03b-library-game-boy")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1, 2)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.PS1) }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
                shot("04-library-playstation")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.SNES) }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
                shot("04b-library-super-nintendo")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.NES) }
                shot("04c-library-nes")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.GENESIS) }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
                shot("04d-library-genesis")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.All }
                shot("05-library-all-games")
                key(scenario, KeyEvent.KEYCODE_BUTTON_L1, 7)
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.GBA) }
                scenario.onActivity { it.coordinator.navigate(-9) }

                // Details are optional and sit beside the object.
                key(scenario, KeyEvent.KEYCODE_BUTTON_X)
                ui.onNodeWithTag("details-panel").assertIsDisplayed()
                shot("06-details")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 3); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                shot("07-manage-game")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); key(scenario, KeyEvent.KEYCODE_DPAD_UP); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                scenario.onActivity { assertEquals(Destination.Options, it.shell.top) }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, 2)
                shot("08-game-options")
                key(scenario, KeyEvent.KEYCODE_DPAD_LEFT, 2); key(scenario, KeyEvent.KEYCODE_BUTTON_B, 2)
                scenario.onActivity { assertNull(it.shell.top) }

                // The system menu and one settings destination.
                key(scenario, KeyEvent.KEYCODE_BUTTON_START)
                shot("09-system-menu")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 6); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                shot("10-settings-library")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                shot("11-settings-appearance")
                // Journey H: a PlayStation video setting, under its own console's heading.
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 5)
                shot("12-settings-gameplay")
                // Shaders: the one Update row, every console, then each console; a picker of the library's folders.
                scenario.onActivity {
                    it.shell.shaderChoices.set(dev.cartridge.shaders.ShaderTarget.Global, dev.cartridge.shaders.ShaderChoice.Preset("crt/crt-royale.glslp"))
                    it.shell.shaderChoices.set(dev.cartridge.shaders.ShaderTarget.Console(GameSystem.GBA), dev.cartridge.shaders.ShaderChoice.Preset("handheld/lcd-grid-v2.glslp"))
                    it.shell.shaderChoices.set(dev.cartridge.shaders.ShaderTarget.Console(GameSystem.PS2), dev.cartridge.shaders.ShaderChoice.Off)
                }
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1); key(scenario, KeyEvent.KEYCODE_DPAD_UP, 8)
                scenario.onActivity { assertEquals(dev.cartridge.shell.SettingsCategory.Shaders, it.shell.category) }
                shot("12b-settings-shaders")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { (it.shell.top as? Destination.ShaderPicker)?.folder == "" && it.shell.shaderListings[""] != null }
                shot("12c-shader-picker")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { (it.shell.top as? Destination.ShaderPicker)?.folder == "crt" && it.shell.shaderListings["crt"] != null }
                shot("12d-shader-picker-crt")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B, 2)
                scenario.onActivity {
                    assertEquals(Destination.Settings, it.shell.top)
                    it.shell.shaderChoices.resetConsoles()
                }
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1); shot("13-settings-controls")
                key(scenario, KeyEvent.KEYCODE_BUTTON_R1); shot("14-settings-system")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B, 3)
                scenario.onActivity { assertNull(it.shell.top) }

                // Search without touching the screen.
                key(scenario, KeyEvent.KEYCODE_BUTTON_START); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, 6); key(scenario, KeyEvent.KEYCODE_BUTTON_A) // "j"? no: row a-l, column 6 is "j"
                shot("15-search-keyboard")
                key(scenario, KeyEvent.KEYCODE_BUTTON_A); key(scenario, KeyEvent.KEYCODE_BUTTON_START)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.searching }
                shot("16-search-no-result")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                await(scenario) { !it.coordinator.state.value.busy && !it.coordinator.state.value.searching }

                // Journey F: layouts keep the focused game.
                var focused = ""; scenario.onActivity { focused = it.coordinator.state.value.selectedGame!!.id; it.shell.setLayout(CollectionLayout.Grid) }
                shot("17-layout-grid")
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.All); it.coordinator.navigate(4 - it.coordinator.state.value.selection.index) }
                shot("17b-layout-grid-all-games")
                scenario.onActivity { it.coordinator.navigate(13 - it.coordinator.state.value.selection.index) }
                shot("17c-layout-grid-cartridges")
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.Console(GameSystem.GBA)) }
                scenario.onActivity { assertEquals(focused, it.coordinator.state.value.selectedGame!!.id); it.shell.setLayout(CollectionLayout.Vertical) }
                shot("18-layout-vertical")
                scenario.onActivity { assertEquals(focused, it.coordinator.state.value.selectedGame!!.id); it.shell.setLayout(CollectionLayout.Horizontal) }

                // Journey G: a game whose file is gone explains itself and offers the repair.
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.Console(GameSystem.PS1)); it.coordinator.navigate(-9) }
                await(scenario) { it.coordinator.state.value.selectedGame?.id == broken }
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { it.coordinator.state.value.launchFailure != null && it.shell.top == Destination.LaunchFailure }
                shot("19-launch-recovery")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                scenario.onActivity { assertNull(it.coordinator.state.value.launchFailure); assertEquals(broken, it.coordinator.state.value.selectedGame!!.id) }

                // Journey E: play, pause, save, options, return.
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.Console(GameSystem.GBA)) }
                await(scenario) { it.coordinator.state.value.shelf == Shelf.Console(GameSystem.GBA) && it.coordinator.state.value.rendererReady }
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                shot("20-playing")
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR)) }
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBR)) }
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message == "State saved" }
                shot("21-pause-menu")
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 2); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                scenario.onActivity { assertEquals(Destination.Options, it.shell.top) }
                shot("22-pause-game-options")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 4); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(Shelf.Console(GameSystem.GBA), it.coordinator.state.value.shelf); assertTrue(it.coordinator.state.value.selectedGame!!.playMs >= 0) }

                // Label preview on the real object, then the reviewed artwork workflow.
                val draft = LabelStore(File(context.filesDir, "m1-test-labels")).importStream("tour-preview") { artwork(Color.rgb(70, 60, 110), Color.rgb(250, 200, 120), 512, 256).inputStream() }
                scenario.onActivity { it.coordinator.previewLabel { draft } }
                await(scenario) { it.coordinator.state.value.labelPreviewReady }
                shot("23-label-preview")
                ui.onNodeWithTag("label-action-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library }
                key(scenario, KeyEvent.KEYCODE_BUTTON_START); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN, 2); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                await(scenario) { it.scraper.state.value.visible }
                shot("24-artwork")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                await(scenario) { !it.scraper.state.value.visible }

                // The Android apps: B from the library with nothing open, their own icons, and B back to the same game.
                var game = ""; scenario.onActivity { game = it.coordinator.state.value.selectedGame!!.id; assertNull(it.shell.top) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                await(scenario) { it.shell.top == Destination.Apps && it.shell.apps.entries.size == FixtureAppSource.TOUR.size }
                key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, 2); key(scenario, KeyEvent.KEYCODE_DPAD_DOWN)
                shot("25-apps")
                key(scenario, KeyEvent.KEYCODE_BUTTON_Y); scenario.onActivity { assertTrue(it.shell.top is Destination.TextEntry); "er".forEach(it.shell::type) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_START)
                scenario.onActivity { assertEquals(Destination.Apps, it.shell.top); assertTrue(it.shell.apps.searching) }
                shot("26-apps-search")
                key(scenario, KeyEvent.KEYCODE_BUTTON_B); scenario.onActivity { assertEquals(Destination.Apps, it.shell.top); assertFalse(it.shell.apps.searching) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B)
                await(scenario) { it.shell.top == null && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(game, it.coordinator.state.value.selectedGame!!.id) }
            }
        } finally { DebugCollectionActivity.testApps = null; DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); tree.listFiles()?.forEach { it.delete() } }
    }
}
