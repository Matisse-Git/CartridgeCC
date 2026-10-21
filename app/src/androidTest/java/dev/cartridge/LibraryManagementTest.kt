package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.library.*
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.DirectoryScanner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryManagementTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun choose(filename: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://$FIXTURE/$filename"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
        // Navigation samples held directions on Choreographer; hold through a real display frame.
        if (code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) Thread.sleep(80)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
        ui.waitForIdle()
    }
    @Test fun controllerDetailsFavoritesSearchMetadataAndNativeHistoryPersist() {
        val bytes = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        File(fixtures, "probe-one.gba").writeBytes(bytes)
        File(fixtures, "probe-two.gba").writeBytes(bytes + byteArrayOf(7))
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", "m1-test-${UUID.randomUUID()}")
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                choose("probe-one.gba"); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                var first = ""; scenario.onActivity { first = it.coordinator.state.value.selectedGame!!.id }
                choose("probe-two.gba"); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.slots.size == 2 }
                var second = ""; scenario.onActivity { second = it.coordinator.state.value.selectedGame!!.id }
                key(scenario, KeyEvent.KEYCODE_BUTTON_Y)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.favorite == true }
                key(scenario, KeyEvent.KEYCODE_BUTTON_X)
                ui.onNodeWithTag("details-panel").assertIsDisplayed()
                ui.onNodeWithTag("details-history").assertTextEquals("Not played yet")
                // Controller only: Manage game is the last details row, Edit details the third row of that sheet.
                repeat(4) { key(scenario, KeyEvent.KEYCODE_DPAD_DOWN) }; key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                ui.onNodeWithTag("sheet-manage").assertIsDisplayed()
                repeat(2) { key(scenario, KeyEvent.KEYCODE_DPAD_DOWN) }; key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                ui.onNodeWithTag("page-edit").assertIsDisplayed()
                key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                ui.onNodeWithTag("edit-title").performTextReplacement("Aster Pilot")
                key(scenario, KeyEvent.KEYCODE_BUTTON_START)
                ui.onNodeWithTag("field-year").performClick()
                // The grid types without touch: focus starts on Q, so 2-0-0-2 is typed from the number row.
                key(scenario, KeyEvent.KEYCODE_DPAD_UP); key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                repeat(8) { key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT) }; key(scenario, KeyEvent.KEYCODE_BUTTON_A); key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                repeat(8) { key(scenario, KeyEvent.KEYCODE_DPAD_LEFT) }; key(scenario, KeyEvent.KEYCODE_BUTTON_A)
                ui.onNodeWithTag("edit-year").assertTextEquals("2002")
                key(scenario, KeyEvent.KEYCODE_BUTTON_START)
                ui.onNodeWithTag("metadata-save").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.title == "Aster Pilot" }
                ui.onNodeWithTag("details-subtitle").assertTextContains("2002", substring = true)
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-library").performClick()
                ui.onNodeWithTag("settings-sort").performScrollTo().performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.query.sort == LibrarySort.Title }
                scenario.onActivity { assertEquals(second, it.coordinator.state.value.selectedGame!!.id); assertEquals(0, it.coordinator.state.value.selection.index) }
                ui.home(); key(scenario, KeyEvent.KEYCODE_BUTTON_R1)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.shelf == Shelf.Favorites && it.coordinator.state.value.slots.size == 1 }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(second, it.coordinator.state.value.selectedGame!!.id); assertEquals(Shelf.Favorites, it.coordinator.state.value.shelf); assertEquals("2002", it.coordinator.state.value.selectedGame!!.metadata["year"]!!.value) }
                ui.tool("search").performClick()
                ui.onNodeWithTag("library-search-text").performTextReplacement("no match")
                ui.onNodeWithTag("text-done").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame == null }
                ui.onNodeWithTag("selected-title").assertTextEquals("No games match “no match”")
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals("no match", it.coordinator.state.value.query.search); assertEquals(2, it.coordinator.state.value.allGames.size) }
                key(scenario, KeyEvent.KEYCODE_BUTTON_B) // From the library, B leaves a search.
                await(scenario) { !it.coordinator.state.value.busy && !it.coordinator.state.value.searching && it.coordinator.state.value.shelf == Shelf.Favorites }
                key(scenario, KeyEvent.KEYCODE_BUTTON_L1)
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.slots.size == 2 }
                scenario.onActivity { it.coordinator.query(LibraryQuery()) }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.query.sort == LibrarySort.Added }
                scenario.onActivity { it.coordinator.navigate(1 - it.coordinator.state.value.selection.index) }
                ui.tool("insert").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                Thread.sleep(500)
                ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                ui.onNodeWithTag("menu-3").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity {
                    val game = it.coordinator.state.value.selectedGame!!
                    assertEquals(second, game.id); assertTrue(game.playMs >= 400); assertTrue(game.lastPlayed > 0)
                    assertTrue(it.coordinator.state.value.allGames.any { item -> item.id == first })
                }
            }
        } finally { DebugCollectionActivity.closeDatabases() }
    }

    @Test fun versionFourMigrationIncrementalVerificationAndMissingRecoveryKeepEnrichment() = runBlocking {
        val name = "m3-index-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/4.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) } }
            }
            schema.getJSONArray("setupQueries").let { setup -> repeat(setup.length()) { db.execSQL(setup.getString(it)) } }
            db.execSQL("INSERT INTO collection_state VALUES (0,0)"); db.execSQL("INSERT INTO slots VALUES (0,NULL)"); db.version = 4
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        val treeFiles = File(context.filesDir, "m1-tree").apply { mkdirs() }
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        repeat(6) { File(treeFiles, "g$it.gba").writeBytes(probe + byteArrayOf((it + 40).toByte())) }
        File(treeFiles, "duplicate.gba").writeBytes(File(treeFiles, "g0.gba").readBytes())
        File(treeFiles, "invalid.gba").delete(); File(treeFiles, "readme.gba").delete()
        val tree = Uri.parse("content://$FIXTURE_TREE/tree/root")
        try {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            val scanner = DirectoryScanner(context.contentResolver, File(context.filesDir, "roms"), repository, repository)
            assertEquals(6, scanner.scan(tree, {}).added)
            val game = repository.initialize().slots.last()!!
            val year = MetadataValue("2001", "Fixture", "one", "", "Original fixture", 1)
            repository.setMetadata(game.id, mapOf("year" to year)); repository.setFavorite(game.id, true)
            val before = FixtureTreeProvider.reads
            assertEquals(7, scanner.scan(tree, {}).unchanged); assertEquals(before, FixtureTreeProvider.reads)
            assertEquals(0, scanner.scan(tree, {}, verifyAll = true).unchanged); assertEquals(before + 7, FixtureTreeProvider.reads)
            File(treeFiles, "g5.gba").delete(); FixtureTreeProvider.failNested = true
            assertTrue(scanner.scan(tree, {}).failed > 0)
            assertFalse(repository.indexed(tree.toString()).single { it.gameId == game.id }.missing)
            FixtureTreeProvider.failNested = false
            scanner.scan(tree, {})
            assertTrue(repository.indexed(tree.toString()).single { it.gameId == game.id }.missing)
            assertTrue(repository.facts(game.id).cachedRom)
            assertTrue(repository.facts(game.id).sourceStatus.contains("missing"))
            val cache = File(context.filesDir, "roms/${game.id}.gba"); val original = cache.readBytes(); cache.delete()
            try { repository.gameRequest(game); fail("Missing original and copy must fail") } catch (_: Exception) { }
            File(treeFiles, "g5.gba").writeBytes(original)
            scanner.scan(tree, {})
            assertArrayEquals(original, cache.readBytes())
            assertFalse(repository.indexed(tree.toString()).single { it.gameId == game.id }.missing)
            assertEquals(year, repository.initialize().slots.last()!!.metadata["year"])
            assertTrue(repository.initialize().slots.last()!!.favorite)
            // Changing content under the same document name creates a separate hash, preserving the old game.
            File(treeFiles, "g5.gba").writeBytes(original + byteArrayOf(99))
            assertEquals(1, scanner.scan(tree, {}).added); assertEquals(7, repository.initialize().slots.size)
            val nested = Uri.parse("content://$FIXTURE_TREE/tree/nested")
            scanner.scan(nested, {})
            assertEquals(2, repository.directoryRecords().size)
            repository.forgetDirectory(tree.toString()); repository.forgetDirectory(nested.toString())
            assertTrue(repository.directories().isEmpty()); assertEquals(7, repository.initialize().slots.size)
            assertEquals(year, repository.initialize().slots.first { it?.id == game.id }!!.metadata["year"])
        } finally { FixtureTreeProvider.failNested = false; database.close(); context.deleteDatabase(name) }
    }
}
