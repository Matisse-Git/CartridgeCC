package dev.cartridge.scraping

import dev.cartridge.library.*
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ScrapePage { Scope, Matches, Artwork, Metadata }
data class ScrapeEntry(val game: LibraryGame, val query: String, val matches: List<GameCandidate> = emptyList(),
    val error: String? = null, val searched: Boolean = false, val corrected: Boolean = false)
data class ScrapeState(val visible: Boolean = false, val page: ScrapePage = ScrapePage.Scope,
    val entries: List<ScrapeEntry> = emptyList(), val index: Int = 0, val candidate: GameCandidate? = null,
    val busy: Boolean = false, val focus: Int = 0, val progress: String? = null, val message: String? = null,
    val metadataChoices: Map<String, Int> = emptyMap(), val checkedFields: Set<String> = emptySet(), val ready: Boolean = true) {
    val current get() = entries.getOrNull(index)
}

/** Reviewed GBA queue. Provider results never auto-overwrite accepted choices. */
class ScrapeCoordinator(
    val provider: ArtworkProvider,
    private val repository: CollectionRepository,
    private val images: LabelStore,
    private val roms: File,
    private val scope: CoroutineScope,
    private val journal: ScrapeJournal = MemoryScrapeJournal(),
    private val preview: (LibraryGame, LabelRef) -> Unit,
) {
    private val mutableState = MutableStateFlow(ScrapeState(ready = false))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var available = emptyList<LibraryGame>()
    private var selected: LibraryGame? = null
    private var previewPending = false
    private var destroyed = false
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val loaded = journal.load()?.copy(ready = true) ?: ScrapeState()
                mutableState.value = loaded.copy(visible = state.value.visible)
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = ScrapeState(message = "Cannot restore the saved scrape batch: ${failure.message}. Start a new scope.")
            }
        }
    }

    fun open(games: List<LibraryGame>, focused: LibraryGame?) {
        available = games.distinctBy { it.id }; selected = focused
        mutableState.value = state.value.copy(visible = true, page = ScrapePage.Scope, focus = 0)
    }
    fun chooseScope(single: Boolean) {
        if (state.value.busy || !state.value.ready) return
        val targets = if (single) listOfNotNull(selected) else available
        if (targets.isEmpty()) { message("Add a GBA game before scraping"); return }
        mutableState.value = ScrapeState(visible = true, page = ScrapePage.Matches,
            entries = targets.map { ScrapeEntry(it, cleanTitle(it.title)) })
        action { journal.reset(); journal.save(state.value); searchPending() }
    }
    fun query(value: String) {
        if (state.value.busy) return
        updateEntry { it.copy(query = value.take(180), searched = false, matches = emptyList(), error = null, corrected = true) }
        persist()
    }
    fun searchCurrent() = action {
        val entry = state.value.current ?: return@action
        val matches = provider.search(entry.query) // Explicit title correction must not reapply a ROM hash guess.
        journal.saveMatches(entry.game.id, matches)
        updateEntry { it.copy(matches = matches, error = null, searched = true) }
        mutableState.value = state.value.copy(page = ScrapePage.Matches, focus = 0, candidate = null)
    }
    private fun searchQueue() = action { searchPending() }
    private suspend fun searchPending() {
        for (index in state.value.entries.indices) {
            val entry = state.value.entries[index]
            if (entry.searched) continue
            mutableState.value = state.value.copy(progress = "Searching ${index + 1} / ${state.value.entries.size}: ${entry.game.title}")
            val result = try {
                val file = if (entry.corrected) null else File(roms, "${entry.game.id}.gba").takeIf(File::isFile)
                val matches = provider.search(entry.query, file)
                journal.saveMatches(entry.game.id, matches)
                // Other titles' candidates stay on disk, not in a growing batch-wide RAM list.
                entry.copy(matches = if (index == state.value.index) matches else emptyList(), searched = true, error = null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                entry.copy(error = failure.message ?: "Search failed", searched = true)
            }
            mutableState.value = state.value.copy(entries = state.value.entries.toMutableList().also { it[index] = result })
            journal.save(state.value)
        }
    }
    fun resumeBatch() = action {
        require(state.value.entries.isNotEmpty()) { "There is no saved batch to resume" }
        mutableState.value = state.value.copy(page = ScrapePage.Matches, focus = 0, candidate = null)
        restoreCurrentCandidates()
        searchPending()
    }
    private suspend fun restoreCurrentCandidates() {
        val entry = state.value.current ?: return
        if (entry.searched && entry.error == null) {
            val matches = journal.matches(entry.game.id)
            updateEntry { it.copy(matches = matches.orEmpty(), searched = matches != null) }
        }
    }
    fun retryRemaining() {
        if (state.value.busy) return
        mutableState.value = state.value.copy(entries = state.value.entries.map { if (it.error != null) it.copy(searched = false) else it })
        searchQueue()
    }
    fun chooseCandidate(index: Int) {
        val candidate = state.value.current?.matches?.getOrNull(index) ?: return
        mutableState.value = state.value.copy(page = ScrapePage.Artwork, candidate = candidate, focus = 0, message = null)
    }
    fun metadata() {
        val candidate = state.value.candidate ?: return
        mutableState.value = state.value.copy(page = ScrapePage.Metadata, focus = 0,
            checkedFields = emptySet(), metadataChoices = candidate.metadata.keys.associateWith { 0 }, message = null)
    }
    fun toggle(field: String) {
        if (state.value.busy) return
        val checked = state.value.checkedFields
        mutableState.value = state.value.copy(checkedFields = if (field in checked) checked - field else checked + field)
    }
    fun variant(field: String) {
        if (state.value.busy) return
        val values = state.value.candidate?.metadata?.get(field) ?: return
        val next = ((state.value.metadataChoices[field] ?: 0) + 1) % values.size
        mutableState.value = state.value.copy(metadataChoices = state.value.metadataChoices + (field to next))
    }
    fun applyMetadata() = action {
        val current = state.value
        val candidate = current.candidate ?: return@action
        val game = current.current?.game ?: return@action
        val accepted = current.checkedFields.associateWith { field ->
            MetadataValue(checkNotNull(candidate.metadata[field])[current.metadataChoices[field] ?: 0], provider.name,
                candidate.id, candidate.sourceUrl, candidate.license, System.currentTimeMillis())
        }
        require(accepted.isNotEmpty()) { "Choose at least one metadata field" }
        repository.setMetadata(game.id, accepted)
        message("Selected metadata applied. Other fields and the label were preserved.")
    }
    fun artwork(index: Int) = action {
        val candidate = state.value.candidate ?: return@action
        val game = state.value.current?.game ?: return@action
        val asset = candidate.artwork.getOrNull(index) ?: return@action
        val bytes = provider.image(asset)
        val label = withContext(Dispatchers.IO) { images.importStream(asset.url) { bytes.inputStream() } }.copy(
            provenance = AssetProvenance(provider.name, candidate.id, asset.type, asset.region, asset.url,
                "Artwork rights remain with their owners; provider grants no image licence. Data: ${candidate.license}", System.currentTimeMillis()))
        if (asset.isLabel) {
            previewPending = true
            mutableState.value = state.value.copy(visible = false)
            preview(game, label)
        } else {
            repository.setMedia(game.id, MediaSelection(label, asset.type, asset.region))
            message("${asset.type} (${asset.region}) saved as additional media. The cartridge label was preserved.")
        }
    }
    fun previewFinished() {
        if (!previewPending) return
        previewPending = false
        mutableState.value = state.value.copy(visible = true, message = "Review another asset or move to the next title.")
    }
    fun next() {
        action {
            if (state.value.index + 1 < state.value.entries.size) {
                mutableState.value = state.value.copy(index = state.value.index + 1, page = ScrapePage.Matches,
                    candidate = null, focus = 0, message = null, entries = state.value.entries.map { it.copy(matches = emptyList()) })
                restoreCurrentCandidates()
                if (state.value.current?.searched == false) searchPending()
            } else {
                journal.reset()
                mutableState.value = state.value.copy(visible = false, page = ScrapePage.Scope, entries = emptyList(), candidate = null, focus = 0)
            }
        }
    }
    fun back() {
        if (state.value.busy) { cancel(); return }
        mutableState.value = when (state.value.page) {
            ScrapePage.Scope -> state.value.copy(visible = false)
            ScrapePage.Matches -> state.value.copy(page = ScrapePage.Scope, focus = 0)
            ScrapePage.Artwork -> state.value.copy(page = ScrapePage.Matches, candidate = null, focus = 0)
            ScrapePage.Metadata -> state.value.copy(page = ScrapePage.Artwork, focus = 0)
        }
    }
    fun navigate(delta: Int) { if (!state.value.busy) mutableState.value = state.value.copy(focus = (state.value.focus + delta).coerceIn(0, actionCount() - 1)) }
    fun select() {
        if (state.value.busy) return
        val focus = state.value.focus
        when (state.value.page) {
            ScrapePage.Scope -> when (focus) { 0 -> chooseScope(true); 1, 2 -> chooseScope(false); 3 -> resumeBatch(); else -> back() }
            ScrapePage.Matches -> when {
                focus < (state.value.current?.matches?.size ?: 0) -> chooseCandidate(focus)
                focus == (state.value.current?.matches?.size ?: 0) -> searchCurrent()
                focus == (state.value.current?.matches?.size ?: 0) + 1 -> next()
                else -> back()
            }
            ScrapePage.Artwork -> when {
                focus == 0 -> metadata()
                focus <= (state.value.candidate?.artwork?.size ?: 0) -> artwork(focus - 1)
                focus == (state.value.candidate?.artwork?.size ?: 0) + 1 -> next()
                else -> back()
            }
            ScrapePage.Metadata -> {
                val fields = state.value.candidate?.metadata?.keys?.toList().orEmpty()
                if (focus < fields.size) toggle(fields[focus])
                else if (focus == fields.size) applyMetadata() else back()
            }
        }
    }
    private fun actionCount() = when (state.value.page) {
        ScrapePage.Scope -> 5
        ScrapePage.Matches -> (state.value.current?.matches?.size ?: 0) + 3
        ScrapePage.Artwork -> (state.value.candidate?.artwork?.size ?: 0) + 3
        ScrapePage.Metadata -> (state.value.candidate?.metadata?.size ?: 0) + 2
    }
    fun cancel() { mutableState.value = state.value.copy(visible = false); job?.cancel(); persist() }
    fun destroy() { destroyed = true; previewPending = false; persist(); job?.cancel() }
    private fun persist() {
        if (!state.value.ready) return
        val snapshot = state.value
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { journal.save(snapshot) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (!destroyed) message("Cannot save scrape progress: ${failure.message}")
            }
        }
    }
    private fun message(message: String) { mutableState.value = state.value.copy(message = message) }
    private fun updateEntry(transform: (ScrapeEntry) -> ScrapeEntry) {
        val index = state.value.index
        mutableState.value = state.value.copy(entries = state.value.entries.mapIndexed { position, entry -> if (position == index) transform(entry) else entry })
    }
    private fun action(block: suspend () -> Unit) {
        if (destroyed || state.value.busy || !state.value.ready) return
        mutableState.value = state.value.copy(busy = true, message = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block() }
            catch (failure: CancellationException) { message("Scraping cancelled. Accepted choices were preserved."); throw failure }
            catch (failure: Exception) { message(failure.message ?: "Provider operation failed") }
            finally {
                if (!destroyed) {
                    try { journal.save(state.value) }
                    catch (failure: Exception) { if (failure !is CancellationException) message("Cannot save scrape progress: ${failure.message}") }
                    mutableState.value = state.value.copy(busy = false, progress = null)
                }
            }
        }
    }
    private fun cleanTitle(title: String) = title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").trim().ifEmpty { title }
}
