package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.scraping.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ScrapeResumeTest {
    @get:Rule val ui = createEmptyComposeRule()
    private class Provider(private val beforeFirstRequest: suspend () -> Unit) : ArtworkProvider {
        override val name = "Original resumable fixture"
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = CompletableDeferred()
        override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem): List<GameCandidate> {
            calls += query
            if (calls.size == 1) beforeFirstRequest()
            if (query.contains("two")) gate?.await()
            return listOf(GameCandidate(query, query, "Game Boy Advance", listOf(query), linkedMapOf("year" to listOf("2002")),
                emptyList(), "https://example.invalid/original", "Original fixture"))
        }
        override suspend fun image(candidate: ArtworkCandidate) = error("No artwork needed for this queue persistence test")
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, predicate: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = predicate(it) }; ready }
    }
    private fun choose(filename: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://dev.cartridge.fixture/$filename"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    @Test fun cancelledBatchReopensFromDiskWithoutRepeatingCompletedSearches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = File(context.filesDir, "cartridge-probe.gba").readBytes()
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        File(fixtures, "probe-one.gba").writeBytes(probe); File(fixtures, "probe-two.gba").writeBytes(probe + byteArrayOf(7))
        val databaseName = "m1-test-${UUID.randomUUID()}"
        val provider = Provider {
            val published = FileScrapeJournal(File(context.filesDir, "$databaseName-scrape")).load()
            assertNotNull("The batch must be saved before its first network request", published)
            assertEquals(2, published!!.entries.size)
            assertTrue(published.entries.none { it.searched })
        }
        DebugCollectionActivity.testProvider = provider
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", databaseName)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                choose("probe-one.gba")
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                choose("probe-two.gba")
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                ui.onNodeWithTag("scrape").performClick(); ui.onNodeWithTag("scrape-action-2").performClick() // All games.
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.searched == true }
                assertEquals(1, provider.calls.size) // Review is available before searching the second title.
                ui.onNodeWithTag("scrape-action-0").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                ui.onNodeWithTag("review-fields").performClick()
                ui.onNodeWithTag("metadata-year").performClick(); ui.onNodeWithTag("scrape-action-1").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.coordinator.state.value.slots[0]!!.metadata["year"]?.value == "2002" }
                ui.onNodeWithTag("scrape-back").performClick()
                ui.onNodeWithTag("review-next").performClick()
                await(scenario) { it.scraper.state.value.busy && it.scraper.state.value.index == 1 && provider.calls.size == 2 }
                ui.onNodeWithTag("scrape-back").performClick()
                await(scenario) { !it.scraper.state.value.busy && !it.scraper.state.value.visible }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                scenario.onActivity {
                    assertEquals(2, it.scraper.state.value.entries.size)
                    assertTrue(it.scraper.state.value.entries[0].searched)
                    assertFalse(it.scraper.state.value.entries[1].searched)
                    provider.gate = null
                }
                ui.onNodeWithTag("scrape").performClick(); ui.onNodeWithTag("scrape-action-3").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.entries.all { entry -> entry.searched } }
                scenario.onActivity {
                    assertEquals(1, provider.calls.count { it.contains("one") })
                    assertEquals(2, provider.calls.count { it.contains("two") })
                    assertTrue(it.scraper.state.value.entries[0].matches.isEmpty())
                }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.scraper.state.value.ready }
                ui.onNodeWithTag("scrape").performClick(); ui.onNodeWithTag("scrape-action-3").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current!!.matches.isNotEmpty() }
                scenario.onActivity {
                    assertEquals(1, it.scraper.state.value.index)
                    assertEquals(3, provider.calls.size)
                    assertEquals("2002", it.coordinator.state.value.slots[0]!!.metadata["year"]!!.value)
                }
            }
        } finally { DebugCollectionActivity.testProvider = null; DebugCollectionActivity.closeDatabases() }
    }
}
