package dev.cartridge.session

import dev.cartridge.domain.Selection
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EmulationEngine
import dev.cartridge.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CollectionScreen { Library, LibraryMenu, Details, Inserting, Handoff, Emulator, Ejecting, LabelPreview }
data class LabelDraft(val gameId: String, val label: LabelRef)
data class RemovedEntry(val gameId: String, val position: Int)
data class CollectionState(
    val screen: CollectionScreen = CollectionScreen.Library,
    val selection: Selection = Selection(count = 1),
    val slots: List<LibraryGame?> = listOf(null),
    val loaded: Boolean = false,
    val message: String? = null,
    val rendererReady: Boolean = false,
    val busy: Boolean = false,
    val menuIndex: Int = 0,
    val reducedMotion: Boolean = false,
    val alternateShortcut: Boolean = false,
    val viewportGeneration: Int = 0,
    val labelDraft: LabelDraft? = null,
    val labelPreviewReady: Boolean = false,
    val scanning: Boolean = false,
    val removedEntry: RemovedEntry? = null,
    val allGames: List<LibraryGame> = emptyList(),
    val query: LibraryQuery = LibraryQuery(),
    val historyWarning: String? = null,
    val lastLabelAppliedTo: String? = null,
    val layout: CollectionLayout = CollectionLayout.Horizontal,
) {
    val selectedGame get() = slots[selection.index]
    val title get() = selectedGame?.title ?: if (allGames.isNotEmpty()) "No matching games" else "Your collection is empty"
    val labels get() = slots.subList(windowStart, (windowStart + layout.visibleCount).coerceAtMost(slots.size)).map { game ->
        if (game?.id == labelDraft?.gameId && labelDraft != null) labelDraft.label else game?.label }
    val windowStart get() = layout.windowStart(selection.index, slots.size)
}

/** Main-thread ports return only after the old GPU owner has synchronously detached/flushed. */
interface RendererHandoff {
    fun releaseLibrary()
    fun releaseEmulator()
}

/** Activity-owned, Android-free coordinator. One user operation; repeated presses cannot queue sessions. */
class CollectionCoordinator(
    private val engine: EmulationEngine,
    private val repository: CollectionRepository,
    private val renderers: RendererHandoff,
    private val scope: CoroutineScope,
    reducedMotion: Boolean = false,
    alternateShortcut: Boolean = false,
    layout: CollectionLayout = CollectionLayout.Horizontal,
    private val onLayoutChanged: (CollectionLayout) -> Unit = {},
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(CollectionState(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut, layout = layout))
    val state = mutableState.asStateFlow()
    private var actionJob: Job? = null
    private var focusJob: Job? = null
    private var backgroundJob: Job? = null
    private var launching = false
    private var foreground = true
    private var destroyed = false
    private val observers = mutableListOf<Job>()
    private var canonical = LibrarySnapshot()
    private val playClock = PlayClock()
    private val playMutex = Mutex()
    private var playJob: Job? = null
    private var playingId: String? = null
    private var playActive = false
    private val pendingHistory = mutableMapOf<String, Pair<Long, Long?>>()
    private var playStartedAt: Long? = null
    // A filtered-out scrape target is projected only for the draft. Never persist a temporary query
    // or selection: a process death during preview must leave the user's collection view intact.
    private var previewGameId: String? = null

    init {
        observers += scope.launch {
            try {
                val initial = repository.initialize()
                applySnapshot(initial, forceFocus = true)
                mutableState.value = state.value.copy(loaded = true)
                repository.snapshots.collect { snapshot ->
                    canonical = snapshot
                    if (state.value.screen in setOf(CollectionScreen.Library, CollectionScreen.LibraryMenu, CollectionScreen.Details)) applySnapshot(snapshot)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = state.value.copy(message = "Cannot read the local library: ${failure.message}")
            }
        }
        observers += scope.launch {
            engine.status.collect { status ->
                if (status.state == SessionState.Running && playingId != null) {
                    if (!playActive) { playActive = true; playClock.running(monotonicMillis()); playStartedAt = wallMillis(); savePlay(true) }
                    if (playJob?.isActive != true) playJob = scope.launch {
                        while (isActive) { delay(15_000); savePlay(true) }
                    }
                } else if (playActive) { playActive = false; playJob?.cancel(); savePlay(false) }
                if (status.state == SessionState.Error && !state.value.busy && !destroyed) {
                    runAction { eject(status.error, animate = false) }
                }
            }
        }
    }
    private fun applySnapshot(snapshot: LibrarySnapshot, forceFocus: Boolean = false) {
        canonical = snapshot
        val focus = previewGameId ?: if (forceFocus) snapshot.slots[snapshot.selectedSlot]?.id else state.value.selectedGame?.id ?: snapshot.slots[snapshot.selectedSlot]?.id
        val games = snapshot.slots.filterNotNull()
        val matching = if (previewGameId != null) games else snapshot.query.apply(games)
        val projected: List<LibraryGame?> = if (matching.isEmpty()) listOf(null) else matching
        val index = projected.indexOfFirst { it?.id == focus }.takeIf { it >= 0 }
            ?: state.value.selection.index.coerceAtMost(projected.lastIndex)
        mutableState.value = state.value.copy(slots = projected, selection = Selection(index, projected.size), allGames = games, query = snapshot.query)
    }
    private suspend fun savePlay(keepRunning: Boolean) = playMutex.withLock {
        val id = playingId ?: return@withLock
        val pending = pendingHistory[id]
        pendingHistory[id] = ((pending?.first ?: 0) + playClock.checkpoint(monotonicMillis(), keepRunning)) to (playStartedAt ?: pending?.second)
        playStartedAt = null
        for ((gameId, checkpoint) in pendingHistory.toMap()) {
            try {
                repository.recordPlay(gameId, checkpoint.first, checkpoint.second)
                pendingHistory.remove(gameId)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = state.value.copy(historyWarning = "Could not save play history: ${failure.message}")
            }
        }
        if (pendingHistory.isEmpty()) mutableState.value = state.value.copy(historyWarning = null)
    }
    fun query(query: LibraryQuery) {
        if (state.value.screen !in setOf(CollectionScreen.Library, CollectionScreen.LibraryMenu)) return
        runAction { focusJob?.cancelAndJoin(); repository.setQuery(query); applySnapshot(repository.initialize()) }
    }
    fun favorite() {
        if (state.value.screen !in setOf(CollectionScreen.Library, CollectionScreen.Details)) return
        val game = state.value.selectedGame ?: return
        runAction {
            repository.setFavorite(game.id, !game.favorite); applySnapshot(repository.initialize())
            if (state.value.screen == CollectionScreen.Details && state.value.selectedGame?.id != game.id) closeDetails()
        }
    }
    fun details() {
        if (state.value.screen == CollectionScreen.Library && !state.value.busy && state.value.selectedGame != null)
            mutableState.value = state.value.copy(screen = CollectionScreen.Details, menuIndex = 0, message = null)
    }
    fun closeDetails() { if (!state.value.busy) mutableState.value = state.value.copy(screen = CollectionScreen.Library) }
    fun editMetadata(values: Map<String, String>) {
        val game = state.value.selectedGame ?: return
        if (state.value.screen != CollectionScreen.Details) return
        runAction {
            repository.setMetadata(game.id, values.mapValues { MetadataValue(it.value.trim(), "Local", game.id, "", "User provided", wallMillis()) })
            applySnapshot(repository.initialize()); notify("Metadata saved")
        }
    }
    fun undoEnrichment() {
        val game = state.value.selectedGame ?: return
        if (state.value.screen != CollectionScreen.Details) return
        runAction {
            notify(if (repository.undoEnrichment(game.id)) "Last artwork / metadata change undone" else "Nothing to undo")
            applySnapshot(repository.initialize())
        }
    }

    fun rendererReady(generation: Int) {
        if (!destroyed && generation == state.value.viewportGeneration) mutableState.value = state.value.copy(rendererReady = true)
    }
    fun labelsReady(generation: Int, keys: List<String?>, firstIndex: Int = 0) {
        val current = state.value
        if (generation == current.viewportGeneration && current.labelDraft != null && !current.labelPreviewReady &&
            keys.getOrNull(current.selection.index - firstIndex) == current.labelDraft.label.textureKey) {
            mutableState.value = current.copy(labelPreviewReady = true)
        }
    }
    fun navigateSpatial(x: Int, y: Int) {
        val current = state.value
        if (current.screen != CollectionScreen.Library) return
        val target = current.layout.move(current.selection.index, current.selection.count, x, y)
        if (target != current.selection.index) navigate(target - current.selection.index)
    }
    fun navigate(delta: Int) {
        if (destroyed || !foreground || state.value.busy || !state.value.loaded) return
        val current = state.value
        if (current.screen == CollectionScreen.LibraryMenu) {
            mutableState.value = current.copy(menuIndex = (current.menuIndex + delta).coerceIn(0, 7))
        } else if (current.screen == CollectionScreen.Details) {
            mutableState.value = current.copy(menuIndex = (current.menuIndex + delta).coerceIn(0, 5))
        } else if (current.screen == CollectionScreen.LabelPreview || engine.status.value.state == SessionState.Paused) {
            mutableState.value = current.copy(menuIndex = (current.menuIndex + delta).coerceIn(0, 3))
        } else if (current.screen == CollectionScreen.Library) {
            val selection = current.selection.move(delta)
            mutableState.value = current.copy(selection = selection, message = null)
            focusJob?.cancel()
            focusJob = scope.launch {
                try { state.value.slots[selection.index]?.id?.let { id -> repository.select(canonical.slots.indexOfFirst { it?.id == id }.coerceAtLeast(0)) } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = state.value.copy(message = "Could not remember selection: ${failure.message}")
                }
            }
        }
    }
    fun select() {
        when {
            state.value.screen == CollectionScreen.LibraryMenu -> libraryAction(state.value.menuIndex)
            state.value.screen == CollectionScreen.LabelPreview -> labelAction(state.value.menuIndex)
            engine.status.value.state == SessionState.Paused -> menuAction(state.value.menuIndex)
            else -> insert()
        }
    }
    fun back() {
        if (state.value.busy) return
        if (state.value.screen == CollectionScreen.Library) openLibraryMenu()
        else if (state.value.screen == CollectionScreen.Details) closeDetails()
        else if (state.value.screen == CollectionScreen.LibraryMenu) libraryAction(0)
        else if (state.value.screen == CollectionScreen.LabelPreview) cancelLabel()
        else if (engine.status.value.state == SessionState.Paused) menuAction(0)
    }
    fun openLibraryMenu() {
        if (foreground && state.value.loaded && !state.value.busy && state.value.screen == CollectionScreen.Library)
            mutableState.value = state.value.copy(screen = CollectionScreen.LibraryMenu, menuIndex = 0, message = null)
    }
    fun libraryAction(index: Int) {
        if (state.value.screen != CollectionScreen.LibraryMenu || state.value.busy) return
        when (index) {
            0 -> mutableState.value = state.value.copy(screen = CollectionScreen.Library)
            1 -> state.value.selectedGame?.let { game ->
                val entry = RemovedEntry(game.id, canonical.slots.indexOfFirst { it?.id == game.id })
                runAction {
                    focusJob?.cancelAndJoin()
                    repository.remove(game.id)
                    refreshLibrary()
                    mutableState.value = state.value.copy(screen = CollectionScreen.Library, removedEntry = entry,
                        message = "Entry removed. ROM, saves and artwork kept. Undo is available in B → Collection.")
                }
            }
            2 -> state.value.removedEntry?.let { entry ->
                runAction {
                    focusJob?.cancelAndJoin()
                    repository.restore(entry.gameId, entry.position)
                    refreshLibrary()
                    mutableState.value = state.value.copy(screen = CollectionScreen.Library, removedEntry = null, message = "Entry restored")
                }
            }
            3 -> query(state.value.query.copy(sort = LibrarySort.entries[(state.value.query.sort.ordinal + 1) % LibrarySort.entries.size]))
            4 -> query(state.value.query.copy(favoritesOnly = !state.value.query.favoritesOnly))
            5 -> query(LibraryQuery())
            7 -> settings(layout = state.value.layout.next())
        }
    }
    private suspend fun refreshLibrary() {
        val snapshot = repository.initialize()
        applySnapshot(snapshot, forceFocus = true)
    }
    fun settings(reducedMotion: Boolean = state.value.reducedMotion, alternateShortcut: Boolean = state.value.alternateShortcut, layout: CollectionLayout = state.value.layout) {
        if (!state.value.busy) {
            val changed = layout != state.value.layout
            mutableState.value = state.value.copy(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut, layout = layout)
            if (changed) onLayoutChanged(layout)
        }
    }
    fun notify(message: String) { if (!destroyed) mutableState.value = state.value.copy(message = message) }

    fun insert() {
        val selected = state.value
        if (!foreground || !selected.loaded || selected.screen != CollectionScreen.Library || !selected.rendererReady) return
        val game = selected.selectedGame ?: return notify("Choose a .gba file for this cartridge first.")
        runAction(isLaunch = true) {
            mutableState.value = state.value.copy(screen = CollectionScreen.Inserting, message = null)
            coroutineScope {
                val request = async { repository.gameRequest(game) }
                if (!selected.reducedMotion) delay(dev.cartridge.domain.MediaMotion.INSERT_MS.toLong())
                val readyRequest = request.await()
                mutableState.value = state.value.copy(screen = CollectionScreen.Handoff, rendererReady = false)
                renderers.releaseLibrary()
                mutableState.value = state.value.copy(screen = CollectionScreen.Emulator)
                playingId = game.id
                engine.prepare(readyRequest)
            }
            if (engine.status.value.state != SessionState.Running) error(engine.status.value.error ?: "The core did not start")
        }
    }
    fun pause() {
        if (engine.status.value.state != SessionState.Running) return
        runAction { playJob?.cancel(); savePlay(false); playActive = false; engine.pause(); mutableState.value = state.value.copy(menuIndex = 0) }
    }
    fun menuAction(index: Int) {
        if (engine.status.value.state != SessionState.Paused) return
        runAction {
            when (index) {
                0 -> engine.resume()
                1 -> { engine.saveState(); notify("State saved") }
                2 -> { engine.loadState(); notify("State loaded") }
                3 -> eject()
            }
        }
    }
    fun assignGame(import: suspend () -> LibraryGame) {
        val selected = state.value.selection.index
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction { val game = import(); repository.assign(selected, game); notify("Game added") }
    }
    fun addGame(import: suspend () -> LibraryGame) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            val game = import()
            focusJob?.cancelAndJoin()
            val added = repository.add(game, restoreRemoved = true)
            repository.setQuery(state.value.query.copy(favoritesOnly = false, search = ""))
            val snapshot = repository.initialize()
            repository.select(snapshot.slots.indexOfFirst { it?.id == game.id }.coerceAtLeast(0))
            refreshLibrary()
            notify(if (added) "Game added" else "Selected the existing entry")
        }
    }
    fun scanDirectory(scan: suspend ((String) -> Unit) -> String) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            mutableState.value = state.value.copy(scanning = true)
            try { notify(scan(::notify)) }
            finally { mutableState.value = state.value.copy(scanning = false) }
        }
    }
    fun cancelScan() {
        if (state.value.scanning) { actionJob?.cancel(); notify("Scan cancelled. Imported games were kept.") }
    }
    fun previewLabel(import: suspend () -> LabelRef) {
        val selected = state.value
        if (selected.screen != CollectionScreen.Library) return
        val game = selected.selectedGame ?: return notify("Choose a game before assigning its label.")
        runAction {
            val label = import()
            mutableState.value = state.value.copy(screen = CollectionScreen.LabelPreview, labelDraft = LabelDraft(game.id, label), menuIndex = 0, labelPreviewReady = false, lastLabelAppliedTo = null)
        }
    }
    fun previewScrapedLabel(game: LibraryGame, label: LabelRef) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            focusJob?.cancelAndJoin()
            val snapshot = repository.initialize()
            val position = snapshot.slots.indexOfFirst { it?.id == game.id }
            check(position >= 0) { "This game is no longer in the collection" }
            if (state.value.slots.none { it?.id == game.id }) {
                previewGameId = game.id
            } else repository.select(position)
            applySnapshot(repository.initialize(), forceFocus = true)
            mutableState.value = state.value.copy(screen = CollectionScreen.LabelPreview, labelDraft = LabelDraft(game.id, label),
                menuIndex = 0, labelPreviewReady = false, lastLabelAppliedTo = null)
        }
    }
    private suspend fun restorePreviewView() {
        previewGameId = null
        refreshLibrary()
    }
    fun labelAction(index: Int) {
        if (state.value.busy) return
        val draft = state.value.labelDraft ?: return
        when (index) {
            0 -> mutableState.value = state.value.copy(labelPreviewReady = false, labelDraft = draft.copy(label = draft.label.copy(
                fit = if (draft.label.fit == LabelFit.Fit) LabelFit.Crop else LabelFit.Fit)))
            1 -> mutableState.value = state.value.copy(labelPreviewReady = false, labelDraft = draft.copy(label = draft.label.copy(
                quarterTurns = (draft.label.quarterTurns + 1) % 4)))
            2 -> if (!state.value.labelPreviewReady) notify("Preparing label preview…") else runAction {
                repository.setLabel(draft.gameId, draft.label)
                restorePreviewView()
                mutableState.value = state.value.copy(screen = CollectionScreen.Library, labelDraft = null,
                    lastLabelAppliedTo = draft.gameId, message = "Label applied")
            }
            3 -> cancelLabel()
        }
    }
    fun cancelLabel() {
        if (state.value.labelDraft != null) runAction {
            restorePreviewView()
            mutableState.value = state.value.copy(screen = CollectionScreen.Library, labelDraft = null, lastLabelAppliedTo = null, message = null)
        }
    }
    fun removeLabel() {
        val game = state.value.selectedGame ?: return
        if (state.value.screen != CollectionScreen.Library) return
        runAction { repository.setLabel(game.id, null); notify("Original study label restored") }
    }

    private fun runAction(isLaunch: Boolean = false, action: suspend () -> Unit) {
        if (destroyed || state.value.busy || backgroundJob?.isActive == true) return
        mutableState.value = state.value.copy(busy = true, message = null)
        launching = isLaunch
        actionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() }
            catch (failure: TimeoutCancellationException) {
                eject("The emulator operation timed out. The session was released.", animate = false)
            }
            catch (failure: CancellationException) {
                if (!destroyed && isLaunch) withContext(NonCancellable) { eject(animate = false) }
                throw failure
            } catch (failure: Exception) {
                if (isLaunch || engine.status.value.state == SessionState.Error || state.value.screen in setOf(CollectionScreen.Inserting, CollectionScreen.Handoff)) {
                    eject(failure.message, animate = false)
                } else notify(failure.message ?: "Operation failed")
            } finally {
                launching = false
                if (!destroyed) mutableState.value = state.value.copy(busy = false)
            }
        }
    }
    private suspend fun eject(message: String? = null, animate: Boolean = true) {
        val hadSession = state.value.screen == CollectionScreen.Emulator
        playJob?.cancel(); savePlay(false); playActive = false
        engine.stop()
        playingId = null
        renderers.releaseEmulator()
        renderers.releaseLibrary()
        val failure = engine.status.value.error ?: message
        if (engine.status.value.state == SessionState.Error) engine.stop()
        val shouldAnimate = animate && foreground && hadSession && !state.value.reducedMotion
        mutableState.value = state.value.copy(screen = if (shouldAnimate) CollectionScreen.Ejecting else CollectionScreen.Library,
            rendererReady = false, viewportGeneration = state.value.viewportGeneration + 1, labelDraft = null, message = failure)
        if (shouldAnimate) { delay(dev.cartridge.domain.MediaMotion.EJECT_MS.toLong()); mutableState.value = state.value.copy(screen = CollectionScreen.Library) }
        refreshLibrary()
    }
    fun foreground() { foreground = true }
    fun background() {
        foreground = false
        if (destroyed || backgroundJob?.isActive == true) return
        val pending = actionJob
        if (launching) pending?.cancel()
        backgroundJob = scope.launch {
            pending?.join() // Complete writes; do not cancel a user's state save midway through ejection.
            if (!destroyed && (engine.status.value.state != SessionState.Idle || state.value.screen == CollectionScreen.Emulator)) {
                mutableState.value = state.value.copy(busy = true)
                try { eject("Session ended when the app left the foreground.", animate = false) }
                finally { if (!destroyed) mutableState.value = state.value.copy(busy = false) }
            }
        }
    }
    /** Native/view destruction is an Activity responsibility; cancel work before its final barrier. */
    fun destroy() {
        destroyed = true
        actionJob?.cancel(); backgroundJob?.cancel(); focusJob?.cancel()
        playJob?.cancel()
        observers.forEach(Job::cancel)
    }
}
