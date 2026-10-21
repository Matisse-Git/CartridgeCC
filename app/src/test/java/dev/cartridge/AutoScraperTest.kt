package dev.cartridge

import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Market
import dev.cartridge.emulation.GameRequest
import dev.cartridge.library.*
import dev.cartridge.scraping.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** ADR 0029: the best match applied without a review, within what [FillPolicy] allows, and nothing lost when offline. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoScraperTest {
    private class Library(vararg games: LibraryGame) : CollectionRepository {
        override val snapshots = MutableStateFlow(LibrarySnapshot(games.toList()))
        override suspend fun initialize() = snapshots.value
        private fun change(id: String, transform: (LibraryGame) -> LibraryGame) {
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots.map { if (it?.id == id) transform(it) else it })
        }
        fun game(id: String) = snapshots.value.slots.first { it?.id == id }!!
        override suspend fun assign(slot: Int, game: LibraryGame) = Unit
        override suspend fun add(game: LibraryGame, restoreRemoved: Boolean) = false
        override suspend fun remove(gameId: String) = Unit
        override suspend fun restore(gameId: String, position: Int) = Unit
        override suspend fun select(slot: Int) = Unit
        override suspend fun setLabel(gameId: String, label: LabelRef?) = change(gameId) { it.copy(label = label) }
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) = change(gameId) { it.copy(metadata = it.metadata + values) }
        override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>) = 0
        override suspend fun undoEnrichment(gameId: String) = false
        override suspend fun setMedia(gameId: String, media: MediaSelection) = Unit
        override suspend fun setFavorite(gameId: String, favorite: Boolean) = Unit
        override suspend fun setQuery(query: LibraryQuery) = Unit
        override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) = Unit
        override suspend fun facts(gameId: String) = LibraryFacts(true, "Fixture", 0, 0)
        override suspend fun gameRequest(game: LibraryGame, disc: Int?) = GameRequest(File("unused.gba"), game.id)
    }
    private class Provider : ArtworkProvider {
        override val name = "Original fixture"
        val searches = mutableListOf<String>()
        val images = mutableListOf<String>()
        var offline = false
        override suspend fun search(query: String, rom: File?, system: GameSystem): List<GameCandidate> {
            if (offline) throw ProviderUnavailable("No connection")
            searches += query
            if (query == "Missing") return emptyList()
            return listOf(GameCandidate("record-$query", "$query proper", system.title, listOf("$query proper"),
                linkedMapOf("title" to listOf("$query proper"), "year" to listOf("1994")),
                listOf(ArtworkCandidate("box-2D", "us", "https://art.invalid/$query-us-front"), ArtworkCandidate("box-texture", "eu", "https://art.invalid/$query-eu-wrap"),
                    ArtworkCandidate("box-texture", "us", "https://art.invalid/$query-us-wrap"), ArtworkCandidate("ss", "us", "https://art.invalid/$query-shot")),
                "https://record.invalid/$query", "Fixture data"))
        }
        override suspend fun image(candidate: ArtworkCandidate): ByteArray { images += candidate.url; return candidate.url.toByteArray() }
    }
    private fun id(c: Char) = c.toString().repeat(64)
    private fun scraper(library: Library, provider: Provider, pending: PendingScrapes, scope: kotlinx.coroutines.CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        AutoScraper(provider, library, File("unused"), scope, pending, MemoryScrapePreferences(),
            importImage = { source, _ -> LabelRef(java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }, sourceUri = source) },
            ioDispatcher = dispatcher)

    @Test fun marketComesFromTheFileNameWithUsFirst() {
        assertEquals(Market.US, Market.of("Orbit Field (USA, Europe)"))
        assertEquals(Market.US, Market.of("Orbit Field (World)"))
        assertEquals(Market.US, Market.of("Orbit Field"))
        assertEquals(Market.EU, Market.of("Orbit Field (Europe) (En,Fr,De)"))
        assertEquals(Market.EU, Market.of("Orbit Field (UK)"))
        assertEquals(Market.EU, Market.of("Orbit Field (E) [!]"))
        assertEquals(Market.JP, Market.of("Orbit Field (Japan)"))
        assertEquals(Market.JP, Market.of("Orbit Field (J)"))
        assertEquals(Market.EU, Market.of("Orbit Field (Japan, Europe)"))
        assertEquals(Market.US, Market.of("Orbit Field (Beta)"))
    }

    @Test fun fillPolicyNeverReplacesTheOwnersChoiceUnlessAsked() {
        assertTrue(FillPolicy().replaces(null)); assertFalse(FillPolicy().replaces(true)); assertFalse(FillPolicy().replaces(false))
        val all = FillPolicy(FillMode.All)
        assertTrue(all.replaces(null)); assertTrue(all.replaces(true)); assertFalse(all.replaces(false))
        assertTrue(FillPolicy(FillMode.All, replaceManual = true).replaces(false))
    }

    @Test fun caseArtFollowsTheGamesRegionThenTheWholeCover() {
        val art = listOf(ArtworkCandidate("box-2D", "jp", "a"), ArtworkCandidate("box-texture", "us", "b"), ArtworkCandidate("box-2D", "eu", "c"),
            ArtworkCandidate("box-texture", "wor", "d"), ArtworkCandidate("support-texture", "eu", "e"), ArtworkCandidate("box-texture", "eu", "f"))
        assertEquals("f", AutoScraper.chooseArt(art, Market.EU)?.url)
        assertEquals("b", AutoScraper.chooseArt(art, Market.US)?.url)
        assertEquals("a", AutoScraper.chooseArt(art, Market.JP)?.url) // Its own region's front beats another region's wrap.
        assertNull(AutoScraper.chooseArt(listOf(ArtworkCandidate("support-texture", "us", "e")), Market.US))
    }

    @Test fun storedLinksNeverCarryTheLogin() {
        val url = "https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=dev&devpassword=secret&softname=x&ssid=me&sspassword=hunter2&systemeid=12&jeuid=7&media=box-texture(us)"
        val clean = ScreenScraperProvider.withoutLogin(url)
        assertEquals("https://neoclone.screenscraper.fr/api2/mediaJeu.php?systemeid=12&jeuid=7&media=box-texture(us)", clean)
        assertTrue(ScreenScraperProvider.offered(clean))
        assertFalse(ScreenScraperProvider.offered("https://screenscraper.fr.example.com/x.png"))
        assertFalse(ScreenScraperProvider.offered("http://www.screenscraper.fr/x.png"))
    }

    @Test fun addedGamesGetTheirRegionsArtAndMissingDetailsMarkedAutomatic() = runTest {
        val manual = MetadataValue("Owner's year", "Owner", "", "", "", 1)
        val library = Library(LibraryGame(id('a'), "Orbit Field (Europe)", "content://a", metadata = mapOf("year" to manual)))
        val provider = Provider(); val pending = MemoryPendingScrapes()
        val scraper = scraper(library, provider, pending, backgroundScope, StandardTestDispatcher(testScheduler))
        scraper.added(listOf(id('a'))); runCurrent()
        val game = library.game(id('a'))
        assertEquals(listOf("Orbit Field"), provider.searches)
        assertEquals(listOf("https://art.invalid/Orbit Field-eu-wrap"), provider.images)
        assertEquals("box-texture", game.label?.provenance?.type); assertTrue(game.label!!.provenance!!.automatic)
        assertEquals("Orbit Field proper", game.metadata["title"]?.value); assertTrue(game.metadata["title"]!!.automatic)
        assertEquals(manual, game.metadata["year"]) // Fill missing keeps the owner's value.
        assertEquals(emptySet<String>(), pending.ids)
        assertEquals(1, scraper.status.value.applied); assertFalse(scraper.status.value.running)
    }

    @Test fun fillAllReplacesAutomaticChoicesOnlyAndSkipsDownloadsItCannotApply() = runTest {
        val manualArt = LabelRef(id('e'), provenance = AssetProvenance("Owner", "", "box-2D", "us", "", "", 1))
        val autoYear = MetadataValue("1990", "Fixture", "", "", "", 1, automatic = true)
        val ownerTitle = MetadataValue("My title", "Owner", "", "", "", 1)
        val library = Library(LibraryGame(id('a'), "Orbit Field (USA)", "content://a", manualArt, mapOf("year" to autoYear, "title" to ownerTitle)))
        val provider = Provider()
        val scraper = scraper(library, provider, MemoryPendingScrapes(), backgroundScope, StandardTestDispatcher(testScheduler))
        scraper.scrape(listOf(id('a')), FillPolicy(FillMode.All)); runCurrent()
        var game = library.game(id('a'))
        assertEquals("1994", game.metadata["year"]?.value); assertEquals(ownerTitle, game.metadata["title"]); assertEquals(manualArt, game.label)
        assertTrue(provider.images.isEmpty()) // The owner's art may not be replaced, so it is not downloaded.
        scraper.scrape(listOf(id('a')), FillPolicy(FillMode.All, replaceManual = true)); runCurrent()
        game = library.game(id('a'))
        assertEquals("Orbit Field proper", game.metadata["title"]?.value)
        assertEquals("https://art.invalid/Orbit Field-us-wrap", game.label?.sourceUri)
    }

    @Test fun anUnreachableProviderKeepsEveryGamePendingForTheNextAdd() = runTest {
        val library = Library(LibraryGame(id('a'), "One", "content://a"), LibraryGame(id('b'), "Two", "content://b"), LibraryGame(id('c'), "Missing", "content://c"))
        val provider = Provider().apply { offline = true }; val pending = MemoryPendingScrapes()
        val scraper = scraper(library, provider, pending, backgroundScope, StandardTestDispatcher(testScheduler))
        scraper.added(listOf(id('a'), id('b'))); runCurrent()
        assertEquals(setOf(id('a'), id('b')), pending.ids)
        assertEquals("No connection", scraper.status.value.problem); assertFalse(scraper.status.value.running)
        assertNull(library.game(id('a')).label)
        provider.offline = false
        scraper.added(emptyList()); runCurrent()
        assertTrue(provider.searches.isEmpty()) // Nothing was added, so nothing is retried.
        scraper.added(listOf(id('c'))); runCurrent()
        assertEquals(setOf("One", "Two", "Missing"), provider.searches.toSet())
        assertEquals(emptySet<String>(), pending.ids)
        assertEquals(2, scraper.status.value.applied); assertEquals(1, scraper.status.value.notFound); assertNull(scraper.status.value.problem)
    }
}
