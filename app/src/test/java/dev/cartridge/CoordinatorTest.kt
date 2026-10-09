package dev.cartridge

import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.*
import dev.cartridge.library.*
import dev.cartridge.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTest {
    private class Library : CollectionRepository {
        val one = LibraryGame("a".repeat(64), "First", "content://first")
        val two = LibraryGame("b".repeat(64), "Second", "content://second")
        override val snapshots = MutableStateFlow(LibrarySnapshot(listOf(one, two, null), 1))
        var requestFailure = false
        var labelWrites = 0
        override suspend fun initialize() = snapshots.value
        override suspend fun assign(slot: Int, game: LibraryGame) {
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots.toMutableList().also { it[slot] = game })
        }
        override suspend fun select(slot: Int) { snapshots.value = snapshots.value.copy(selectedSlot = slot) }
        private val removed = mutableMapOf<String, LibraryGame>()
        override suspend fun remove(gameId: String) {
            removed[gameId] = snapshots.value.slots.first { it?.id == gameId }!!
            val remaining = snapshots.value.slots.filterNot { it?.id == gameId }
            snapshots.value = LibrarySnapshot(remaining, snapshots.value.selectedSlot.coerceAtMost(remaining.lastIndex))
        }
        override suspend fun restore(gameId: String, position: Int) {
            snapshots.value = LibrarySnapshot(snapshots.value.slots.toMutableList().also { it.add(position, removed.remove(gameId)!!) }, position)
        }
        override suspend fun add(game: LibraryGame, restoreRemoved: Boolean): Boolean {
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots + game)
            return true
        }
        override suspend fun setLabel(gameId: String, label: LabelRef?) {
            labelWrites++
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots.map { if (it?.id == gameId) it.copy(label = label) else it })
        }
        override suspend fun gameRequest(game: LibraryGame): GameRequest {
            check(!requestFailure) { "Missing game" }
            return GameRequest(File("${game.id}.gba"), game.id)
        }
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) = Unit
        override suspend fun setMedia(gameId: String, media: MediaSelection) = Unit
    }
    private class Engine : EmulationEngine {
        override val status = MutableStateFlow(EngineStatus())
        var prepareGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var missingState = false
        var prepares = 0; var stops = 0; var saves = 0
        override suspend fun prepare(game: GameRequest) {
            prepares++; status.value = EngineStatus(SessionState.Preparing)
            prepareGate?.await()
            status.value = EngineStatus(SessionState.Running)
        }
        override suspend fun pause() { if (status.value.state == SessionState.Running) status.value = EngineStatus(SessionState.Paused) }
        override suspend fun resume() { status.value = EngineStatus(SessionState.Running) }
        override suspend fun stop() { stops++; status.value = EngineStatus() }
        override suspend fun saveState() { saveGate?.await(); saves++ }
        override suspend fun loadState() { check(!missingState) { "There is no saved state" } }
        override suspend fun persistSaveRam() = Unit
        override suspend fun configure(options: Map<String, String>) = Unit
        override fun key(action: Int, androidKeyCode: Int) = Unit
        override fun motion(x: Float, y: Float) = Unit
    }
    private class Surfaces : RendererHandoff {
        var libraryReleases = 0; var emulatorReleases = 0
        override fun releaseLibrary() { libraryReleases++ }
        override fun releaseEmulator() { emulatorReleases++ }
    }
    @Test fun restoresFocusAndPreventsQueuedDuplicateLaunches() = runTest {
        val library = Library(); val engine = Engine(); val surfaces = Surfaces()
        val coordinator = CollectionCoordinator(engine, library, surfaces, backgroundScope, reducedMotion = true)
        runCurrent()
        assertEquals(1, coordinator.state.value.selection.index)
        coordinator.rendererReady(0)
        engine.prepareGate = CompletableDeferred()
        repeat(10) { coordinator.insert() }
        runCurrent()
        assertEquals(1, engine.prepares)
        assertEquals(1, surfaces.libraryReleases)
        assertTrue(coordinator.state.value.busy)
        engine.prepareGate!!.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.busy)
        assertEquals(SessionState.Running, engine.status.value.state)
        coordinator.destroy()
    }
    @Test fun backgroundCancelsPreparationAndAllowsFreshRetry() = runTest {
        val library = Library(); val engine = Engine(); val surfaces = Surfaces()
        val coordinator = CollectionCoordinator(engine, library, surfaces, backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0)
        engine.prepareGate = CompletableDeferred()
        coordinator.insert(); runCurrent(); coordinator.background(); runCurrent()
        assertEquals(SessionState.Idle, engine.status.value.state)
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        assertFalse(coordinator.state.value.busy)
        assertEquals(1, coordinator.state.value.selection.index)
        engine.prepareGate = null
        coordinator.foreground(); coordinator.rendererReady(coordinator.state.value.viewportGeneration)
        coordinator.insert(); runCurrent()
        assertEquals(2, engine.prepares)
        assertEquals(SessionState.Running, engine.status.value.state)
        coordinator.destroy()
    }
    @Test fun backgroundWaitsForSaveBeforeDestroyingSession() = runTest {
        val engine = Engine(); val surfaces = Surfaces()
        val coordinator = CollectionCoordinator(engine, Library(), surfaces, backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        coordinator.pause(); runCurrent()
        engine.saveGate = CompletableDeferred()
        coordinator.menuAction(1); coordinator.background(); runCurrent()
        assertEquals(0, engine.stops)
        engine.saveGate!!.complete(Unit); runCurrent()
        assertEquals(1, engine.saves)
        assertEquals(1, engine.stops)
        assertEquals(1, surfaces.emulatorReleases)
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        coordinator.destroy()
    }
    @Test fun fileFailurePreservesFocusAndReturnsToUsableLibrary() = runTest {
        val library = Library().apply { requestFailure = true }
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        assertEquals(1, coordinator.state.value.selection.index)
        assertEquals("Missing game", coordinator.state.value.message)
        assertFalse(coordinator.state.value.busy)
        coordinator.destroy()
    }
    @Test fun labelPreviewRequiresRenderedRecipeAndCancelNeverWrites() = runTest {
        val library = Library()
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope)
        runCurrent()
        val label = LabelRef("c".repeat(64), sourceUri = "content://label")
        coordinator.previewLabel { label }; runCurrent()
        coordinator.labelAction(2); runCurrent()
        assertEquals(0, library.labelWrites)
        coordinator.labelsReady(0, listOf(null, label.textureKey, null))
        coordinator.labelAction(0)
        assertFalse(coordinator.state.value.labelPreviewReady)
        coordinator.labelsReady(0, listOf(null, label.textureKey, null))
        assertFalse(coordinator.state.value.labelPreviewReady) // Stale GPU acknowledgement.
        coordinator.cancelLabel()
        assertEquals(0, library.labelWrites)
        coordinator.previewLabel { label }; runCurrent()
        coordinator.labelsReady(0, listOf(null, label.textureKey, null))
        coordinator.labelAction(2); runCurrent()
        assertEquals(1, library.labelWrites)
        assertNull(library.snapshots.value.slots[0]?.label)
        assertEquals(label, library.snapshots.value.slots[1]?.label)
        coordinator.destroy()
    }
    @Test fun missingSaveStateReportsErrorWithoutEjectingPausedGame() = runTest {
        val engine = Engine(); val surfaces = Surfaces()
        val coordinator = CollectionCoordinator(engine, Library(), surfaces, backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent(); coordinator.pause(); runCurrent()
        engine.missingState = true
        coordinator.menuAction(2); runCurrent()
        assertEquals(SessionState.Paused, engine.status.value.state)
        assertEquals(CollectionScreen.Emulator, coordinator.state.value.screen)
        assertEquals("There is no saved state", coordinator.state.value.message)
        assertEquals(0, surfaces.emulatorReleases)
        assertFalse(coordinator.state.value.busy)
        coordinator.destroy()
    }
    @Test fun explicitAddFocusesNewGameAndAcknowledgesLabelsBeyondThirdSlot() = runTest {
        val library = Library()
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope)
        runCurrent()
        repeat(5) { coordinator.addGame { library.one.copy(id = (it + 1).toString().repeat(64), title = "Added $it") }; runCurrent() }
        assertEquals(8, coordinator.state.value.selection.count)
        assertEquals(7, coordinator.state.value.selection.index)
        coordinator.navigate(20); runCurrent()
        assertEquals(7, coordinator.state.value.selection.index)
        assertEquals(5, coordinator.state.value.windowStart)
        val label = LabelRef("e".repeat(64))
        coordinator.previewLabel { label }; runCurrent()
        coordinator.labelsReady(0, listOf(null, null, label.textureKey), firstIndex = 0)
        assertFalse(coordinator.state.value.labelPreviewReady)
        coordinator.labelsReady(0, listOf(null, null, label.textureKey), firstIndex = 5)
        assertTrue(coordinator.state.value.labelPreviewReady)
        coordinator.destroy()
    }
    @Test fun collectionMenuRemovalAndUndoRestoreIdentityAndPositionWithoutStartingEngine() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope)
        runCurrent()
        coordinator.back(); coordinator.navigate(1); coordinator.select(); runCurrent()
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        assertEquals(2, coordinator.state.value.slots.size)
        assertEquals(library.two.id, coordinator.state.value.removedEntry!!.gameId)
        coordinator.back(); coordinator.navigate(2); coordinator.select(); runCurrent()
        assertEquals(library.two.id, coordinator.state.value.selectedGame!!.id)
        assertEquals(1, coordinator.state.value.selection.index)
        assertNull(coordinator.state.value.removedEntry)
        assertEquals(0, engine.prepares)
        assertEquals(0, engine.stops)
        coordinator.destroy()
    }
    @Test fun cancellingScanKeepsCommittedGamesAndDoesNotStopAnIdleEngine() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope)
        runCurrent()
        coordinator.scanDirectory { progress ->
            library.add(library.one.copy(id = "d".repeat(64)))
            progress("One imported")
            awaitCancellation()
        }
        runCurrent(); coordinator.cancelScan(); runCurrent()
        assertEquals(4, coordinator.state.value.slots.size)
        assertFalse(coordinator.state.value.scanning)
        assertFalse(coordinator.state.value.busy)
        assertEquals(0, engine.stops)
        coordinator.destroy()
    }
}
