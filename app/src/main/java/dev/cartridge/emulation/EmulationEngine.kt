package dev.cartridge.emulation

import dev.cartridge.domain.SessionState
import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * [notice] is something about this start worth telling the player once; it is gone at the next state change. [frozen] is a
 * pause with no menu, from the freeze combo (ADR 0031). [speed] is how many frames run for each one shown, 1 normally.
 */
data class EngineStatus(val state: SessionState = SessionState.Idle, val error: String? = null, val saveWarning: String? = null, val notice: String? = null,
    val frozen: Boolean = false, val speed: Int = 1,
    /** The disc in the drive and how many the engine can change between (ADR 0032); 0 discs where it cannot. */
    val disc: Int = 0, val discs: Int = 0)
/**
 * [discs] are the private copies of a game's discs in order, empty for a game of one disc, and [disc] the one to start on:
 * [rom] is that disc's file. A core that can change discs is started from a playlist of all of them.
 */
data class GameRequest(val rom: File, val id: String, val system: GameSystem = GameSystem.GBA, val resumeState: ByteArray? = null,
    val discs: List<File> = emptyList(), val disc: Int = 0)

interface EmulationEngine {
    val status: StateFlow<EngineStatus>
    suspend fun prepare(game: GameRequest)
    /** Opens the pause menu: a running game stops, a frozen one keeps its frame; both are back at normal speed. */
    suspend fun pause()
    /** A running game stops where it is, at the speed it had, with no menu (ADR 0031). [resume] continues it. */
    suspend fun freeze(): Unit = error("Freezing is not available")
    /** A running game runs [frames] frames for each one shown, with no sound above 1, where its core allows it. */
    suspend fun speed(frames: Int): Unit = error("Fast-forward is not available")
    suspend fun resume()
    suspend fun stop()
    fun key(action: Int, androidKeyCode: Int)
    fun motion(x: Float, y: Float)
    /** A stick of a pad that has them: 0 is the left, 1 the right, each axis -1..1. */
    fun analog(stick: Int, x: Float, y: Float) = Unit
    /** The quick save and quick load: from the pause menu, or during play from their button combos. */
    suspend fun saveState()
    suspend fun loadState()
    /** A paused game's state into its list, as a new save or in place of [replacing]. Returns the name it is listed under. */
    suspend fun saveSlot(replacing: String? = null): String = error("Saved states are not available")
    /** A paused game continues from one of its listed states (docs/SAVE_RELIABILITY). */
    suspend fun loadSlot(name: String): Unit = error("Saved states are not available")
    /** A paused game's drive is opened and [disc] put in it (ADR 0032); the game sees the new disc when it resumes. */
    suspend fun changeDisc(disc: Int): Unit = error("This game has one disc")
    /** A paused game continues from a state its disc [discId] made as a game of its own, with that disc, [disc], in the drive. */
    suspend fun loadDiscState(discId: String, name: String, disc: Int): Unit = error("This game has one disc")
    suspend fun persistSaveRam()
    suspend fun configure(options: Map<String, String>)
    suspend fun checkpointSession() = Unit
    /** A paused game reads its shader choice again (ADR 0027); the new picture shows when it resumes. */
    suspend fun reloadShader() = Unit
}
