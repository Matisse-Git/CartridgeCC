package dev.cartridge

import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.scraping.RetroBaseProvider
import dev.cartridge.scraping.ScrapePreferences
import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in live metadata/URL probe. Downloads no commercial ROMs, images, BIOS or test assets. */
class ProviderLiveTest {
    @Test fun realApiOffersGbaFlatLabelRegionChoices() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProvider") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(context.cacheDir, "live-provider-${UUID.randomUUID()}")
        try {
            val matches = withTimeout(60_000) { RetroBaseProvider(cache).search("Metroid Fusion") }
            val exactTitle = matches.firstOrNull { it.names.any { name -> name == "Metroid Fusion" } }
            assertNotNull("The real public API did not return the requested GBA title", exactTitle)
            assertEquals("Game Boy Advance", exactTitle!!.platform)
            assertTrue("The provider has no flat label for this title", exactTitle.artwork.any { it.isCaseArt })
            assertTrue(exactTitle.artwork.filter { it.isCaseArt }.map { it.region }.toSet().containsAll(setOf("eu", "jp", "us")))
            val ranked = ScrapePreferences("jp", "fr").rank(exactTitle)
            assertEquals("jp", ranked.artwork.first { it.isCaseArt }.region)
            assertEquals(exactTitle.artwork.toSet(), ranked.artwork.toSet())
            assertEquals(exactTitle.metadata.keys, ranked.metadata.keys)
            exactTitle.metadataTags.forEach { (field, tags) -> assertEquals(exactTitle.metadata[field]!!.size, tags.size) }
            val jpIndex = exactTitle.metadataTags["title"].orEmpty().indexOfFirst { it.region == "jp" }
            if (jpIndex >= 0) assertEquals(exactTitle.metadata["title"]!![jpIndex], ranked.metadata["title"]!!.first())
            // Each implemented console uses its own real provider route. This retrieves only
            // metadata and image URLs; artwork remains user-selected and is never bundled.
            for ((system, query) in listOf(GameSystem.GB to "Tetris", GameSystem.GBC to "Zelda", GameSystem.PS1 to "Crash Bandicoot")) {
                val additional = withTimeout(60_000) { RetroBaseProvider(cache).search(query, system = system) }
                assertTrue("No ${system.title} candidates from the public API", additional.isNotEmpty())
                assertTrue(additional.all { it.platform == system.title })
                assertTrue("No ${system.title} flat label URL in this sampled query", additional.any { game -> game.artwork.any { it.isCaseArt } })
            }
        } finally { cache.deleteRecursively() }
    }
}
