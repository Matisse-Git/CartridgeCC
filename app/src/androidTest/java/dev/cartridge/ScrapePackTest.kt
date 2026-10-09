package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.library.*
import dev.cartridge.scraping.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScrapePackTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "5".repeat(64)
    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
    }
    private fun pack(checksum: Boolean = true, version: Int = 1): ByteArray {
        val bytes = image(); val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val metadata = JSONObject().put("year", JSONArray().put(JSONObject().put("value", "2002")))
            .put("developer", JSONArray().put(JSONObject().put("value", "Original studio")))
        val media = JSONObject().put("asset", "blue").put("type", "support-texture").put("region", "us").put("sourceUrl", "https://example.invalid/original")
        val match = JSONObject().put("id", "original").put("title", "Orbit Field").put("platform", "Game Boy Advance")
            .put("metadata", metadata).put("media", JSONArray().put(media))
        val game = JSONObject().put("romSha256", hash).put("matches", JSONArray().put(match))
        val asset = JSONObject().put("id", "blue").put("path", "media/blue.png").put("sha256", if (checksum) sha else "0".repeat(64))
        val root = JSONObject().put("format", "project-cartridge.scrape-pack").put("version", version)
            .put("provider", "Original pack fixture").put("license", "Original fixture")
            .put("assets", JSONArray().put(asset)).put("games", JSONArray().put(game))
        return ByteArrayOutputStream().use { output -> ZipOutputStream(output).use { zip ->
            listOf("pack.json" to root.toString().toByteArray(), "media/blue.png" to bytes).forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }; output.toByteArray() }
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun click(tag: String) { ui.onNodeWithTag("scrape-list").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performClick() }
    @Test fun offlineSafPackReviewPreviewApplyResumeAndIndependentMetadataPersist() {
        val name = "m1-test-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        val local = MetadataValue("1999", "Local", hash, "", "User provided", 1)
        runBlocking { val repository = RoomCollectionRepository(context, database); repository.initialize(); repository.add(LibraryGame(hash, "Original ROM", "content://fixture")); repository.setMetadata(hash, mapOf("year" to local)) }
        database.close()
        File(context.filesDir, "m1-fixtures").mkdirs(); File(context.filesDir, "m1-fixtures/original-pack.zip").writeBytes(pack())
        val noNetwork = object : ArtworkProvider {
            override val name = "Network must not be used"
            override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem): List<GameCandidate> = error("Pack review must stay offline")
            override suspend fun image(candidate: ArtworkCandidate): ByteArray = error("Pack images must stay offline")
        }
        DebugCollectionActivity.testProvider = noNetwork
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                ui.tool("scrape").performClick()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/original-pack.zip"))))
                    click("scrape-action-7")
                } finally { Intents.release() }
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.local == true }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                click("review-label"); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.labelPreviewReady }
                ui.onNodeWithTag("label-action-2").performClick(); await(scenario) { it.scraper.state.value.visible && !it.scraper.state.value.busy }
                click("review-fill"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.game?.metadata?.containsKey("developer") == true }
                scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                ui.tool("scrape").performClick(); click("scrape-action-3")
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.matches?.isNotEmpty() == true }
                scenario.onActivity {
                    val game = it.coordinator.state.value.selectedGame!!
                    assertEquals(local, game.metadata["year"]); assertEquals("Original pack fixture", game.metadata["developer"]!!.provider)
                    assertEquals("Original pack fixture", game.label!!.provenance!!.provider)
                    assertEquals("https://example.invalid/original", game.label.provenance!!.url)
                    assertTrue(it.scraper.state.value.current!!.local)
                }
                click("scrape-action-0"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                click("review-undo"); await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.game?.canUndoEnrichment == false }
                scenario.onActivity { assertFalse(it.scraper.state.value.current!!.game.metadata.containsKey("developer")); assertNotNull(it.scraper.state.value.current!!.game.label) }
            }
        } finally { DebugCollectionActivity.testProvider = null; DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    @Test fun alteredImagesAndUnknownVersionsLeaveNoRetainedPack() = runBlocking {
        val directory = File(context.filesDir, "pack-guard-${UUID.randomUUID()}")
        try {
            val store = ScrapePackStore(directory)
            for (bytes in listOf(pack(checksum = false), pack(version = 99))) {
                try { store.importStream { bytes.inputStream() }; fail("Invalid pack must fail before any accepted choices") }
                catch (_: IllegalArgumentException) { }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
            val valid = store.importStream { pack().inputStream() }
            assertEquals(hash, valid.games.keys.single())
            assertEquals("Original pack fixture", valid.games[hash]!!.single().sourceName)
            try { store.image("cartridge-pack://" + "a".repeat(32) + "/../escape.image"); fail("Media references cannot escape") }
            catch (_: IllegalArgumentException) { }
        } finally { directory.deleteRecursively() }
    }
}
