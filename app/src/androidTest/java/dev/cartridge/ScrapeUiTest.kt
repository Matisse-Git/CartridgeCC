package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.scraping.*
import dev.cartridge.session.CollectionScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Stubs network responses with original art, while exercising real Room/Filament and production UI. */
class ScrapeUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Provider : ArtworkProvider {
        override val name = "Original test provider"
        val red = ArtworkCandidate("support-texture", "us", "https://example.invalid/red")
        val blue = ArtworkCandidate("support-texture", "jp", "https://example.invalid/blue")
        val box = ArtworkCandidate("box-2D", "us", "https://example.invalid/box")
        val one = GameCandidate("one", "Orbit Field", "Game Boy Advance", listOf("Orbit Field"),
            linkedMapOf("title" to listOf("Orbit Field"), "year" to listOf("2002"), "developer" to listOf("Studio A")),
            listOf(red, box), "https://example.invalid/one", "Original fixture")
        val two = one.copy(id = "two", title = "Orbit Field Japan", metadata = linkedMapOf("title" to listOf("Orbit Field Japan"),
            "developer" to listOf("Studio B")), artwork = listOf(blue, box), sourceUrl = "https://example.invalid/two")
        override suspend fun search(query: String, rom: File?) = listOf(one, two)
        override suspend fun image(candidate: ArtworkCandidate): ByteArray {
            val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (candidate == blue) Color.BLUE else Color.RED)
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
        }
    }
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, condition: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = condition(it) }; ready }
    }
    @Test fun independentProviderChoicesPreviewApplyAndPersist() {
        val provider = Provider()
        DebugCollectionActivity.testProvider = provider
        val probe = File(context.filesDir, "cartridge-probe.gba")
        check(probe.isFile) { "Push the original diagnostic using Test-Device.ps1 first" }
        File(context.filesDir, "m1-fixtures").mkdirs()
        probe.copyTo(File(context.filesDir, "m1-fixtures/probe-one.gba"), overwrite = true)
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", "m1-test-${UUID.randomUUID()}")
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe-one.gba"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                ui.onNodeWithTag("scrape").performClick()
                ui.onNodeWithTag("scrape-action-0").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.current?.searched == true }
                ui.onNodeWithTag("scrape-action-0").performClick() // First provider match.
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                ui.onNodeWithTag("review-media").performClick()
                ui.onNodeWithTag("scrape-action-0").performClick() // Metadata, independently.
                ui.onNodeWithTag("metadata-year").performClick()
                ui.onNodeWithTag("scrape-action-3").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.coordinator.state.value.selectedGame?.metadata?.containsKey("year") == true }
                ui.onNodeWithTag("scrape-back").performClick()
                ui.onNodeWithTag("scrape-asset-0").performClick()
                await(scenario) { it.coordinator.state.value.labelPreviewReady && !it.coordinator.state.value.busy }
                ui.onNodeWithTag("label-action-2").performClick()
                await(scenario) { it.scraper.state.value.visible && !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library }
                scenario.onActivity {
                    val game = it.coordinator.state.value.selectedGame!!
                    assertEquals("one", game.label!!.provenance!!.recordId)
                    assertEquals("one", game.metadata["year"]!!.recordId)
                    assertFalse(game.metadata.containsKey("title"))
                }
                ui.onNodeWithTag("scrape-back").performClick() // Review.
                ui.onNodeWithTag("scrape-list").performScrollToNode(hasTestTag("review-match"))
                ui.onNodeWithTag("review-match").performClick()
                ui.onNodeWithTag("scrape-action-1").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.scraper.state.value.page == ScrapePage.Review }
                ui.onNodeWithTag("review-media").performClick()
                ui.onNodeWithTag("scrape-action-0").performClick()
                ui.onNodeWithTag("metadata-developer").performClick()
                ui.onNodeWithTag("scrape-action-2").performClick()
                await(scenario) { !it.scraper.state.value.busy && it.coordinator.state.value.selectedGame?.metadata?.get("developer")?.recordId == "two" }
                ui.onNodeWithTag("scrape-back").performClick()
                ui.onNodeWithTag("scrape-asset-1").performClick() // Box saved as extra, never as label.
                await(scenario) { !it.scraper.state.value.busy && it.coordinator.state.value.selectedGame?.media?.containsKey("box-2D") == true }
                scenario.recreate()
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity {
                    val game = it.coordinator.state.value.selectedGame!!
                    assertEquals("one", game.label!!.provenance!!.recordId)
                    assertEquals("one", game.metadata["year"]!!.recordId)
                    assertEquals("two", game.metadata["developer"]!!.recordId)
                    assertEquals("two", game.media["box-2D"]!!.image.provenance!!.recordId)
                    assertFalse(game.metadata.containsKey("title"))
                }
            }
        } finally { DebugCollectionActivity.testProvider = null; DebugCollectionActivity.closeDatabases() }
    }
}
