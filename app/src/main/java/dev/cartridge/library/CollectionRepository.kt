package dev.cartridge.library

import dev.cartridge.emulation.GameRequest
import kotlinx.coroutines.flow.Flow

enum class LabelFit { Fit, Crop }
data class LabelRef(val assetId: String, val fit: LabelFit = LabelFit.Fit, val quarterTurns: Int = 0, val sourceUri: String? = null) {
    init { require(assetId.matches(Regex("[a-f0-9]{64}"))); require(quarterTurns in 0..3) }
    val textureKey: String get() = "$assetId:${fit.name}:$quarterTurns"
}
data class LibraryGame(val id: String, val title: String, val sourceUri: String, val label: LabelRef? = null) {
    init { require(id.matches(Regex("[a-f0-9]{64}"))) }
}
data class LibrarySnapshot(val slots: List<LibraryGame?> = List(3) { null }, val selectedSlot: Int = 0) {
    init { require(slots.size == 3); require(selectedSlot in 0..2) }
}

/** ROM identity, persisted choices and Android storage/rendering remain separate. */
interface CollectionRepository {
    val snapshots: Flow<LibrarySnapshot>
    suspend fun initialize(): LibrarySnapshot
    suspend fun assign(slot: Int, game: LibraryGame)
    suspend fun select(slot: Int)
    suspend fun setLabel(gameId: String, label: LabelRef?)
    suspend fun gameRequest(game: LibraryGame): GameRequest
}
