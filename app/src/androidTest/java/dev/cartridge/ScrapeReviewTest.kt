package dev.cartridge

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.library.*
import dev.cartridge.scraping.*
import dev.cartridge.session.CollectionScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Original fixture art only; production Room, controller-owned state and Filament label preview. */
class ScrapeReviewTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Provider : ArtworkProvider {
        override val name = "Original review fixture"
        var calls = 0
        val us = ArtworkCandidate("support-texture", "us", "https://example.invalid/us")
        val jp = us.copy(region = "jp", url = "https://example.invalid/jp")
        override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem): List<GameCandidate> {
            calls++
            return listOf(GameCandidate("original", "Orbit", "Game Boy Advance", listOf("Orbit"), linkedMapOf(
                "year" to listOf("2002"), "description" to listOf("English fixture", "Description originale")),
                listOf(us, jp), "https://example.invalid/record", "Original fixture", metadataTags = mapOf(
                    "description" to listOf(MetadataTags(language = "en"), MetadataTags(language = "fr")))))
        }
        override suspend fun image(candidate: ArtworkCandidate): ByteArray {
            val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (candidate.region == "jp") Color.BLUE else Color.RED)
            return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
        }
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun click(tag: String) {
        ui.onNodeWithTag("scrape-list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performClick()
    }
    @Test fun selectedHiddenGamePreferredPreviewMissingOnlyAndUndoSurviveRecreation() {
        val name = "m1-test-${UUID.randomUUID()}"
        val first = LibraryGame("1".repeat(64), "First fixture", "content://fixture/one")
        val second = LibraryGame("2".repeat(64), "Second fixture", "content://fixture/two")
        val localYear = MetadataValue("1999", "Local", second.id, "", "User provided", 1)
        val query = LibraryQuery(LibrarySort.Title, favoritesOnly = true)
        // An older build's filter: it must arrive as the Favorites shelf with the hidden flag cleared.
        val viewed = LibraryQuery(LibrarySort.Title)
        val seed = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking {
            val repository = RoomCollectionRepository(context, seed); repository.initialize()
            repository.add(first); repository.add(second); repository.setMetadata(second.id, mapOf("year" to localYear))
            repository.setFavorite(first.id, true); repository.select(0); repository.setQuery(query)
        }
        seed.close()
        val provider = Provider(); DebugCollectionActivity.testProvider = provider
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                ui.tool("scrape").performClick(); click("scrape-action-6")
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.preferences.region == "eu" }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.preferences.region == "jp" }
                click("scrape-action-1"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.preferences.language == "fr" }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                scenario.onActivity { assertEquals(ScrapePreferences("jp", "fr"), it.scraper.state.value.preferences) }
                ui.tool("scrape").performClick(); click("scrape-action-4")
                click("scrape-action-2"); click("scrape-target-1"); click("scrape-action-0")
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.searched == true }
                scenario.onActivity { assertEquals(listOf(second.id), it.scraper.state.value.entries.map { entry -> entry.game.id }) }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                click("review-label"); await(scenario) { it.coordinator.state.value.labelPreviewReady && !it.coordinator.state.value.busy }
                scenario.onActivity { assertEquals(second.id, it.coordinator.state.value.selectedGame!!.id); assertEquals(viewed, it.coordinator.state.value.query); assertEquals(Shelf.Favorites, it.coordinator.state.value.shelf) }
                // Recreation discards the unapplied draft, without saving its temporary view or focus.
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                scenario.onActivity { assertEquals(first.id, it.coordinator.state.value.selectedGame!!.id); assertEquals(viewed, it.coordinator.state.value.query); assertEquals(Shelf.Favorites, it.coordinator.state.value.shelf) }
                ui.tool("scrape").performClick(); click("scrape-action-3")
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.matches?.isNotEmpty() == true }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                click("review-label"); await(scenario) { it.coordinator.state.value.labelPreviewReady && !it.coordinator.state.value.busy }
                ui.onNodeWithTag("label-action-2").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.visible && it.coordinator.state.value.screen == CollectionScreen.Library }
                scenario.onActivity {
                    assertEquals(first.id, it.coordinator.state.value.selectedGame!!.id)
                    assertEquals("jp", it.scraper.state.value.current!!.game.label!!.provenance!!.region)
                }
                click("review-fill"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.game?.metadata?.containsKey("description") == true }
                scenario.onActivity {
                    assertEquals(localYear, it.scraper.state.value.current!!.game.metadata["year"])
                    assertEquals("Description originale", it.scraper.state.value.current!!.game.metadata["description"]!!.value)
                }
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                ui.tool("scrape").performClick(); click("scrape-action-3")
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.matches?.isNotEmpty() == true }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                click("review-undo"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.game?.canUndoEnrichment == false }
                scenario.onActivity {
                    val game = it.scraper.state.value.current!!.game
                    assertEquals(localYear, game.metadata["year"]); assertFalse(game.metadata.containsKey("description"))
                    assertEquals("jp", game.label!!.provenance!!.region); assertEquals(viewed, it.coordinator.state.value.query); assertEquals(Shelf.Favorites, it.coordinator.state.value.shelf)
                    assertEquals(1, provider.calls)
                }
            }
        } finally { DebugCollectionActivity.testProvider = null; DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }

    @Test fun schemaFiveUndoIsAtomicPersistentAndDoesNotRewindSavesHistoryOrSources() = runBlocking {
        val name = "review-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/5.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val id = "3".repeat(64)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) } }
            }
            schema.getJSONArray("setupQueries").let { setup -> repeat(setup.length()) { db.execSQL(setup.getString(it)) } }
            db.execSQL("INSERT INTO games(id,title,sourceUri,labelFit,labelQuarterTurns) VALUES (?, 'Original', 'content://old', 'Fit', 0)", arrayOf(id))
            db.execSQL("INSERT INTO slots VALUES(0, ?)", arrayOf(id)); db.execSQL("INSERT INTO collection_state(singleton,selectedSlot) VALUES(0,0)"); db.version = 5
        }
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7).build()
        var database = open()
        val save = File(context.filesDir, "saves/$id.state").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            var repository = RoomCollectionRepository(context, database); repository.initialize()
            val label = LabelRef("a".repeat(64), LabelFit.Crop, 1, "content://original-label")
            repository.setLabel(id, label)
            val local = MetadataValue("1999", "Local", id, "", "User provided", 1)
            repository.setMetadata(id, mapOf("year" to local))
            val proposed = local.copy(value = "2002", provider = "Fixture")
            assertEquals(1, repository.fillMissingMetadata(id, mapOf("year" to proposed, "developer" to proposed.copy(value = "Fixture Studio"))))
            repository.setFavorite(id, true); repository.recordPlay(id, 1500, 123)
            repository.add(LibraryGame(id, "Original", "content://moved"))
            val query = LibraryQuery(LibrarySort.Playtime, true, "original"); repository.setQuery(query)
            database.close(); database = open(); repository = RoomCollectionRepository(context, database)
            assertTrue(repository.initialize().slots[0]!!.canUndoEnrichment)
            assertEquals(0, repository.fillMissingMetadata(id, mapOf("year" to proposed))) // No-op must not replace the undo point.
            assertTrue(repository.undoEnrichment(id)); assertFalse(repository.undoEnrichment(id))
            val after = repository.initialize(); val game = after.slots[0]!!
            assertEquals(mapOf("year" to local), game.metadata); assertEquals(label, game.label)
            assertTrue(game.favorite); assertEquals(1500L, game.playMs); assertEquals(123L, game.lastPlayed)
            assertEquals("content://moved", game.sourceUri); assertEquals(query, after.query); assertArrayEquals(byteArrayOf(1, 2, 3), save.readBytes())
            val media = MediaSelection(label, "box-2D", "jp")
            repository.setMedia(id, media); assertTrue(repository.undoEnrichment(id)); assertTrue(repository.initialize().slots[0]!!.media.isEmpty())
            repository.setLabel(id, label.copy(quarterTurns = 2)); assertTrue(repository.undoEnrichment(id)); assertEquals(label, repository.initialize().slots[0]!!.label)
        } finally { database.close(); context.deleteDatabase(name); save.delete() }
    }

    @Test fun journalVersionOneStillResumesAndVersionTwoRetainsTagsAndReviewOutcomes() = runBlocking {
        val directory = File(context.filesDir, "review-journal-${UUID.randomUUID()}").apply { mkdirs() }
        val game = LibraryGame("4".repeat(64), "Original fixture", "content://fixture")
        try {
            File(directory, "manifest.json").writeText("""{"version":1,"index":0,"entries":[{"id":"${game.id}","title":"Original fixture","source":"content://fixture","query":"Original","searched":false,"corrected":true}]}""")
            val journal = FileScrapeJournal(directory)
            val legacy = journal.load()!!
            assertEquals(ScrapeOutcome.Pending, legacy.current!!.outcome); assertTrue(legacy.current!!.corrected)
            val candidate = GameCandidate("fixture", "Original", "Game Boy Advance", listOf("Original"), mapOf("description" to listOf("Original description")),
                emptyList(), "", "Original fixture", metadataTags = mapOf("description" to listOf(MetadataTags(language = "fr"))))
            journal.saveMatches(game.id, listOf(candidate))
            journal.save(legacy.copy(entries = listOf(legacy.current!!.copy(searched = true, outcome = ScrapeOutcome.Applied))))
            val restored = FileScrapeJournal(directory).load()!!
            assertEquals(ScrapeOutcome.Applied, restored.current!!.outcome); assertEquals(candidate, restored.current!!.matches.single())
            // A cached v1 candidate had no tags. It must remain usable with stable original ordering.
            val candidates = directory.listFiles()!!.single { it.name != "manifest.json" && it.extension == "json" }
            val array = org.json.JSONArray(candidates.readText()); array.getJSONObject(0).remove("tags"); candidates.writeText(array.toString())
            assertTrue(FileScrapeJournal(directory).load()!!.current!!.matches.single().metadataTags.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
