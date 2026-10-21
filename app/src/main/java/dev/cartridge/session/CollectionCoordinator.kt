package dev.cartridge.session

import dev.cartridge.domain.Selection
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.SessionState
import dev.cartridge.domain.GameSystem
import dev.cartridge.emulation.EmulationEngine
import dev.cartridge.library.*
import dev.cartridge.storage.ResumeSession
import dev.cartridge.storage.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CollectionScreen { Library, Inserting, Handoff, Emulator, Ejecting, LabelPreview }
data class LabelDraft(val gameId: String, val label: LabelRef)
data class RemovedEntry(val gameId: String, val position: Int)
/** A game that did not start. The session is already released; the library entry is untouched. */
data class LaunchFailure(val gameId: String, val title: String, val system: GameSystem, val reason: String)
data class CollectionState(
    val screen: CollectionScreen = CollectionScreen.Library,
    val selection: Selection = Selection(count = 1),
    val slots: List<LibraryGame?> = listOf(null),
    val loaded: Boolean = false,
    val message: String? = null,
    val rendererReady: Boolean = false,
    val busy: Boolean = false,
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
    val resumeSession: ResumeSession? = null,
    val shelf: Shelf = Shelf.All,
    val launchFailure: LaunchFailure? = null,
    /**
     * The screen is closed while the stage changes hands between the library and a game, so the swap, a
     * loading pause and a core's blank first frames are never seen. It opens on a picture, not on a promise.
     */
    val curtain: Boolean = false,
    /** The number of the disc just put in the drive, shown on the game for a moment by `ui/GameBadge` (ADR 0032). */
    val discBadge: Int? = null,
    /** The game whose last disc change by hand can be undone. */
    val discUndo: String? = null,
) {
    val selectedGame get() = slots[selection.index]
    val title get() = selectedGame?.title ?: if (allGames.isNotEmpty()) "No matching games" else "Your collection is empty"
    /** The bounded slice of the shelf that owns GPU objects and decoded labels: what is on screen and what one step brings in. */
    val window get() = slots.subList(windowStart, (windowStart + layout.windowCount).coerceAtMost(slots.size))
    val labels get() = window.map { game -> if (game?.id == labelDraft?.gameId && labelDraft != null) labelDraft.label else game?.label }
    val windowStart get() = layout.windowStart(selection.index, slots.size)
    val systems get() = window.map { it?.system ?: GameSystem.GBA }
    val shelves get() = Shelf.available(allGames)
    val searching get() = query.search.isNotBlank()
}

/** How long the focus must rest before it is written to the library. */
private const val FOCUS_SETTLE_MS = 400L
/** How long a changed disc's number stays on the game. */
private const val DISC_BADGE_MS = 2_000L

/** Main-thread ports return only after the old GPU owner has synchronously detached/flushed. */
interface RendererHandoff {
    fun releaseLibrary()
    fun releaseEmulator()
    /** Returns once the running game is showing a picture rather than a blank screen, or after a short bounded wait. */
    suspend fun awaitPicture() {}
}

/**
 * Activity-owned, Android-free coordinator. One user operation; repeated presses cannot queue sessions.
 * It owns the library projection and the native session, not menus: overlays live in the console shell.
 */
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
    private val sessionStore: SessionStore? = null,
    private val initialShelf: Shelf? = null,
    initialShelfFocus: Map<String, String> = emptyMap(),
    private val onBrowseChanged: (Shelf, Map<String, String>) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow(CollectionState(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut, layout = layout))
    val state = mutableState.asStateFlow()
    private var actionJob: Job? = null
    private var focusJob: Job? = null
    private var focusPending = false
    private var engineNotice: String? = null
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
    // A scrape target outside the current shelf or search is projected only for the draft. Never persist a
    // temporary query or selection: a process death during preview must leave the user's collection view intact.
    private var previewGameId: String? = null
    private var resumeAfterBackground = false
    private val shelfFocus = initialShelfFocus.toMutableMap()
    private var launchTarget: LibraryGame? = null
    private var discUndo: DiscUndo? = null
    private var badgeJob: Job? = null

    init {
        observers += scope.launch {
            try {
                var initial = repository.initialize()
                var shelf = initialShelf
                // The favourites filter became a shelf. Move a stored flag across once so no view stays filtered without a control.
                if (initial.query.favoritesOnly) {
                    repository.setQuery(initial.query.copy(favoritesOnly = false))
                    initial = repository.initialize()
                    if (initial.slots.any { it?.favorite == true }) shelf = Shelf.Favorites
                }
                applySnapshot(initial, startup = true, startShelf = shelf)
                mutableState.value = state.value.copy(loaded = true)
                readResume()
                repository.snapshots.collect { snapshot ->
                    canonical = snapshot
                    if (state.value.screen == CollectionScreen.Library) applySnapshot(snapshot)
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
                // Something the engine has to say about this start, once.
                if (status.notice != null && status.notice != engineNotice) notify(status.notice)
                engineNotice = status.notice
                if (status.state == SessionState.Error && !state.value.busy && !destroyed) {
                    runAction { eject(status.error, animate = false) }
                }
            }
        }
    }
    private suspend fun readResume() {
        val store = sessionStore ?: return
        try {
            val saved = withContext(Dispatchers.IO) { store.available() }
            // Read the latest UI state after suspension; renderer/input updates may arrive while
            // the filesystem is checked. A captured receiver could clear its one-shot readiness.
            mutableState.value = state.value.copy(resumeSession = saved)
        }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; notify("Saved session unavailable: ${failure.message}. Discard it in Settings.") }
    }
    fun discardResume() {
        if (state.value.screen != CollectionScreen.Library) return
        runAction { withContext(Dispatchers.IO) { sessionStore?.clear() }; mutableState.value = state.value.copy(resumeSession = null); notify("Saved session discarded; battery and manual saves kept") }
    }
    fun resumeSavedSession() {
        val saved = state.value.resumeSession ?: return
        if (!foreground || state.value.screen != CollectionScreen.Library || state.value.busy || !state.value.loaded) return
        val game = state.value.allGames.firstOrNull { it.id == saved.id && it.system == saved.system }
            ?: return notify("The saved game's library entry is missing. Restore the entry or discard this session.")
        launchTarget = null // A failed restore is reported in place; the checkpoint stays available.
        runAction(isLaunch = true) {
            val bytes = withContext(Dispatchers.IO) { checkNotNull(sessionStore).read(saved) }
            val request = repository.gameRequest(game).copy(resumeState = bytes)
            mutableState.value = state.value.copy(shelf = shelfShowing(game))
            previewGameId = game.id; applySnapshot(canonical)
            closeCurtain()
            mutableState.value = state.value.copy(screen = CollectionScreen.Handoff, rendererReady = false)
            renderers.releaseLibrary(); mutableState.value = state.value.copy(screen = CollectionScreen.Emulator)
            playingId = game.id
            engine.prepare(request)
            check(engine.status.value.state == SessionState.Running) { engine.status.value.error ?: "The saved session did not start" }
            engine.pause()
            readResume()
            mutableState.value = state.value.copy(curtain = false, message = "Session restored")
        }
    }
    /**
     * Projects the canonical collection onto the current shelf. Focus follows a game's identity: an explicit
     * target, then the game already focused, then what this shelf last showed, then the stored selection.
     * Changing shelf is the exception: a shelf the player turns to always opens on its first game.
     */
    private fun applySnapshot(snapshot: LibrarySnapshot, focusId: String? = null, startup: Boolean = false,
        startShelf: Shelf? = null, shelfChanged: Boolean = false) {
        canonical = snapshot
        val games = snapshot.slots.filterNotNull()
        val current = state.value
        val stored = snapshot.slots[snapshot.selectedSlot]?.id
        val focused = focusId ?: previewGameId ?: if (startup) null else current.selectedGame?.id
        val shelves = Shelf.available(games)
        var shelf = if (startup) startShelf ?: games.firstOrNull { it.id == stored }?.let { Shelf.Console(it.system) } ?: Shelf.All else current.shelf
        if (shelf !in shelves) shelf = games.firstOrNull { it.id == (focused ?: stored) }?.let { Shelf.Console(it.system) }?.takeIf { it in shelves }
            ?: shelves.firstOrNull() ?: Shelf.All
        val matching = when {
            previewGameId != null -> games
            snapshot.query.search.isNotBlank() -> snapshot.query.copy(favoritesOnly = false).apply(games)
            shelf == Shelf.Recent -> shelf.apply(games)
            else -> shelf.apply(snapshot.query.copy(favoritesOnly = false).apply(games))
        }
        val projected: List<LibraryGame?> = matching.ifEmpty { listOf(null) }
        val index = if (shelfChanged && focusId == null) 0 else listOf(focused, shelfFocus[shelf.key], stored).firstNotNullOfOrNull { id ->
            if (id == null) null else projected.indexOfFirst { it?.id == id }.takeIf { it >= 0 }
        } ?: if (startup || shelf != current.shelf) 0 else current.selection.index.coerceAtMost(projected.lastIndex)
        mutableState.value = state.value.copy(slots = projected, selection = Selection(index, projected.size), allGames = games,
            query = snapshot.query, shelf = shelf)
        rememberBrowse()
    }
    private fun rememberBrowse() {
        if (previewGameId != null || state.value.screen == CollectionScreen.LabelPreview) return
        state.value.selectedGame?.id?.let { shelfFocus[state.value.shelf.key] = it }
        onBrowseChanged(state.value.shelf, shelfFocus.toMap())
    }
    /** The shelf to stand on when [game] must be visible: the current one if it already shows it. */
    private fun shelfShowing(game: LibraryGame): Shelf =
        if (state.value.shelf.apply(listOf(game)).isNotEmpty()) state.value.shelf else Shelf.Console(game.system)
    /**
     * The stored selection is written once browsing settles, never per step: a database write while the
     * D-pad is held competes with the frames that show the movement.
     */
    private fun persistFocus() {
        if (state.value.selectedGame == null) return
        focusPending = true
        focusJob?.cancel()
        focusJob = scope.launch { delay(FOCUS_SETTLE_MS); writeFocus() }
    }
    private suspend fun writeFocus() {
        val id = state.value.selectedGame?.id
        if (!focusPending || id == null) return
        try { repository.select(canonical.slots.indexOfFirst { it?.id == id }.coerceAtLeast(0)); focusPending = false }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            focusPending = false
            mutableState.value = state.value.copy(message = "Could not remember selection: ${failure.message}")
        }
    }
    /** Before anything that reloads or rewrites the library: finish the pending selection write now. */
    private suspend fun settleFocus() { focusJob?.cancelAndJoin(); writeFocus() }
    fun chooseShelf(shelf: Shelf) {
        val current = state.value
        if (destroyed || !foreground || current.busy || !current.loaded || current.screen != CollectionScreen.Library || shelf !in current.shelves) return
        if (current.searching) {
            // Leaving search for a shelf is one step; the stored search is cleared with it.
            runAction {
                settleFocus(); repository.setQuery(state.value.query.copy(search = ""))
                mutableState.value = state.value.copy(shelf = shelf)
                applySnapshot(repository.initialize(), shelfChanged = true); persistFocus()
            }
        } else if (shelf != current.shelf) {
            rememberBrowse()
            mutableState.value = current.copy(shelf = shelf, message = null)
            applySnapshot(canonical, shelfChanged = true)
            persistFocus()
        }
    }
    fun switchShelf(delta: Int) {
        val shelves = state.value.shelves
        val index = shelves.indexOf(state.value.shelf).coerceAtLeast(0)
        if (state.value.searching) chooseShelf(shelves[index])
        else if (shelves.size > 1) chooseShelf(shelves[Math.floorMod(index + delta, shelves.size)])
    }
    /** Stand on a shelf that shows this game and focus it, e.g. after an import. */
    fun reveal(gameId: String) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction { settleFocus(); show(gameId) }
    }
    private suspend fun show(gameId: String) {
        if (state.value.searching) repository.setQuery(state.value.query.copy(search = ""))
        var snapshot = repository.initialize()
        val position = snapshot.slots.indexOfFirst { it?.id == gameId }
        if (position >= 0) {
            repository.select(position)
            snapshot = repository.initialize()
            mutableState.value = state.value.copy(shelf = shelfShowing(snapshot.slots[position]!!))
        }
        applySnapshot(snapshot, focusId = gameId)
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
        if (state.value.screen != CollectionScreen.Library) return
        runAction { settleFocus(); repository.setQuery(query.copy(favoritesOnly = false)); applySnapshot(repository.initialize()) }
    }
    fun favorite() {
        if (state.value.screen != CollectionScreen.Library) return
        val game = state.value.selectedGame ?: return
        runAction {
            repository.setFavorite(game.id, !game.favorite); applySnapshot(repository.initialize())
            notify(if (game.favorite) "Removed from Favorites" else "Added to Favorites")
        }
    }
    fun editMetadata(values: Map<String, String>) {
        val game = state.value.selectedGame ?: return
        if (state.value.screen != CollectionScreen.Library || values.isEmpty()) return
        runAction {
            repository.setMetadata(game.id, values.mapValues { MetadataValue(it.value.trim(), "Local", game.id, "", "User provided", wallMillis()) })
            applySnapshot(repository.initialize()); notify("Details saved")
        }
    }
    fun undoEnrichment() {
        val game = state.value.selectedGame ?: return
        if (state.value.screen != CollectionScreen.Library) return
        runAction {
            notify(if (repository.undoEnrichment(game.id)) "Last artwork / metadata change undone" else "Nothing to undo")
            applySnapshot(repository.initialize())
        }
    }

    fun rendererReady(generation: Int) {
        if (destroyed || generation != state.value.viewportGeneration) return
        // A rebuilt library stage is revealed only now that it has drawn.
        val library = state.value.screen in setOf(CollectionScreen.Library, CollectionScreen.Ejecting, CollectionScreen.LabelPreview)
        mutableState.value = state.value.copy(rendererReady = true, curtain = state.value.curtain && !library)
    }
    fun labelsReady(generation: Int, keys: List<String?>, firstIndex: Int = 0) {
        val current = state.value
        if (generation == current.viewportGeneration && current.labelDraft != null && !current.labelPreviewReady &&
            keys.getOrNull(current.selection.index - firstIndex) == current.labelDraft.label.textureKey(current.selectedGame?.shownAs ?: dev.cartridge.domain.Media.entries.first())) {
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
        if (current.screen != CollectionScreen.Library) return
        val selection = current.selection.move(delta)
        if (selection == current.selection) return
        mutableState.value = current.copy(selection = selection, message = null)
        rememberBrowse()
        persistFocus()
    }
    /** Hide only the library entry. The game file, saves and artwork stay; a rescan will not bring it back. */
    fun hideSelected() {
        if (state.value.screen != CollectionScreen.Library || state.value.busy) return
        val game = state.value.selectedGame ?: return
        val entry = RemovedEntry(game.id, canonical.slots.indexOfFirst { it?.id == game.id })
        runAction {
            settleFocus()
            repository.remove(game.id)
            refreshLibrary()
            mutableState.value = state.value.copy(removedEntry = entry, message = "${game.title} hidden. Its file, saves and artwork are kept.")
        }
    }
    fun undoHide() {
        if (state.value.screen != CollectionScreen.Library || state.value.busy) return
        val entry = state.value.removedEntry ?: return
        runAction {
            settleFocus()
            repository.restore(entry.gameId, entry.position)
            show(entry.gameId)
            mutableState.value = state.value.copy(removedEntry = null, message = "Back in your library")
        }
    }
    /** Return a hidden game from Settings. It rejoins the end of the collection and takes focus. */
    fun restoreHidden(gameId: String) {
        if (state.value.screen != CollectionScreen.Library || state.value.busy) return
        runAction {
            settleFocus()
            repository.restore(gameId, canonical.slots.count { it != null })
            show(gameId)
            mutableState.value = state.value.copy(removedEntry = state.value.removedEntry?.takeIf { it.gameId != gameId }, message = "Back in your library")
        }
    }
    private suspend fun refreshLibrary() = applySnapshot(repository.initialize())
    fun settings(reducedMotion: Boolean = state.value.reducedMotion, alternateShortcut: Boolean = state.value.alternateShortcut, layout: CollectionLayout = state.value.layout) {
        if (!state.value.busy) {
            val changed = layout != state.value.layout
            mutableState.value = state.value.copy(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut, layout = layout)
            if (changed) onLayoutChanged(layout)
        }
    }
    fun notify(message: String) { if (!destroyed) mutableState.value = state.value.copy(message = message) }
    fun dismissMessage() { if (!destroyed && state.value.message != null) mutableState.value = state.value.copy(message = null) }
    fun dismissLaunchFailure() { if (!destroyed && state.value.launchFailure != null) mutableState.value = state.value.copy(launchFailure = null) }

    /** Starts the focused game, on its disc [disc] by its place in the game's discs, or on the disc last in the drive. */
    fun insert(disc: Int? = null) {
        val selected = state.value
        if (!foreground || !selected.loaded || selected.screen != CollectionScreen.Library || !selected.rendererReady) return
        val game = selected.selectedGame ?: return
        launchTarget = game
        runAction(isLaunch = true) {
            mutableState.value = state.value.copy(screen = CollectionScreen.Inserting, message = null, launchFailure = null)
            coroutineScope {
                val request = async { repository.gameRequest(game, disc) }
                if (!selected.reducedMotion) delay(dev.cartridge.domain.MediaMotion.INSERT_MS.toLong())
                closeCurtain()
                val readyRequest = request.await()
                mutableState.value = state.value.copy(screen = CollectionScreen.Handoff, rendererReady = false)
                renderers.releaseLibrary()
                mutableState.value = state.value.copy(screen = CollectionScreen.Emulator)
                playingId = game.id
                engine.prepare(readyRequest)
                // The disc a game of several was started on is in the drive, also where the engine cannot change discs.
                if (readyRequest.discs.size > 1 && engine.status.value.state == SessionState.Running)
                    try { repository.setDisc(game.id, readyRequest.disc, 0) } catch (failure: Exception) { if (failure is CancellationException) throw failure }
            }
            if (engine.status.value.state != SessionState.Running) error(engine.status.value.error ?: "The core did not start")
            renderers.awaitPicture()
            mutableState.value = state.value.copy(curtain = false)
        }
    }
    /** Close the screen and give the closing motion its time before the stage beneath it is taken apart. */
    private suspend fun closeCurtain() {
        if (state.value.curtain) return
        mutableState.value = state.value.copy(curtain = true)
        if (!state.value.reducedMotion) delay(dev.cartridge.domain.MediaMotion.CURTAIN_MS.toLong())
    }
    /** The pause menu: over a running game, or over a frozen one, which then keeps its frame. Either is at normal speed after. */
    fun pause() {
        val status = engine.status.value
        if (status.state == SessionState.Paused && status.frozen) return runAction { engine.pause() }
        if (status.state != SessionState.Running) return
        runAction { playJob?.cancel(); savePlay(false); playActive = false; engine.pause() }
    }
    /** The freeze combo (ADR 0031): a running game stops where it is with no menu, and a frozen one goes on at its speed. */
    fun toggleFreeze() {
        val status = engine.status.value
        when {
            status.state == SessionState.Running -> runAction { playJob?.cancel(); savePlay(false); playActive = false; engine.freeze() }
            status.state == SessionState.Paused && status.frozen -> runAction { engine.resume() }
        }
    }
    /** The fast-forward combo: between normal speed and [frames], on a running game whose core allows it. */
    fun toggleFastForward(frames: Int) {
        val status = engine.status.value
        if (status.state != SessionState.Running) return
        val system = playingId?.let { id -> state.value.allGames.firstOrNull { it.id == id } }?.system ?: return
        if (!system.core.fastForward) return notify("Fast-forward isn't available for ${system.title}")
        runAction { engine.speed(if (status.speed > 1) 1 else frames) }
    }
    /** 0 resume · 1 save state · 2 load state · 3 eject. Positions are a stable contract for the pause menu. */
    fun menuAction(index: Int) {
        if (engine.status.value.state != SessionState.Paused) return
        runAction {
            when (index) {
                0 -> engine.resume()
                1 -> { engine.saveState(); notify("Quick saved") }
                2 -> { engine.loadState(); notify("Quick loaded") }
                3 -> eject()
            }
        }
    }
    /**
     * The quick save, from the pause menu or from its combo during play. [resume] returns a paused game to play once it
     * is done, as the owner's After Quick Save setting asks; a running game simply keeps running.
     */
    fun quickSave(resume: Boolean = false) = stateAction("Quick saved", resume) { engine.saveState() }
    /** A frozen game goes on from what it loads: the frame it was frozen on is no longer the game's. */
    fun quickLoad(resume: Boolean = false) = stateAction("Quick loaded", resume || engine.status.value.frozen) { engine.loadState() }
    /** One of the paused game's listed states. Saving into the list keeps the menu open; loading follows [resume]. */
    fun saveSlot(replacing: String? = null, saved: (String) -> Unit = {}) {
        if (engine.status.value.state != SessionState.Paused) return
        runAction { val name = engine.saveSlot(replacing); notify(if (replacing == null) "Saved" else "Save overwritten"); saved(name) }
    }
    fun loadSlot(name: String, resume: Boolean = false) {
        if (engine.status.value.state != SessionState.Paused) return
        runAction { engine.loadSlot(name); if (resume) engine.resume(); notify("Loaded") }
    }
    /** File work on a paused game's saves, serialized with everything else the session does. */
    fun pausedFiles(message: String, operation: suspend () -> Unit) {
        if (engine.status.value.state == SessionState.Paused) runAction { operation(); notify(message) }
    }
    private fun stateAction(message: String, resume: Boolean, operation: suspend () -> Unit) {
        val running = engine.status.value.state
        if (running != SessionState.Running && running != SessionState.Paused) return
        runAction {
            operation()
            if (resume && engine.status.value.state == SessionState.Paused) engine.resume()
            notify(message)
        }
    }
    /** A paused game's drive gets [disc] (ADR 0032), and the game goes on with the disc's number on it for a moment. */
    fun changeDisc(disc: Int) {
        val status = engine.status.value
        if (status.state != SessionState.Paused || disc !in 0 until status.discs) return
        runAction {
            if (disc != engine.status.value.disc) engine.changeDisc(disc)
            rememberDisc()
            engine.resume()
            showDisc(disc)
        }
    }
    /** A paused game continues from a state one of its discs made as a game of its own. */
    fun loadDiscState(discId: String, name: String, disc: Int, resume: Boolean = false) {
        if (engine.status.value.state != SessionState.Paused) return
        runAction { engine.loadDiscState(discId, name, disc); rememberDisc(); if (resume) engine.resume(); notify("Loaded"); if (resume) showDisc(disc) }
    }
    private fun showDisc(disc: Int) {
        badgeJob?.cancel()
        val game = playingId?.let { id -> state.value.allGames.firstOrNull { it.id == id } }
        mutableState.value = state.value.copy(discBadge = game?.discs?.getOrNull(disc)?.number ?: (disc + 1))
        badgeJob = scope.launch { delay(DISC_BADGE_MS); mutableState.value = state.value.copy(discBadge = null) }
    }
    /** The disc in the drive is where the game starts next time. A failure to write it costs nothing but that. */
    private suspend fun rememberDisc() {
        val id = playingId ?: return
        val status = engine.status.value
        if (status.discs <= 0) return
        try { repository.setDisc(id, status.disc, status.discs) } catch (failure: Exception) { if (failure is CancellationException) throw failure }
    }
    /** By hand (ADR 0032): [discId], a game of one disc, joins the focused game as one of its discs. */
    fun addDisc(discId: String) = discChange("Disc added") { repository.addDisc(it.id, discId) }
    /** By hand: each of the focused game's discs is a game of its own again. */
    fun separateDiscs() = discChange("Discs separated") { repository.separateDiscs(it.id) }
    fun undoDiscs() {
        val undo = discUndo ?: return
        libraryOperation {
            repository.undoDiscs(undo); discUndo = null
            mutableState.value = state.value.copy(discUndo = null)
            show(undo.gameId); notify("Disc change undone")
        }
    }
    private fun discChange(message: String, change: suspend (LibraryGame) -> DiscUndo) {
        val game = state.value.selectedGame ?: return
        libraryOperation {
            discUndo = change(game)
            mutableState.value = state.value.copy(discUndo = game.id)
            show(game.id); notify(message)
        }
    }
    fun configureGame(options: Map<String, String>, applied: () -> Unit) {
        if (engine.status.value.state == SessionState.Paused) runAction { engine.configure(options); applied() }
    }
    /** Told the ids of games that were not in the library before; artwork is fetched for them (ADR 0029). */
    var onAdded: (Collection<String>) -> Unit = {}
    fun addGame(import: suspend () -> LibraryGame) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            val game = import()
            settleFocus()
            val added = repository.add(game, restoreRemoved = true)
            show(game.id)
            notify(if (added) "Game added" else "Selected the existing entry")
            if (added) onAdded(listOf(game.id))
        }
    }
    /** A blank result leaves reporting to the caller's own import summary. */
    fun scanDirectory(scan: suspend ((String) -> Unit) -> String) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            mutableState.value = state.value.copy(scanning = true)
            try { scan(::notify).let { if (it.isNotBlank()) notify(it) else dismissMessage() } }
            finally { mutableState.value = state.value.copy(scanning = false) }
        }
    }
    /** Serialized library-only file operations; never read/write saves under an active core. */
    fun libraryOperation(operation: suspend () -> Unit) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded || engine.status.value.state != SessionState.Idle) return
        runAction { settleFocus(); operation(); refreshLibrary(); readResume() }
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
            mutableState.value = state.value.copy(screen = CollectionScreen.LabelPreview, labelDraft = LabelDraft(game.id, label), labelPreviewReady = false, lastLabelAppliedTo = null)
        }
    }
    fun previewScrapedLabel(game: LibraryGame, label: LabelRef) {
        if (state.value.screen != CollectionScreen.Library || !state.value.loaded) return
        runAction {
            settleFocus()
            val snapshot = repository.initialize()
            val position = snapshot.slots.indexOfFirst { it?.id == game.id }
            check(position >= 0) { "This game is no longer in the collection" }
            if (state.value.slots.none { it?.id == game.id }) {
                previewGameId = game.id
            } else repository.select(position)
            applySnapshot(repository.initialize(), focusId = game.id)
            mutableState.value = state.value.copy(screen = CollectionScreen.LabelPreview, labelDraft = LabelDraft(game.id, label),
                labelPreviewReady = false, lastLabelAppliedTo = null)
        }
    }
    private suspend fun restorePreviewView() {
        previewGameId = null
        refreshLibrary()
    }
    /** 0 fit/crop · 1 rotate · 2 apply · 3 cancel. */
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
        runAction { repository.setLabel(game.id, null); notify("Label removed") }
    }

    private fun runAction(isLaunch: Boolean = false, action: suspend () -> Unit) {
        if (destroyed || state.value.busy || backgroundJob?.isActive == true) return
        mutableState.value = state.value.copy(busy = true, message = null)
        launching = isLaunch
        val target = if (isLaunch) launchTarget else null
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
                    // A launch that failed gets a recovery sheet; the same text stays in message for callers that read it.
                    if (target != null && !destroyed) mutableState.value = state.value.copy(
                        launchFailure = LaunchFailure(target.id, target.title, target.system, state.value.message ?: failure.message ?: "The game did not start"))
                } else notify(failure.message ?: "Operation failed")
            } finally {
                launching = false
                if (!destroyed) mutableState.value = state.value.copy(busy = false)
            }
        }
    }
    private suspend fun eject(message: String? = null, animate: Boolean = true) {
        val hadSession = state.value.screen == CollectionScreen.Emulator
        // Leaving a game the player can see closes the screen first; the library is shown again once it has drawn.
        val covered = foreground && (hadSession || state.value.curtain)
        if (covered && animate) closeCurtain()
        playJob?.cancel(); savePlay(false); playActive = false
        rememberDisc()
        engine.stop()
        if (sessionStore != null && message == null && animate && engine.status.value.state == SessionState.Idle) {
            withContext(NonCancellable + Dispatchers.IO) { sessionStore.clear() }
            mutableState.value = state.value.copy(resumeSession = null)
        }
        playingId = null
        previewGameId = null
        renderers.releaseEmulator()
        renderers.releaseLibrary()
        val failure = engine.status.value.error ?: message
        if (engine.status.value.state == SessionState.Error) engine.stop()
        val shouldAnimate = animate && foreground && hadSession && !state.value.reducedMotion
        mutableState.value = state.value.copy(screen = if (shouldAnimate) CollectionScreen.Ejecting else CollectionScreen.Library,
            rendererReady = false, viewportGeneration = state.value.viewportGeneration + 1, labelDraft = null, message = failure, curtain = covered)
        if (shouldAnimate) { delay(dev.cartridge.domain.MediaMotion.EJECT_MS.toLong()); mutableState.value = state.value.copy(screen = CollectionScreen.Library) }
        refreshLibrary()
    }
    fun foreground() {
        foreground = true
        scope.launch {
            backgroundJob?.join()
            if (resumeAfterBackground && foreground && !destroyed) {
                resumeAfterBackground = false
                resumeSavedSession()
            }
        }
    }
    /**
     * A console whose whole machine cannot be captured (its core's `snapshots` is Never) has no checkpoint to come
     * back from, so leaving the app must not end its game: sleep is how a handheld is put down.
     */
    private fun keptWhileAway(): Boolean {
        val playing = playingId?.let { id -> state.value.allGames.firstOrNull { it.id == id } } ?: return false
        return playing.system.core.snapshots == dev.cartridge.domain.Snapshots.Never &&
            engine.status.value.state in setOf(SessionState.Running, SessionState.Paused)
    }
    fun background() {
        foreground = false
        if (destroyed || backgroundJob?.isActive == true) return
        val pending = actionJob
        if (launching) pending?.cancel()
        backgroundJob = scope.launch {
            pending?.join() // Complete writes; do not cancel a user's state save midway through ejection.
            settleFocus()
            if (!destroyed && (engine.status.value.state != SessionState.Idle || state.value.screen == CollectionScreen.Emulator)) {
                mutableState.value = state.value.copy(busy = true)
                try {
                    if (sessionStore == null) eject("Session ended when the app left the foreground.", animate = false)
                    else if (keptWhileAway()) {
                        // Nothing to bring this game back from: it stays loaded, paused behind its pause menu, for as
                        // long as Android keeps the app. Its battery save is written by the pause, as at any pause. A
                        // frozen game comes back to the menu too, at normal speed.
                        if (engine.status.value.state == SessionState.Running) { playJob?.cancel(); savePlay(false); playActive = false; engine.pause() }
                        else if (engine.status.value.frozen) engine.pause()
                    }
                    else {
                        var checkpointFailure: Exception? = null
                        try { engine.pause(); engine.checkpointSession() }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; checkpointFailure = failure }
                        playJob?.cancel(); savePlay(false); playActive = false
                        rememberDisc()
                        engine.stop(); playingId = null
                        if (engine.status.value.state == SessionState.Error) {
                            checkpointFailure = IllegalStateException(engine.status.value.error)
                            engine.stop()
                        }
                        renderers.releaseEmulator(); renderers.releaseLibrary()
                        mutableState.value = state.value.copy(screen = CollectionScreen.Library, rendererReady = false,
                            viewportGeneration = state.value.viewportGeneration + 1, curtain = false,
                            message = checkpointFailure?.let { "Session checkpoint failed: ${it.message}" })
                        readResume()
                        resumeAfterBackground = checkpointFailure == null && state.value.resumeSession != null
                    }
                }
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
