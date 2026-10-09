package dev.cartridge.scraping

import dev.cartridge.library.*
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ScrapePage { Scope, Matches, Artwork, Metadata }
data class ScrapeEntry(val game: LibraryGame, val query: String, val matches: List<GameCandidate> = emptyList(),
    val error: String? = null, val searched: Boolean = false)
data class ScrapeState(val visible: Boolean = false, val page: ScrapePage = ScrapePage.Scope,
    val entries: List<ScrapeEntry> = emptyList(), val index: Int = 0, val candidate: GameCandidate? = null,
    val busy: Boolean = false, val focus: Int = 0, val progress: String? = null, val message: String? = null,
    val metadataChoices: Map<String, Int> = emptyMap(), val checkedFields: Set<String> = emptySet()) {
    val current get() = entries.getOrNull(index)
}

/** Reviewed queue for the small GBA library. Provider results never auto-overwrite accepted choices. */
class ScrapeCoordinator(
    val provider: ArtworkProvider,
    private val repository: CollectionRepository,
    private val images: LabelStore,
    private val roms: File,
    private val scope: CoroutineScope,
    private val preview: (LibraryGame, LabelRef) -> Unit,
) {
    private val mutableState = MutableStateFlow(ScrapeState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var available = emptyList<LibraryGame>()
    private var selected: LibraryGame? = null
    private var previewPending = false

    fun open(games: List<LibraryGame>, focused: LibraryGame?) {
        available = games.distinctBy { it.id }; selected = focused
        mutableState.value = state.value.copy(visible = true)
    }
    fun chooseScope(single: Boolean) {
        if (state.value.busy) return
        val targets = if (single) listOfNotNull(selected) else available
        if (targets.isEmpty()) { message("Add a GBA game before scraping"); return }
        mutableState.value = ScrapeState(visible = true, page = ScrapePage.Matches,
            entries = targets.map { ScrapeEntry(it, cleanTitle(it.title)) })
        searchQueue()
    }
    fun query(value: String) {
        if (state.value.busy) return
        updateEntry { it.copy(query = value.take(180)) }
    }
    fun searchCurrent() = action {
        val entry = state.value.current ?: return@action
        val matches = provider.search(entry.query) // Explicit title correction must not reapply a ROM hash guess.
        updateEntry { it.copy(matches = matches, error = null, searched = true) }
        mutableState.value = state.value.copy(page = ScrapePage.Matches, focus = 0, candidate = null)
    }
    private fun searchQueue() = action {
        for (index in state.value.entries.indices) {
            val entry = state.value.entries[index]
            if (entry.searched) continue
            mutableState.value = state.value.copy(progress = "Searching ${index + 1} / ${state.value.entries.size}: ${entry.game.title}")
            val result = try {
                val file = File(roms, "${entry.game.id}.gba").takeIf(File::isFile)
                entry.copy(matches = provider.search(entry.query, file), searched = true, error = null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                entry.copy(error = failure.message ?: "Search failed", searched = true)
            }
            mutableState.value = state.value.copy(entries = state.value.entries.toMutableList().also { it[index] = result })
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
        if (state.value.busy) return
        if (state.value.index + 1 < state.value.entries.size) mutableState.value = state.value.copy(
            index = state.value.index + 1, page = ScrapePage.Matches, candidate = null, focus = 0, message = null)
        else mutableState.value = state.value.copy(visible = false, page = ScrapePage.Scope, entries = emptyList(), candidate = null, focus = 0)
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
            ScrapePage.Scope -> when (focus) { 0 -> chooseScope(true); 1, 2 -> chooseScope(false); else -> back() }
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
        ScrapePage.Scope -> 4
        ScrapePage.Matches -> (state.value.current?.matches?.size ?: 0) + 3
        ScrapePage.Artwork -> (state.value.candidate?.artwork?.size ?: 0) + 3
        ScrapePage.Metadata -> (state.value.candidate?.metadata?.size ?: 0) + 2
    }
    fun cancel() { mutableState.value = state.value.copy(visible = false); job?.cancel() }
    fun destroy() { previewPending = false; job?.cancel() }
    private fun message(message: String) { mutableState.value = state.value.copy(message = message) }
    private fun updateEntry(transform: (ScrapeEntry) -> ScrapeEntry) {
        val index = state.value.index
        mutableState.value = state.value.copy(entries = state.value.entries.mapIndexed { position, entry -> if (position == index) transform(entry) else entry })
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, message = null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block() }
            catch (failure: CancellationException) { message("Scraping cancelled. Accepted choices were preserved."); throw failure }
            catch (failure: Exception) { message(failure.message ?: "Provider operation failed") }
            finally { mutableState.value = state.value.copy(busy = false, progress = null) }
        }
    }
    private fun cleanTitle(title: String) = title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").trim().ifEmpty { title }
}
