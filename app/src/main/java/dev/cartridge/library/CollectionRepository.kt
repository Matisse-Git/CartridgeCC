package dev.cartridge.library

import dev.cartridge.emulation.GameRequest
import kotlinx.coroutines.flow.Flow

enum class LabelFit { Fit, Crop }
data class LabelRef(val assetId: String, val fit: LabelFit = LabelFit.Fit, val quarterTurns: Int = 0,
    val sourceUri: String? = null, val provenance: AssetProvenance? = null) {
    init { require(assetId.matches(Regex("[a-f0-9]{64}"))); require(quarterTurns in 0..3) }
    val textureKey: String get() = "$assetId:${fit.name}:$quarterTurns"
}
data class LibraryGame(val id: String, val title: String, val sourceUri: String, val label: LabelRef? = null,
    val metadata: Map<String, MetadataValue> = emptyMap(), val media: Map<String, MediaSelection> = emptyMap(),
    val favorite: Boolean = false, val playMs: Long = 0, val lastPlayed: Long = 0, val canUndoEnrichment: Boolean = false) {
    init { require(id.matches(Regex("[a-f0-9]{64}"))) }
}
data class LibrarySnapshot(val slots: List<LibraryGame?> = listOf(null), val selectedSlot: Int = 0,
    val query: LibraryQuery = LibraryQuery()) {
    init { require(slots.isNotEmpty()); require(selectedSlot in slots.indices) }
}
data class LibraryFacts(val cachedRom: Boolean, val sourceStatus: String, val batteryAt: Long, val stateAt: Long)

/** ROM identity, persisted choices and Android storage/rendering remain separate. */
interface CollectionRepository {
    val snapshots: Flow<LibrarySnapshot>
    suspend fun initialize(): LibrarySnapshot
    suspend fun assign(slot: Int, game: LibraryGame)
    suspend fun add(game: LibraryGame, restoreRemoved: Boolean = false): Boolean
    /** Hide only the collection entry; never delete ROMs, saves or enrichment. */
    suspend fun remove(gameId: String)
    suspend fun restore(gameId: String, position: Int)
    suspend fun select(slot: Int)
    suspend fun setLabel(gameId: String, label: LabelRef?)
    suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>)
    /** Atomically fill absent fields; existing manual/provider values always win. */
    suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>): Int
    suspend fun setMedia(gameId: String, media: MediaSelection)
    suspend fun undoEnrichment(gameId: String): Boolean
    suspend fun setFavorite(gameId: String, favorite: Boolean)
    suspend fun setQuery(query: LibraryQuery)
    suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long? = null)
    suspend fun facts(gameId: String): LibraryFacts
    suspend fun gameRequest(game: LibraryGame): GameRequest
}
