package dev.cartridge.session

import dev.cartridge.domain.Selection
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EmulationEngine
import dev.cartridge.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

enum class CollectionScreen { Library, LibraryMenu, Inserting, Handoff, Emulator, Ejecting, LabelPreview }
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
) {
    val selectedGame get() = slots[selection.index]
    val title get() = selectedGame?.title ?: "Empty cartridge ${selection.index + 1}"
    val labels get() = slots.subList(windowStart, (windowStart + 3).coerceAtMost(slots.size)).map { game ->
        if (game?.id == labelDraft?.gameId && labelDraft != null) labelDraft.label else game?.label }
    val windowStart get() = (selection.index - 1).coerceIn(0, (slots.size - 3).coerceAtLeast(0))
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
) {
    private val mutableState = MutableStateFlow(CollectionState(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut))
    val state = mutableState.asStateFlow()
    private var actionJob: Job? = null
    private var focusJob: Job? = null
    private var backgroundJob: Job? = null
    private var launching = false
    private var foreground = true
    private var destroyed = false
    private val observers = mutableListOf<Job>()

    init {
        observers += scope.launch {
            try {
                val initial = repository.initialize()
                mutableState.value = state.value.copy(slots = initial.slots, selection = Selection(initial.selectedSlot, initial.slots.size), loaded = true)
                repository.snapshots.collect { snapshot ->
                    val membershipChanged = snapshot.slots.map { it?.id } != state.value.slots.map { it?.id }
                    mutableState.value = state.value.copy(slots = snapshot.slots,
                        selection = Selection(if (membershipChanged) snapshot.selectedSlot else state.value.selection.index.coerceAtMost(snapshot.slots.lastIndex), snapshot.slots.size))
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = state.value.copy(message = "Cannot read the local library: ${failure.message}")
            }
        }
        observers += scope.launch {
            engine.status.collect { status ->
                if (status.state == SessionState.Error && !state.value.busy && !destroyed) {
                    runAction { eject(status.error, animate = false) }
                }
            }
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
    fun navigate(delta: Int) {
        if (destroyed || !foreground || state.value.busy || !state.value.loaded) return
        val current = state.value
        if (current.screen == CollectionScreen.LibraryMenu) {
            mutableState.value = current.copy(menuIndex = (current.menuIndex + delta).coerceIn(0, 2))
        } else if (current.screen == CollectionScreen.LabelPreview || engine.status.value.state == SessionState.Paused) {
            mutableState.value = current.copy(menuIndex = (current.menuIndex + delta).coerceIn(0, 3))
        } else if (current.screen == CollectionScreen.Library) {
            val selection = current.selection.move(delta)
            mutableState.value = current.copy(selection = selection, message = null)
            focusJob?.cancel()
            focusJob = scope.launch {
                try { repository.select(selection.index) }
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
                val entry = RemovedEntry(game.id, state.value.selection.index)
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
        }
    }
    private suspend fun refreshLibrary() {
        val snapshot = repository.initialize()
        mutableState.value = state.value.copy(slots = snapshot.slots, selection = Selection(snapshot.selectedSlot, snapshot.slots.size))
    }
    fun settings(reducedMotion: Boolean = state.value.reducedMotion, alternateShortcut: Boolean = state.value.alternateShortcut) {
        if (!state.value.busy) mutableState.value = state.value.copy(reducedMotion = reducedMotion, alternateShortcut = alternateShortcut)
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
                if (!selected.reducedMotion) delay(540)
                val readyRequest = request.await()
                mutableState.value = state.value.copy(screen = CollectionScreen.Handoff, rendererReady = false)
                renderers.releaseLibrary()
                mutableState.value = state.value.copy(screen = CollectionScreen.Emulator)
                engine.prepare(readyRequest)
            }
            if (engine.status.value.state != SessionState.Running) error(engine.status.value.error ?: "The core did not start")
        }
    }
    fun pause() {
        if (engine.status.value.state != SessionState.Running) return
        runAction { engine.pause(); mutableState.value = state.value.copy(menuIndex = 0) }
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
            mutableState.value = state.value.copy(screen = CollectionScreen.LabelPreview, labelDraft = LabelDraft(game.id, label), menuIndex = 0, labelPreviewReady = false)
        }
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
                mutableState.value = state.value.copy(screen = CollectionScreen.Library, labelDraft = null,
                    slots = state.value.slots.map { if (it?.id == draft.gameId) it.copy(label = draft.label) else it }, message = "Label applied")
            }
            3 -> cancelLabel()
        }
    }
    fun cancelLabel() {
        if (!state.value.busy) mutableState.value = state.value.copy(screen = CollectionScreen.Library, labelDraft = null, message = null)
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
        engine.stop()
        renderers.releaseEmulator()
        renderers.releaseLibrary()
        val failure = engine.status.value.error ?: message
        if (engine.status.value.state == SessionState.Error) engine.stop()
        val shouldAnimate = animate && foreground && hadSession && !state.value.reducedMotion
        mutableState.value = state.value.copy(screen = if (shouldAnimate) CollectionScreen.Ejecting else CollectionScreen.Library,
            rendererReady = false, viewportGeneration = state.value.viewportGeneration + 1, labelDraft = null, message = failure)
        if (shouldAnimate) { delay(420); mutableState.value = state.value.copy(screen = CollectionScreen.Library) }
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
        observers.forEach(Job::cancel)
    }
}
