package dev.cartridge.scraping

import dev.cartridge.library.*
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ScrapePage { Scope, Targets, Preferences, Matches, Review, Artwork, Metadata }
enum class ScrapeOutcome { Pending, Applied, Skipped }
data class ScrapeEntry(val game: LibraryGame, val query: String, val matches: List<GameCandidate> = emptyList(),
    val error: String? = null, val searched: Boolean = false, val corrected: Boolean = false, val outcome: ScrapeOutcome = ScrapeOutcome.Pending)
data class ScrapeState(val visible: Boolean = false, val page: ScrapePage = ScrapePage.Scope,
    val entries: List<ScrapeEntry> = emptyList(), val index: Int = 0, val candidate: GameCandidate? = null,
    val busy: Boolean = false, val focus: Int = 0, val progress: String? = null, val message: String? = null,
    val metadataChoices: Map<String, Int> = emptyMap(), val checkedFields: Set<String> = emptySet(), val ready: Boolean = true,
    val preferences: ScrapePreferences = ScrapePreferences(), val availableGames: List<LibraryGame> = emptyList(),
    val pickedIds: Set<String> = emptySet()) {
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
    private val preferences: ScrapePreferenceStore = MemoryScrapePreferences(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preview: (LibraryGame, LabelRef) -> Unit,
) {
    private val mutableState = MutableStateFlow(ScrapeState(ready = false))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var available = emptyList<LibraryGame>()
    private var selected: LibraryGame? = null
    private var previewPending = false
    private var previewReturnPage = ScrapePage.Artwork
    private var metadataReturnPage = ScrapePage.Artwork
    private var destroyed = false
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val loaded = journal.load()?.copy(ready = true) ?: ScrapeState()
                mutableState.value = loaded.copy(visible = state.value.visible, preferences = preferences.load(), availableGames = available)
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = ScrapeState(message = "Cannot restore the saved scrape batch: ${failure.message}. Start a new scope.")
            }
        }
    }

    fun open(games: List<LibraryGame>, focused: LibraryGame?) {
        available = games.distinctBy { it.id }; selected = focused
        mutableState.value = state.value.copy(visible = true, page = ScrapePage.Scope, focus = 0, availableGames = available)
    }
    fun chooseScope(single: Boolean) {
        if (state.value.busy || !state.value.ready) return
        begin(if (single) listOfNotNull(selected) else available)
    }
    private fun begin(targets: List<LibraryGame>) {
        if (state.value.busy || !state.value.ready) return
        if (targets.isEmpty()) { message("Add a GBA game before scraping"); return }
        mutableState.value = ScrapeState(visible = true, page = ScrapePage.Matches,
            entries = targets.distinctBy { it.id }.map { ScrapeEntry(it, cleanTitle(it.title)) }, preferences = state.value.preferences, availableGames = available)
        action { journal.reset(); journal.save(state.value); searchCurrentEntry() }
    }
    fun targets() {
        if (!state.value.busy) mutableState.value = state.value.copy(page = ScrapePage.Targets, focus = 0,
            pickedIds = state.value.pickedIds.intersect(available.map { it.id }.toSet()).ifEmpty { setOfNotNull(selected?.id) })
    }
    fun pick(id: String) { if (!state.value.busy && available.any { it.id == id }) mutableState.value = state.value.copy(
        pickedIds = if (id in state.value.pickedIds) state.value.pickedIds - id else state.value.pickedIds + id) }
    fun pickAll(all: Boolean) { if (!state.value.busy) mutableState.value = state.value.copy(pickedIds = if (all) available.map { it.id }.toSet() else emptySet()) }
    fun beginPicked() {
        val targets = available.filter { it.id in state.value.pickedIds }
        if (targets.isEmpty()) message("Choose at least one game") else begin(targets)
    }
    fun missingLabels() {
        val targets = available.filter { it.label == null }
        if (targets.isEmpty()) message("Every game already has a label") else begin(targets)
    }
    fun preferencePage() { if (!state.value.busy) mutableState.value = state.value.copy(page = ScrapePage.Preferences, focus = 0) }
    fun cyclePreference(region: Boolean) = action {
        val before = state.value.preferences
        val choices = if (region) ScrapePreferences.regions else ScrapePreferences.languages
        val code = if (region) before.region else before.language
        val next = choices[(choices.indexOfFirst { it.code == code } + 1) % choices.size].code
        val changed = if (region) before.copy(region = next) else before.copy(language = next)
        preferences.save(changed)
        mutableState.value = state.value.copy(preferences = changed)
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
    private suspend fun searchCurrentEntry() {
        val index = state.value.index
        val entry = state.value.current ?: return
        if (entry.searched) return
        mutableState.value = state.value.copy(progress = "Searching ${index + 1} / ${state.value.entries.size}: ${entry.game.title}")
        val result = try {
            val file = withContext(ioDispatcher) { if (entry.corrected) null else File(roms, "${entry.game.id}.gba").takeIf(File::isFile) }
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
    fun resumeBatch() = action {
        require(state.value.entries.isNotEmpty()) { "There is no saved batch to resume" }
        mutableState.value = state.value.copy(page = ScrapePage.Matches, focus = 0, candidate = null)
        restoreCurrentCandidates()
        refreshCurrentGame()
        searchCurrentEntry()
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
        action { searchCurrentEntry() }
    }
    fun chooseCandidate(index: Int) = action {
        val candidate = state.value.current?.matches?.getOrNull(index) ?: return@action
        refreshCurrentGame()
        val ranked = state.value.preferences.rank(candidate)
        val game = state.value.current!!.game
        val focus = if (game.label == null && ranked.artwork.any { it.isLabel }) 0
            else if (ranked.metadata.keys.any { game.metadata[it]?.value.isNullOrBlank() }) 1 else 4
        mutableState.value = state.value.copy(page = ScrapePage.Review, candidate = ranked, focus = focus, message = null)
    }
    private suspend fun refreshCurrentGame(): LibraryGame {
        val id = checkNotNull(state.value.current).game.id
        val game = repository.initialize().slots.filterNotNull().firstOrNull { it.id == id }
            ?: error("This game is no longer in the collection. Skip it or start a new batch.")
        available = available.map { if (it.id == id) game else it }
        mutableState.value = state.value.copy(availableGames = available)
        updateEntry { it.copy(game = game) }
        return game
    }
    fun browseArtwork() { if (!state.value.busy) mutableState.value = state.value.copy(page = ScrapePage.Artwork, focus = 0) }
    fun review() { if (!state.value.busy) mutableState.value = state.value.copy(page = ScrapePage.Review, focus = 0) }
    fun fillMissingMetadata() = action {
        val candidate = state.value.candidate ?: return@action
        val game = refreshCurrentGame()
        val proposed = candidate.metadata.mapNotNull { (field, values) -> values.firstOrNull()?.let {
            field to MetadataValue(it, provider.name, candidate.id, candidate.sourceUrl, candidate.license, System.currentTimeMillis()) } }.toMap()
        val changed = repository.fillMissingMetadata(game.id, proposed)
        refreshCurrentGame()
        if (changed > 0) updateEntry { it.copy(outcome = ScrapeOutcome.Applied) }
        mutableState.value = state.value.copy(focus = 4)
        message(if (changed == 0) "Metadata is already filled. Existing choices kept." else "$changed missing fields filled. Existing choices kept.")
    }
    fun preferredLabel() {
        val asset = state.value.candidate?.artwork?.indexOfFirst { it.isLabel } ?: -1
        if (asset < 0) message("This match has no flat label") else artwork(asset, missingOnly = true)
    }
    fun undo() = action {
        val game = refreshCurrentGame()
        message(if (repository.undoEnrichment(game.id)) "Last artwork / metadata change undone" else "Nothing to undo")
        refreshCurrentGame()
    }
    fun metadata() {
        val candidate = state.value.candidate ?: return
        if (state.value.busy) return
        metadataReturnPage = state.value.page
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
        refreshCurrentGame(); updateEntry { it.copy(outcome = ScrapeOutcome.Applied) }
        message("Selected metadata applied. Other fields and the label were preserved.")
    }
    fun artwork(index: Int, missingOnly: Boolean = false) = action {
        val candidate = state.value.candidate ?: return@action
        val game = refreshCurrentGame()
        val asset = candidate.artwork.getOrNull(index) ?: return@action
        if (asset.isLabel && missingOnly && game.label != null) { message("This game already has a label. Choose All label / media choices to replace it explicitly."); return@action }
        val bytes = provider.image(asset)
        val label = withContext(ioDispatcher) { images.importStream(asset.url) { bytes.inputStream() } }.copy(
            provenance = AssetProvenance(provider.name, candidate.id, asset.type, asset.region, asset.url,
                "Artwork rights remain with their owners; provider grants no image licence. Data: ${candidate.license}", System.currentTimeMillis()))
        if (asset.isLabel) {
            previewPending = true
            previewReturnPage = state.value.page
            mutableState.value = state.value.copy(visible = false)
            preview(game, label)
        } else {
            repository.setMedia(game.id, MediaSelection(label, asset.type, asset.region))
            refreshCurrentGame(); updateEntry { it.copy(outcome = ScrapeOutcome.Applied) }
            message("${asset.type} (${asset.region}) saved as additional media. The cartridge label was preserved.")
        }
    }
    fun previewFinished(applied: Boolean = false) {
        if (!previewPending) return
        previewPending = false
        mutableState.value = state.value.copy(visible = true, page = previewReturnPage,
            focus = if (previewReturnPage == ScrapePage.Review && applied) 1 else state.value.focus,
            message = if (applied) "Label applied. Fill missing metadata or move to the next title." else "Preview cancelled. Accepted label kept.")
        action {
            refreshCurrentGame(); if (applied) updateEntry { it.copy(outcome = ScrapeOutcome.Applied) }
            message(if (applied) "Label applied. Fill missing metadata or move to the next title." else "Preview cancelled. Accepted label kept.")
        }
    }
    fun next() {
        action {
            updateEntry { if (it.outcome == ScrapeOutcome.Pending) it.copy(outcome = ScrapeOutcome.Skipped) else it }
            if (state.value.index + 1 < state.value.entries.size) {
                mutableState.value = state.value.copy(index = state.value.index + 1, page = ScrapePage.Matches,
                    candidate = null, focus = 0, message = null, entries = state.value.entries.map { it.copy(matches = emptyList()) })
                restoreCurrentCandidates()
                refreshCurrentGame()
                if (state.value.current?.searched == false) searchCurrentEntry()
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
            ScrapePage.Targets, ScrapePage.Preferences -> state.value.copy(page = ScrapePage.Scope, focus = 0)
            ScrapePage.Matches -> state.value.copy(page = ScrapePage.Scope, focus = 0)
            ScrapePage.Review -> state.value.copy(page = ScrapePage.Matches, candidate = null, focus = 0)
            ScrapePage.Artwork -> state.value.copy(page = ScrapePage.Review, focus = 0)
            ScrapePage.Metadata -> state.value.copy(page = metadataReturnPage, focus = if (metadataReturnPage == ScrapePage.Review) 3 else 0)
        }
    }
    fun navigate(delta: Int) { if (!state.value.busy) mutableState.value = state.value.copy(focus = (state.value.focus + delta).coerceIn(0, actionCount() - 1)) }
    fun select() {
        if (state.value.busy) return
        val focus = state.value.focus
        when (state.value.page) {
            ScrapePage.Scope -> when (focus) { 0 -> chooseScope(true); 1, 2 -> chooseScope(false); 3 -> resumeBatch(); 4 -> targets(); 5 -> missingLabels(); 6 -> preferencePage(); else -> back() }
            ScrapePage.Targets -> when (focus) { 0 -> beginPicked(); 1 -> pickAll(true); 2 -> pickAll(false)
                else -> available.getOrNull(focus - 3)?.let { pick(it.id) } ?: back() }
            ScrapePage.Preferences -> when (focus) { 0 -> cyclePreference(true); 1 -> cyclePreference(false); else -> back() }
            ScrapePage.Review -> when (focus) { 0 -> preferredLabel(); 1 -> fillMissingMetadata(); 2 -> browseArtwork(); 3 -> metadata(); 4 -> next(); 5 -> undo(); else -> back() }
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
        ScrapePage.Scope -> 8
        ScrapePage.Targets -> available.size + 4
        ScrapePage.Preferences -> 3
        ScrapePage.Review -> 7
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
