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
    suspend fun saveState()
    suspend fun loadState()
    suspend fun persistSaveRam()
    suspend fun configure(options: Map<String, String>)
    suspend fun checkpointSession() = Unit
}
