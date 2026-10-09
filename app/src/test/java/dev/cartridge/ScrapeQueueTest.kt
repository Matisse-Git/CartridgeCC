package dev.cartridge

import dev.cartridge.emulation.GameRequest
import dev.cartridge.library.*
import dev.cartridge.scraping.*
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ScrapeQueueTest {
    private class Repository : CollectionRepository {
        val one = LibraryGame("a".repeat(64), "One", "content://one", LabelRef("c".repeat(64)))
        val two = LibraryGame("b".repeat(64), "Two", "content://two")
        override val snapshots = MutableStateFlow(LibrarySnapshot(listOf(one, two, one)))
        var writes = 0
        var accepted = emptyMap<String, MetadataValue>()
        override suspend fun initialize() = snapshots.value
        override suspend fun assign(slot: Int, game: LibraryGame) = Unit
        override suspend fun add(game: LibraryGame, restoreRemoved: Boolean) = false
        override suspend fun remove(gameId: String) = Unit
        override suspend fun restore(gameId: String, position: Int) = Unit
        override suspend fun select(slot: Int) = Unit
        override suspend fun setLabel(gameId: String, label: LabelRef?) { writes++ }
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) { writes++; accepted += values }
        override suspend fun setMedia(gameId: String, media: MediaSelection) { writes++ }
        override suspend fun gameRequest(game: LibraryGame) = GameRequest(File("unused.gba"), game.id)
    }
    private class Provider : ArtworkProvider {
        override val name = "Original fixture"
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun search(query: String, rom: File?): List<GameCandidate> {
            calls++; gate?.await()
            return listOf(GameCandidate("one", query, "Game Boy Advance", listOf(query),
                linkedMapOf("title" to listOf(query), "year" to listOf("2002")), emptyList(), "https://example.invalid", "Original fixture"))
        }
        override suspend fun image(candidate: ArtworkCandidate) = error("No images in queue tests")
    }
    @Test fun consoleQueueDeduplicatesGamesAndNeverOverwritesAcceptedChoices() = runTest {
        val repository = Repository(); val provider = Provider()
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope) { _, _ -> error("No preview") }
        scraper.open(listOf(repository.one, repository.two, repository.one), repository.one)
        scraper.chooseScope(false); runCurrent()
        assertEquals(2, provider.calls)
        assertEquals(2, scraper.state.value.entries.size)
        assertTrue(scraper.state.value.entries.all { it.searched })
        assertEquals(0, repository.writes)
        assertNotNull(repository.snapshots.value.slots[0]!!.label)
        scraper.next(); runCurrent(); assertEquals(repository.two.id, scraper.state.value.current!!.game.id)
        scraper.destroy()
    }
    @Test fun cancellationLeavesPendingTitlesRetryableAndAcceptsOnlyCheckedFields() = runTest {
        val repository = Repository(); val provider = Provider().apply { gate = CompletableDeferred() }
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope) { _, _ -> error("No preview") }
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.chooseScope(false); runCurrent(); scraper.cancel(); runCurrent()
        assertFalse(scraper.state.value.busy)
        assertFalse(scraper.state.value.visible)
        assertEquals(0, repository.writes)
        provider.gate = null
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.retryRemaining(); runCurrent()
        assertTrue(scraper.state.value.entries.all { it.searched })
        scraper.chooseCandidate(0); scraper.metadata(); scraper.toggle("year"); scraper.applyMetadata(); runCurrent()
        assertEquals(setOf("year"), repository.accepted.keys)
        assertEquals(1, repository.writes)
        assertNotNull(repository.snapshots.value.slots[0]!!.label)
        scraper.destroy()
    }
    @Test fun recreatedQueueLoadsCompletedCandidatesAndResumesOnlyPendingTitles() = runTest {
        val repository = Repository(); val provider = Provider(); val journal = MemoryScrapeJournal()
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, journal) { _, _ -> }
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.chooseScope(false); runCurrent()
        assertEquals(2, provider.calls)
        assertTrue(scraper.state.value.entries[1].matches.isEmpty()) // Noncurrent candidates are journal-only.
        scraper.next(); runCurrent()
        assertFalse(scraper.state.value.current!!.matches.isEmpty())
        scraper.destroy(); runCurrent()
        val reopened = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, journal) { _, _ -> }
        runCurrent(); reopened.open(listOf(repository.one, repository.two), repository.one)
        reopened.resumeBatch(); runCurrent()
        assertEquals(1, reopened.state.value.index)
        assertEquals(repository.two.title, reopened.state.value.current!!.matches[0].title)
        assertEquals(2, provider.calls)
        assertEquals(0, repository.writes)
        reopened.destroy()
    }
}
