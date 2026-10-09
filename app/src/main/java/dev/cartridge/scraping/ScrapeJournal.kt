package dev.cartridge.scraping

/** Accepted choices remain in Room. This journal holds only the unfinished search/review queue. */
interface ScrapeJournal {
    suspend fun load(): ScrapeState?
    suspend fun save(state: ScrapeState)
    suspend fun saveMatches(gameId: String, matches: List<GameCandidate>)
    suspend fun matches(gameId: String): List<GameCandidate>?
    suspend fun reset()
}

class MemoryScrapeJournal : ScrapeJournal {
    private var saved: ScrapeState? = null
    private val candidates = mutableMapOf<String, List<GameCandidate>>()
    override suspend fun load() = saved
    override suspend fun save(state: ScrapeState) { saved = state.takeIf { it.entries.isNotEmpty() }?.copy(visible = false, busy = false, page = ScrapePage.Scope) }
    override suspend fun saveMatches(gameId: String, matches: List<GameCandidate>) { candidates[gameId] = matches }
    override suspend fun matches(gameId: String) = candidates[gameId]
    override suspend fun reset() { saved = null; candidates.clear() }
}
