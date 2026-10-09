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
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) {
            writes++; accepted += values
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots.map { if (it?.id == gameId) it.copy(metadata = it.metadata + values) else it })
        }
        override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>): Int {
            val current = snapshots.value.slots.first { it?.id == gameId }!!
            val missing = values.filterKeys { current.metadata[it]?.value.isNullOrBlank() }
            if (missing.isNotEmpty()) setMetadata(gameId, missing)
            return missing.size
        }
        override suspend fun undoEnrichment(gameId: String) = false
        override suspend fun setMedia(gameId: String, media: MediaSelection) { writes++ }
        override suspend fun setFavorite(gameId: String, favorite: Boolean) = Unit
        override suspend fun setQuery(query: LibraryQuery) { snapshots.value = snapshots.value.copy(query = query) }
        override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) = Unit
        override suspend fun facts(gameId: String) = LibraryFacts(true, "Fixture", 0, 0)
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
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> error("No preview") }
        scraper.open(listOf(repository.one, repository.two, repository.one), repository.one)
        scraper.chooseScope(false); runCurrent()
        assertEquals(1, provider.calls)
        assertEquals(2, scraper.state.value.entries.size)
        assertTrue(scraper.state.value.entries[0].searched)
        assertFalse(scraper.state.value.entries[1].searched)
        assertEquals(0, repository.writes)
        assertNotNull(repository.snapshots.value.slots[0]!!.label)
        scraper.next(); runCurrent(); assertEquals(2, provider.calls); assertEquals(repository.two.id, scraper.state.value.current!!.game.id)
        scraper.destroy()
    }
    @Test fun cancellationLeavesPendingTitlesRetryableAndAcceptsOnlyCheckedFields() = runTest {
        val repository = Repository(); val provider = Provider().apply { gate = CompletableDeferred() }
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> error("No preview") }
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.chooseScope(false); runCurrent(); scraper.cancel(); runCurrent()
        assertFalse(scraper.state.value.busy)
        assertFalse(scraper.state.value.visible)
        assertEquals(0, repository.writes)
        provider.gate = null
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.retryRemaining(); runCurrent()
        assertTrue(scraper.state.value.current!!.searched)
        scraper.chooseCandidate(0); runCurrent(); scraper.metadata(); scraper.toggle("year"); scraper.applyMetadata(); runCurrent()
        assertEquals(setOf("year"), repository.accepted.keys)
        assertEquals(1, repository.writes)
        assertNotNull(repository.snapshots.value.slots[0]!!.label)
        scraper.destroy()
    }
    @Test fun recreatedQueueLoadsCompletedCandidatesAndResumesOnlyPendingTitles() = runTest {
        val repository = Repository(); val provider = Provider(); val journal = MemoryScrapeJournal()
        val scraper = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, journal, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> }
        scraper.open(listOf(repository.one, repository.two), repository.one)
        scraper.chooseScope(false); runCurrent()
        assertEquals(1, provider.calls)
        assertTrue(scraper.state.value.entries[1].matches.isEmpty()) // Noncurrent candidates are journal-only.
        scraper.next(); runCurrent()
        assertFalse(scraper.state.value.current!!.matches.isEmpty())
        scraper.destroy(); runCurrent()
        val reopened = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope, journal, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> }
        runCurrent(); reopened.open(listOf(repository.one, repository.two), repository.one)
        reopened.resumeBatch(); runCurrent()
        assertEquals(1, reopened.state.value.index)
        assertEquals(repository.two.title, reopened.state.value.current!!.matches[0].title)
        assertEquals(2, provider.calls)
        assertEquals(0, repository.writes)
        reopened.destroy()
    }
    @Test fun selectedSubsetIsSavedBeforeSearchAndResumeReadsAcceptedRoomChoices() = runTest {
        val repository = Repository(); val journal = MemoryScrapeJournal()
        val provider = object : ArtworkProvider {
            override val name = "Original selected fixture"
            var calls = 0
            override suspend fun search(query: String, rom: File?): List<GameCandidate> {
                calls++
                assertEquals(listOf(repository.two.id), journal.load()!!.entries.map { it.game.id })
                return listOf(GameCandidate("match", query, "Game Boy Advance", listOf(query),
                    linkedMapOf("year" to listOf("2002"), "developer" to listOf("Fixture Studio")), emptyList(), "", "Original fixture"))
            }
            override suspend fun image(candidate: ArtworkCandidate) = error("No preview")
        }
        fun coordinator() = ScrapeCoordinator(provider, repository, LabelStore(File("unused")), File("unused"), backgroundScope,
            journal, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ -> error("No preview") }
        val scraper = coordinator(); runCurrent()
        scraper.open(listOf(repository.one, repository.two, repository.one), repository.one)
        scraper.targets(); scraper.pickAll(false); scraper.pick(repository.two.id); scraper.beginPicked(); runCurrent()
        assertEquals(1, provider.calls)
        scraper.destroy(); runCurrent()
        // Accepted data can change after the journal snapshot, e.g. a local edit while the batch is closed.
        val manual = MetadataValue("1999", "Local", "", "", "User provided", 1)
        repository.setMetadata(repository.two.id, mapOf("year" to manual)); repository.writes = 0; repository.accepted = emptyMap()
        val resumed = coordinator(); runCurrent(); resumed.open(listOf(repository.one, repository.two), repository.one)
        resumed.resumeBatch(); runCurrent(); resumed.chooseCandidate(0); runCurrent(); resumed.fillMissingMetadata(); runCurrent()
        assertEquals(setOf("developer"), repository.accepted.keys)
        assertEquals(manual, resumed.state.value.current!!.game.metadata["year"])
        assertEquals(ScrapeOutcome.Applied, journal.load()!!.current!!.outcome)
        assertEquals(1, provider.calls)
        resumed.fillMissingMetadata(); runCurrent(); assertEquals(1, repository.writes)
        resumed.next(); runCurrent(); assertNull(journal.load())
        resumed.destroy()
    }

}
