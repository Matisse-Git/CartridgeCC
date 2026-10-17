package dev.cartridge.emulation

import dev.cartridge.domain.SessionState
import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** [notice] is something about this start worth telling the player once; it is gone at the next state change. */
data class EngineStatus(val state: SessionState = SessionState.Idle, val error: String? = null, val saveWarning: String? = null, val notice: String? = null)
data class GameRequest(val rom: File, val id: String, val system: GameSystem = GameSystem.GBA, val resumeState: ByteArray? = null)

interface EmulationEngine {
    val status: StateFlow<EngineStatus>
    suspend fun prepare(game: GameRequest)
    suspend fun pause()
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
    suspend fun persistSaveRam()
    suspend fun configure(options: Map<String, String>)
    suspend fun checkpointSession() = Unit
    /** A paused game reads its shader choice again (ADR 0027); the new picture shows when it resumes. */
    suspend fun reloadShader() = Unit
}
