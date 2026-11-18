package dev.cartridge

import dev.cartridge.domain.GameSystem
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
        override val snapshots = MutableStateFlow(LibrarySnapshot(listOf(one, two), 1))
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
        override suspend fun gameRequest(game: LibraryGame, disc: Int?): GameRequest {
            check(!requestFailure) { "Missing game" }
            return GameRequest(File("${game.id}.gba"), game.id)
        }
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) = Unit
        override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>): Int { setMetadata(gameId, values); return values.size }
        override suspend fun undoEnrichment(gameId: String) = false
        override suspend fun setMedia(gameId: String, media: MediaSelection) = Unit
        override suspend fun setFavorite(gameId: String, favorite: Boolean) {
            snapshots.value = snapshots.value.copy(slots = snapshots.value.slots.map { if (it?.id == gameId) it.copy(favorite = favorite) else it })
        }
        override suspend fun setQuery(query: LibraryQuery) { snapshots.value = snapshots.value.copy(query = query) }
        var played = 0L
        var historyFailure = false
        val playedById = mutableMapOf<String, Long>()
        override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) {
            check(!historyFailure) { "History store unavailable" }
            played += elapsedMs; playedById[gameId] = (playedById[gameId] ?: 0) + elapsedMs
        }
        override suspend fun facts(gameId: String) = LibraryFacts(true, "Fixture", 0, 0)
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
        // As the real engine: the menu opens over a running or a frozen game at normal speed; a freeze and a resume keep the speed.
        override suspend fun pause() { if (status.value.state == SessionState.Running || status.value.frozen) status.value = EngineStatus(SessionState.Paused) }
        override suspend fun freeze() { if (status.value.state == SessionState.Running) status.value = status.value.copy(state = SessionState.Paused, frozen = true) }
        override suspend fun speed(frames: Int) { if (status.value.state == SessionState.Running) status.value = status.value.copy(speed = frames) }
        override suspend fun resume() { status.value = EngineStatus(SessionState.Running, speed = status.value.speed) }
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
        /** When set, the game stays blank until it completes. */
        var picture: CompletableDeferred<Unit>? = null
        override fun releaseLibrary() { libraryReleases++ }
        override fun releaseEmulator() { emulatorReleases++ }
        override suspend fun awaitPicture() { picture?.await() }
    }
    @Test fun theScreenClosesAfterInsertionAndOpensOnlyOnceTheGameHasAPicture() = runTest {
        val engine = Engine(); val surfaces = Surfaces().apply { picture = CompletableDeferred() }
        val coordinator = CollectionCoordinator(engine, Library(), surfaces, backgroundScope)
        runCurrent(); coordinator.rendererReady(0)
        coordinator.insert(note = "Restoring Quick save"); runCurrent()
        assertEquals(CollectionScreen.Inserting, coordinator.state.value.screen); assertFalse(coordinator.state.value.curtain)
        // The Launch screen names the game and what its start does from the first moment.
        val game = coordinator.state.value.selectedGame!!.id
        assertEquals(dev.cartridge.session.Launch(game, "Restoring Quick save", 0), coordinator.state.value.launch)
        advanceTimeBy(dev.cartridge.domain.MediaMotion.INSERT_MS.toLong()); runCurrent()
        // The object has gone; the screen closes over a stage that is still whole.
        assertTrue(coordinator.state.value.curtain); assertEquals(CollectionScreen.Inserting, coordinator.state.value.screen)
        assertEquals(0, surfaces.libraryReleases); assertEquals(0, engine.prepares)
        advanceTimeBy(dev.cartridge.domain.MediaMotion.CURTAIN_MS.toLong()); runCurrent()
        assertEquals(1, surfaces.libraryReleases); assertEquals(CollectionScreen.Emulator, coordinator.state.value.screen)
        // Running, but nothing drawn yet: still closed, and still not the player's to control.
        assertEquals(SessionState.Running, engine.status.value.state)
        assertTrue(coordinator.state.value.curtain); assertTrue(coordinator.state.value.busy)
        assertEquals(2, coordinator.state.value.launch?.step)
        surfaces.picture!!.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.curtain); assertFalse(coordinator.state.value.busy); assertNull(coordinator.state.value.launch)
        // Leaving closes it again before the game is taken down, and the rebuilt library opens it.
        coordinator.pause(); runCurrent(); coordinator.menuAction(3); runCurrent()
        assertTrue(coordinator.state.value.curtain); assertEquals(0, surfaces.emulatorReleases); assertEquals(CollectionScreen.Emulator, coordinator.state.value.screen)
        advanceTimeBy(dev.cartridge.domain.MediaMotion.CURTAIN_MS.toLong()); runCurrent()
        assertEquals(1, surfaces.emulatorReleases); assertEquals(CollectionScreen.Ejecting, coordinator.state.value.screen)
        assertTrue(coordinator.state.value.curtain); assertFalse(coordinator.state.value.rendererReady)
        coordinator.rendererReady(0); assertTrue("A stale stage must not open the screen", coordinator.state.value.curtain)
        coordinator.rendererReady(coordinator.state.value.viewportGeneration)
        assertFalse(coordinator.state.value.curtain); assertTrue(coordinator.state.value.rendererReady)
        advanceTimeBy(dev.cartridge.domain.MediaMotion.EJECT_MS.toLong()); runCurrent()
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen); assertFalse(coordinator.state.value.busy)
        coordinator.destroy()
    }
    @Test fun whatTheEngineSaysAboutAStartIsShownOnce() = runTest {
        val engine = Engine()
        val coordinator = CollectionCoordinator(engine, Library(), Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        assertNull(coordinator.state.value.message)
        engine.status.value = EngineStatus(SessionState.Running, notice = "The save state could not be loaded"); runCurrent()
        assertEquals("The save state could not be loaded", coordinator.state.value.message)
        // The same status with another field changed is not a second announcement.
        coordinator.dismissMessage()
        engine.status.value = engine.status.value.copy(saveWarning = "Checkpoint failed"); runCurrent()
        assertNull(coordinator.state.value.message)
        // A later start may say it again.
        engine.status.value = EngineStatus(SessionState.Paused); runCurrent()
        engine.status.value = EngineStatus(SessionState.Running, notice = "The save state could not be loaded"); runCurrent()
        assertEquals("The save state could not be loaded", coordinator.state.value.message)
        coordinator.destroy()
    }
    @Test fun aFailedStartKeepsTheScreenClosedUntilTheLibraryIsDrawnAgain() = runTest {
        val library = Library().apply { requestFailure = true }
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0)
        coordinator.insert(); runCurrent()
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen); assertNotNull(coordinator.state.value.launchFailure)
        assertTrue(coordinator.state.value.curtain)
        coordinator.rendererReady(coordinator.state.value.viewportGeneration)
        assertFalse(coordinator.state.value.curtain)
        coordinator.destroy()
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
        coordinator.labelsReady(0, listOf(null, label.textureKey(dev.cartridge.domain.Media.HandheldBox), null))
        coordinator.labelAction(0)
        assertFalse(coordinator.state.value.labelPreviewReady)
        coordinator.labelsReady(0, listOf(null, label.textureKey(dev.cartridge.domain.Media.HandheldBox), null))
        assertFalse(coordinator.state.value.labelPreviewReady) // Stale GPU acknowledgement.
        coordinator.cancelLabel()
        assertEquals(0, library.labelWrites)
        coordinator.previewLabel { label }; runCurrent()
        coordinator.labelsReady(0, listOf(null, label.textureKey(dev.cartridge.domain.Media.HandheldBox), null))
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
        assertEquals(7, coordinator.state.value.selection.count)
        assertEquals(6, coordinator.state.value.selection.index)
        coordinator.navigate(20); runCurrent()
        assertEquals(6, coordinator.state.value.selection.index)
        assertEquals(2, coordinator.state.value.windowStart); assertEquals(5, coordinator.state.value.labels.size)
        val label = LabelRef("e".repeat(64))
        coordinator.previewLabel { label }; runCurrent()
        coordinator.labelsReady(0, List(5) { if (it == 4) label.textureKey(dev.cartridge.domain.Media.HandheldBox) else null }, firstIndex = 0)
        assertFalse(coordinator.state.value.labelPreviewReady)
        coordinator.labelsReady(0, List(5) { if (it == 4) label.textureKey(dev.cartridge.domain.Media.HandheldBox) else null }, firstIndex = 2)
        assertTrue(coordinator.state.value.labelPreviewReady)
        coordinator.destroy()
    }
    @Test fun collectionMenuRemovalAndUndoRestoreIdentityAndPositionWithoutStartingEngine() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope)
        runCurrent()
        coordinator.hideSelected(); runCurrent()
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        assertEquals(1, coordinator.state.value.slots.size)
        assertEquals(library.two.id, coordinator.state.value.removedEntry!!.gameId)
        coordinator.undoHide(); runCurrent()
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
        assertEquals(3, coordinator.state.value.slots.size)
        assertFalse(coordinator.state.value.scanning)
        assertFalse(coordinator.state.value.busy)
        assertEquals(0, engine.stops)
        coordinator.destroy()
    }

    @Test fun sortingKeepsIdentityAndNavigationPersistsCanonicalIndex() = runTest {
        val library = Library()
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope)
        runCurrent()
        coordinator.query(LibraryQuery(LibrarySort.Title)); runCurrent()
        assertEquals(library.two.id, coordinator.state.value.selectedGame!!.id)
        coordinator.navigate(-1); runCurrent()
        assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        // The stored selection follows once browsing has settled, not on the step itself.
        assertEquals(1, library.snapshots.value.selectedSlot)
        advanceTimeBy(500); runCurrent()
        assertEquals(0, library.snapshots.value.selectedSlot)
        coordinator.query(LibraryQuery(search = "unmatched")); runCurrent()
        assertNull(coordinator.state.value.selectedGame)
        assertEquals(2, coordinator.state.value.allGames.size)
        coordinator.query(LibraryQuery()); runCurrent()
        assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        coordinator.destroy()
    }
    @Test fun aFreezeStopsTheGameWithNoMenuKeepsItsSpeedAndIsNotPlayTime() = runTest {
        val library = Library(); val engine = Engine(); var now = 0L
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true, monotonicMillis = { now })
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        coordinator.toggleFastForward(3); runCurrent()
        assertEquals(3, engine.status.value.speed)
        now = 1000; coordinator.toggleFreeze(); runCurrent()
        assertEquals(SessionState.Paused, engine.status.value.state); assertTrue(engine.status.value.frozen)
        assertEquals(1000L, library.played)
        // Only the combos work on a frozen game, and fast-forward is not one of them.
        coordinator.toggleFastForward(3); runCurrent()
        assertTrue(engine.status.value.frozen); assertEquals(3, engine.status.value.speed)
        now = 60_000; coordinator.toggleFreeze(); runCurrent()
        assertEquals(SessionState.Running, engine.status.value.state); assertFalse(engine.status.value.frozen)
        assertEquals("It goes on at the speed it had", 3, engine.status.value.speed)
        now = 60_500; coordinator.toggleFastForward(3); runCurrent()
        assertEquals(1, engine.status.value.speed)
        coordinator.pause(); runCurrent()
        assertEquals("Frozen time is not play time; fast time is", 1500L, library.played)
        coordinator.menuAction(0); runCurrent()
        // The pause menu over a frozen game: the same frame, no freeze, normal speed.
        coordinator.toggleFastForward(2); runCurrent(); coordinator.toggleFreeze(); runCurrent()
        coordinator.pause(); runCurrent()
        assertEquals(SessionState.Paused, engine.status.value.state); assertFalse(engine.status.value.frozen); assertEquals(1, engine.status.value.speed)
        // The freeze combo does not close the menu, and Resume goes on at normal speed.
        coordinator.toggleFreeze(); runCurrent()
        assertEquals(SessionState.Paused, engine.status.value.state); assertFalse(engine.status.value.frozen)
        coordinator.menuAction(0); runCurrent()
        assertEquals(SessionState.Running, engine.status.value.state); assertEquals(1, engine.status.value.speed)
        coordinator.destroy()
    }
    @Test fun aQuickSaveKeepsAGameFrozenAndAQuickLoadLetsItGoOn() = runTest {
        val engine = Engine()
        val coordinator = CollectionCoordinator(engine, Library(), Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        coordinator.toggleFreeze(); runCurrent()
        coordinator.quickSave(); runCurrent()
        assertEquals(1, engine.saves); assertTrue(engine.status.value.frozen); assertEquals("Quick saved", coordinator.state.value.message)
        coordinator.quickLoad(); runCurrent()
        assertEquals(SessionState.Running, engine.status.value.state); assertFalse(engine.status.value.frozen)
        // From the pause menu a load still follows the user's setting.
        coordinator.pause(); runCurrent(); coordinator.quickLoad(resume = false); runCurrent()
        assertEquals(SessionState.Paused, engine.status.value.state)
        coordinator.destroy()
    }
    @Test fun fastForwardIsNotOfferedWhereItsEngineHasNotBeenRunFaster() = runTest {
        val library = Library(); val engine = Engine()
        val dreamcast = LibraryGame("d".repeat(64), "Disc", "content://disc", system = GameSystem.DREAMCAST)
        library.snapshots.value = LibrarySnapshot(listOf(library.one, dreamcast), 1)
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        coordinator.toggleFastForward(2); runCurrent()
        assertEquals(1, engine.status.value.speed); assertEquals("Fast-forward isn't available for Dreamcast", coordinator.state.value.message)
        // Freezing is for every console.
        coordinator.toggleFreeze(); runCurrent()
        assertTrue(engine.status.value.frozen)
        assertEquals(listOf(GameSystem.DREAMCAST, GameSystem.PS2, GameSystem.GAMECUBE), GameSystem.entries.filter { !it.core.fastForward })
        coordinator.destroy()
    }
    @Test fun historyExcludesPauseAndFinishesBeforeEjection() = runTest {
        val library = Library(); val engine = Engine(); var now = 0L
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true, monotonicMillis = { now })
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        now = 1200; coordinator.pause(); runCurrent()
        assertEquals(1200L, library.played)
        now = 20_000; coordinator.menuAction(0); runCurrent()
        now = 21_300; coordinator.pause(); runCurrent()
        coordinator.menuAction(3); runCurrent()
        assertEquals(2500L, library.played)
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen)
        coordinator.destroy()
    }
    @Test fun failedHistoryRetriesForItsOwnGameAcrossSessions() = runTest {
        val library = Library().apply { historyFailure = true }; val engine = Engine(); var now = 0L
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true, monotonicMillis = { now })
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        now = 1000; coordinator.pause(); runCurrent(); coordinator.menuAction(3); runCurrent()
        assertTrue(coordinator.state.value.historyWarning.orEmpty().contains("History store unavailable"))
        library.historyFailure = false
        coordinator.navigate(-1); runCurrent(); coordinator.rendererReady(coordinator.state.value.viewportGeneration); coordinator.insert(); runCurrent()
        now = 1400; coordinator.pause(); runCurrent(); coordinator.menuAction(3); runCurrent()
        assertEquals(1000L, library.playedById[library.two.id])
        assertEquals(400L, library.playedById[library.one.id])
        coordinator.destroy()
    }
    @Test fun scrapePreviewOutsideTheShelfNeverPersistsTemporaryViewAndRestoresFocus() = runTest {
        val library = Library()
        library.setFavorite(library.one.id, true); library.select(0)
        val query = LibraryQuery(LibrarySort.Title)
        library.setQuery(query)
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, initialShelf = Shelf.Favorites)
        runCurrent()
        assertEquals(Shelf.Favorites, coordinator.state.value.shelf); assertEquals(1, coordinator.state.value.slots.size)
        val draft = LabelRef("e".repeat(64))
        coordinator.previewScrapedLabel(library.two, draft); runCurrent()
        assertEquals(library.two.id, coordinator.state.value.selectedGame!!.id)
        assertEquals(query, library.snapshots.value.query)
        assertEquals(0, library.snapshots.value.selectedSlot)
        coordinator.cancelLabel(); runCurrent()
        assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        assertEquals(1, coordinator.state.value.slots.size); assertEquals(Shelf.Favorites, coordinator.state.value.shelf)
        coordinator.previewScrapedLabel(library.two, draft); runCurrent()
        coordinator.labelsReady(0, listOf(null, draft.textureKey(dev.cartridge.domain.Media.HandheldBox))); coordinator.labelAction(2); runCurrent()
        assertEquals(library.two.id, coordinator.state.value.lastLabelAppliedTo)
        assertEquals(draft, library.snapshots.value.slots[1]!!.label)
        assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        coordinator.previewScrapedLabel(library.two, draft); runCurrent(); coordinator.destroy()
        val reopened = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, initialShelf = Shelf.Favorites); runCurrent()
        assertEquals(query, reopened.state.value.query)
        assertEquals(library.one.id, reopened.state.value.selectedGame!!.id)
        reopened.destroy()
    }
    private fun disc(index: Int, title: String = "Disc $index") = LibraryGame((index + 0xd0).toString(16).padStart(64, '0'), title, "content://disc$index", system = GameSystem.PS1)
    @Test fun turningToAShelfStartsAtItsFirstGameAndNeverRewritesStoredOrder() = runTest {
        val library = Library(); repeat(3) { library.add(disc(it)) }
        val remembered = mutableListOf<Pair<Shelf, Map<String, String>>>()
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, onBrowseChanged = { shelf, focus -> remembered += shelf to focus })
        runCurrent()
        // Opens on the console and the game of the stored selection; a single unified view appears once there are two consoles.
        assertEquals(Shelf.Console(GameSystem.GBA), coordinator.state.value.shelf); assertEquals(library.two.id, coordinator.state.value.selectedGame!!.id)
        assertEquals(listOf(Shelf.Console(GameSystem.GBA), Shelf.Console(GameSystem.PS1), Shelf.All), coordinator.state.value.shelves)
        assertEquals(2, coordinator.state.value.slots.size)
        coordinator.switchShelf(1); runCurrent()
        assertEquals(Shelf.Console(GameSystem.PS1), coordinator.state.value.shelf); assertEquals(3, coordinator.state.value.slots.size)
        assertEquals(disc(0).id, coordinator.state.value.selectedGame!!.id)
        coordinator.navigate(2); runCurrent()
        assertEquals(disc(2).id, coordinator.state.value.selectedGame!!.id)
        advanceTimeBy(500); runCurrent(); assertEquals(4, library.snapshots.value.selectedSlot)
        coordinator.switchShelf(-1); runCurrent()
        // Not the game this shelf showed last: a shelf the player turns to opens on its first game.
        assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        coordinator.switchShelf(1); runCurrent()
        assertEquals(disc(0).id, coordinator.state.value.selectedGame!!.id)
        coordinator.navigate(1); runCurrent()
        coordinator.switchShelf(1); runCurrent()
        // The unified shelf too, even though it holds the disc that was just focused.
        assertEquals(Shelf.All, coordinator.state.value.shelf); assertEquals(library.one.id, coordinator.state.value.selectedGame!!.id)
        assertEquals(0, coordinator.state.value.selection.index); assertEquals(5, coordinator.state.value.slots.size)
        coordinator.switchShelf(1); runCurrent(); assertEquals(Shelf.Console(GameSystem.GBA), coordinator.state.value.shelf) // Wraps.
        assertEquals(0, coordinator.state.value.selection.index)
        assertEquals(listOf(library.one.id, library.two.id, disc(0).id, disc(1).id, disc(2).id), library.snapshots.value.slots.map { it!!.id })
        assertEquals(Shelf.Console(GameSystem.GBA), remembered.last().first); assertEquals(disc(1).id, remembered.last().second["PS1"])
        coordinator.destroy()
        // Opening the console again still returns to the shelf and the game it was closed on.
        val reopened = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, initialShelf = Shelf.Console(GameSystem.PS1), initialShelfFocus = remembered.last().second)
        runCurrent()
        assertEquals(disc(1).id, reopened.state.value.selectedGame!!.id)
        reopened.destroy()
    }
    @Test fun storedFavouritesFilterBecomesTheFavoritesShelfOnce() = runTest {
        val library = Library(); library.setFavorite(library.two.id, true); library.setQuery(LibraryQuery(LibrarySort.Title, favoritesOnly = true))
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope)
        runCurrent()
        assertEquals(Shelf.Favorites, coordinator.state.value.shelf); assertEquals(listOf(library.two.id), coordinator.state.value.slots.map { it!!.id })
        assertEquals(LibraryQuery(LibrarySort.Title), library.snapshots.value.query) // Sort survives; the hidden flag does not.
        coordinator.favorite(); runCurrent()
        // The last favourite leaving empties the shelf, so the view follows the game to its console.
        assertEquals(Shelf.Console(GameSystem.GBA), coordinator.state.value.shelf); assertEquals(library.two.id, coordinator.state.value.selectedGame!!.id)
        assertFalse(Shelf.Favorites in coordinator.state.value.shelves)
        coordinator.destroy()
    }
    @Test fun searchSpansEveryConsoleAndLeavingItClearsTheStoredQuery() = runTest {
        val library = Library(); library.add(disc(0, "Second Orbit"))
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope)
        runCurrent()
        coordinator.query(LibraryQuery(search = "second")); runCurrent()
        assertTrue(coordinator.state.value.searching)
        assertEquals(setOf(GameSystem.GBA, GameSystem.PS1), coordinator.state.value.slots.map { it!!.system }.toSet())
        coordinator.switchShelf(1); runCurrent()
        assertFalse(coordinator.state.value.searching); assertEquals("", library.snapshots.value.query.search)
        assertEquals(Shelf.Console(GameSystem.GBA), coordinator.state.value.shelf)
        coordinator.destroy()
    }
    @Test fun failedLaunchOffersRecoveryAndHidingTheLastGameOfAConsoleMovesToAShelfThatExists() = runTest {
        val library = Library().apply { requestFailure = true }; library.add(disc(0))
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0); coordinator.insert(); runCurrent()
        val failure = coordinator.state.value.launchFailure!!
        assertEquals(library.two.id, failure.gameId); assertEquals("Missing game", failure.reason); assertEquals("Missing game", coordinator.state.value.message)
        assertEquals(CollectionScreen.Library, coordinator.state.value.screen); assertFalse(coordinator.state.value.busy)
        assertNull("A failed start leaves no Launch screen behind", coordinator.state.value.launch)
        coordinator.dismissLaunchFailure(); assertNull(coordinator.state.value.launchFailure)
        coordinator.switchShelf(1); runCurrent(); assertEquals(disc(0).id, coordinator.state.value.selectedGame!!.id)
        coordinator.hideSelected(); runCurrent()
        assertEquals(Shelf.Console(GameSystem.GBA), coordinator.state.value.shelf); assertEquals(listOf(Shelf.Console(GameSystem.GBA)), coordinator.state.value.shelves)
        assertNotNull(coordinator.state.value.selectedGame)
        coordinator.undoHide(); runCurrent()
        assertEquals(disc(0).id, coordinator.state.value.selectedGame!!.id); assertEquals(Shelf.Console(GameSystem.PS1), coordinator.state.value.shelf)
        coordinator.destroy()
    }

    @Test fun layoutsShareIdentityNavigationAndGridPreviewAcknowledgements() = runTest {
        val library = Library()
        repeat(18) { library.add(library.one.copy(id = (it + 1).toString(16).padStart(64, '0'))) }
        val saved = mutableListOf<dev.cartridge.domain.CollectionLayout>()
        val coordinator = CollectionCoordinator(Engine(), library, Surfaces(), backgroundScope, onLayoutChanged = saved::add)
        runCurrent()
        val identity = coordinator.state.value.selectedGame!!.id
        coordinator.settings(layout = dev.cartridge.domain.CollectionLayout.Grid); runCurrent()
        assertEquals(identity, coordinator.state.value.selectedGame!!.id)
        coordinator.navigateSpatial(0, 1); runCurrent(); assertEquals(4, coordinator.state.value.selection.index)
        coordinator.navigate(15); runCurrent()
        assertEquals(6, coordinator.state.value.windowStart); assertEquals(14, coordinator.state.value.labels.size)
        val label = LabelRef("e".repeat(64)); coordinator.previewLabel { label }; runCurrent()
        coordinator.labelsReady(0, List(14) { if (it == 13) label.textureKey(dev.cartridge.domain.Media.HandheldBox) else null }, firstIndex = 6)
        assertTrue(coordinator.state.value.labelPreviewReady)
        coordinator.cancelLabel(); runCurrent()
        coordinator.settings(layout = dev.cartridge.domain.CollectionLayout.Vertical); runCurrent()
        coordinator.navigateSpatial(-1, -1); runCurrent(); assertEquals(18, coordinator.state.value.selection.index)
        assertEquals(listOf(dev.cartridge.domain.CollectionLayout.Grid, dev.cartridge.domain.CollectionLayout.Vertical), saved)
        advanceTimeBy(500); runCurrent()
        assertEquals(18, library.snapshots.value.selectedSlot)
        coordinator.destroy()
    }

}
