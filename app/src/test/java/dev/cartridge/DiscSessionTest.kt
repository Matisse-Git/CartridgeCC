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

/** ADR 0032: a game of several discs starts on the disc asked for, changes disc from its pause menu and remembers it. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscSessionTest {
    private val ids = (1..3).map { it.toString().repeat(64) }
    private val game = LibraryGame(ids[0], "Saga", "content://saga", system = GameSystem.PS1, fileName = "Saga (Disc 1)",
        discs = listOf(GameDisc(ids[0], 1, "Saga (Disc 1)", "content://1", GameDisc.NAMES), GameDisc(ids[1], 3, "Saga (Disc 3)", "content://3", GameDisc.NAMES)),
        disc = 1)

    private inner class Library : CollectionRepository {
        override val snapshots = MutableStateFlow(LibrarySnapshot(listOf(game), 0))
        val requested = mutableListOf<Int?>()
        val remembered = mutableListOf<Pair<Int, Int>>()
        val changes = mutableListOf<String>()
        override suspend fun initialize() = snapshots.value
        override suspend fun assign(slot: Int, game: LibraryGame) = Unit
        override suspend fun add(game: LibraryGame, restoreRemoved: Boolean) = false
        override suspend fun remove(gameId: String) = Unit
        override suspend fun restore(gameId: String, position: Int) = Unit
        override suspend fun select(slot: Int) = Unit
        override suspend fun setLabel(gameId: String, label: LabelRef?) = Unit
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) = Unit
        override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>) = 0
        override suspend fun setMedia(gameId: String, media: MediaSelection) = Unit
        override suspend fun undoEnrichment(gameId: String) = false
        override suspend fun setFavorite(gameId: String, favorite: Boolean) = Unit
        override suspend fun setQuery(query: LibraryQuery) = Unit
        override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) = Unit
        override suspend fun facts(gameId: String) = LibraryFacts(true, "Fixture", 0, 0)
        override suspend fun gameRequest(game: LibraryGame, disc: Int?): GameRequest {
            requested += disc
            val files = game.discs.map { File("${it.id}.cue") }
            val start = disc ?: game.disc
            return GameRequest(files[start], game.id, game.system, discs = files, disc = start)
        }
        override suspend fun setDisc(gameId: String, disc: Int, count: Int) { remembered += disc to count }
        private fun undo() = DiscUndo(game.id, emptyList(), emptySet(), emptySet(), emptySet(), emptyList())
        override suspend fun addDisc(gameId: String, discId: String): DiscUndo { changes += "add $discId"; return undo() }
        override suspend fun separateDiscs(gameId: String): DiscUndo { changes += "separate"; return undo() }
        override suspend fun undoDiscs(undo: DiscUndo) { changes += "undo" }
    }
    private class Engine : EmulationEngine {
        override val status = MutableStateFlow(EngineStatus())
        var started: GameRequest? = null
        val changed = mutableListOf<Int>()
        override suspend fun prepare(game: GameRequest) { started = game; status.value = EngineStatus(SessionState.Running, disc = game.disc, discs = game.discs.size) }
        override suspend fun pause() { status.value = status.value.copy(state = SessionState.Paused) }
        override suspend fun resume() { status.value = status.value.copy(state = SessionState.Running) }
        override suspend fun stop() { status.value = EngineStatus() }
        override suspend fun changeDisc(disc: Int) { check(status.value.state == SessionState.Paused); changed += disc; status.value = status.value.copy(disc = disc) }
        override suspend fun saveState() = Unit
        override suspend fun loadState() = Unit
        override suspend fun persistSaveRam() = Unit
        override suspend fun configure(options: Map<String, String>) = Unit
        override fun key(action: Int, androidKeyCode: Int) = Unit
        override fun motion(x: Float, y: Float) = Unit
    }
    private class Surfaces : RendererHandoff {
        override fun releaseLibrary() = Unit
        override fun releaseEmulator() = Unit
    }

    @Test fun aGameStartsOnTheDiscAskedForAndOtherwiseOnTheOneLastInTheDrive() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0)
        coordinator.insert(); runCurrent()
        assertEquals(listOf<Int?>(null), library.requested); assertEquals(1, engine.started!!.disc); assertEquals(2, engine.started!!.discs.size)
        coordinator.pause(); runCurrent(); coordinator.menuAction(3); runCurrent()
        // The disc in the drive is what the next start uses.
        assertEquals(1 to 2, library.remembered.last())
        coordinator.rendererReady(coordinator.state.value.viewportGeneration)
        coordinator.insert(0); runCurrent()
        assertEquals(0, library.requested.last()); assertEquals(0, engine.started!!.disc)
    }

    @Test fun changingDiscResumesTheGameNamesTheDiscForAMomentAndRemembersIt() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent(); coordinator.rendererReady(0)
        coordinator.insert(0); runCurrent()
        // Only from the pause menu.
        coordinator.changeDisc(1); runCurrent()
        assertTrue(engine.changed.isEmpty())
        coordinator.pause(); runCurrent()
        coordinator.changeDisc(5); runCurrent()
        assertTrue("No such disc", engine.changed.isEmpty())
        coordinator.changeDisc(1); runCurrent()
        assertEquals(listOf(1), engine.changed); assertEquals(SessionState.Running, engine.status.value.state)
        assertEquals("The badge names the disc by its number", 3, coordinator.state.value.discBadge)
        assertEquals(1 to 2, library.remembered.last())
        advanceTimeBy(2_001); runCurrent()
        assertNull(coordinator.state.value.discBadge)
    }

    @Test fun discChangesByHandCanBeUndoneOnce() = runTest {
        val library = Library(); val engine = Engine()
        val coordinator = CollectionCoordinator(engine, library, Surfaces(), backgroundScope, reducedMotion = true)
        runCurrent()
        coordinator.addDisc(ids[2]); runCurrent()
        assertEquals(game.id, coordinator.state.value.discUndo)
        coordinator.undoDiscs(); runCurrent()
        assertNull(coordinator.state.value.discUndo)
        coordinator.undoDiscs(); runCurrent()
        assertEquals(listOf("add ${ids[2]}", "undo"), library.changes)
        coordinator.separateDiscs(); runCurrent()
        assertEquals("separate", library.changes.last()); assertEquals(game.id, coordinator.state.value.discUndo)
    }
}
