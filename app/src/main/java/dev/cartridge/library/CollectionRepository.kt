package dev.cartridge.library

import dev.cartridge.emulation.GameRequest
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
import kotlinx.coroutines.flow.Flow

enum class LabelFit { Fit, Crop }
data class LabelRef(val assetId: String, val fit: LabelFit = LabelFit.Fit, val quarterTurns: Int = 0,
    val sourceUri: String? = null, val provenance: AssetProvenance? = null) {
    init { require(assetId.matches(Regex("[a-f0-9]{64}"))); require(quarterTurns in 0..3) }
    val textureKey: String get() = "$assetId:${fit.name}:$quarterTurns"
    /** The image as decoded for the case that carries it: every case has its own label height. */
    fun textureKey(media: Media) = "$textureKey:${media.labelHeight}"
}
data class LibraryGame(val id: String, val title: String, val sourceUri: String, val label: LabelRef? = null,
    val metadata: Map<String, MetadataValue> = emptyMap(), val media: Map<String, MediaSelection> = emptyMap(),
    val favorite: Boolean = false, val playMs: Long = 0, val lastPlayed: Long = 0, val canUndoEnrichment: Boolean = false,
    val system: GameSystem = GameSystem.GBA,
    /** The name the game file was listed under, before any scraped title replaced [title]. Its region tags stay here. */
    val fileName: String = title) {
    init { require(id.matches(Regex("[a-f0-9]{64}"))) }
    /** The object this game is shown as. Presentation only: it follows the console and how the file is named. */
    val shownAs: Media get() = system.media(fileName)
}
data class LibrarySnapshot(val slots: List<LibraryGame?> = listOf(null), val selectedSlot: Int = 0,
    val query: LibraryQuery = LibraryQuery()) {
    init { require(slots.isNotEmpty()); require(selectedSlot in slots.indices) }
}
data class LibraryFacts(val cachedRom: Boolean, val sourceStatus: String, val batteryAt: Long, val stateAt: Long)

/** ROM identity, persisted choices and Android storage/rendering remain separate. */
interface CollectionRepository {
    /** The collection whenever its games or query change. A selection-only write need not be announced. */
    val snapshots: Flow<LibrarySnapshot>
    suspend fun initialize(): LibrarySnapshot
    suspend fun assign(slot: Int, game: LibraryGame)
    suspend fun add(game: LibraryGame, restoreRemoved: Boolean = false): Boolean
    /** Hide only the collection entry; never delete ROMs, saves or enrichment. */
    suspend fun remove(gameId: String)
    suspend fun restore(gameId: String, position: Int)
    /** Entries the owner hid. Their ROMs, saves and enrichment are intact. */
    suspend fun hiddenGames(): List<LibraryGame> = emptyList()
    suspend fun select(slot: Int)
    suspend fun setLabel(gameId: String, label: LabelRef?)
    suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>)
    /** Atomically fill absent fields; existing manual/provider values always win. */
    suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>): Int
    suspend fun setMedia(gameId: String, media: MediaSelection)
    suspend fun undoEnrichment(gameId: String): Boolean
    /**
     * What an automatic scrape chose (ADR 0029), written as one change with one Undo: [label] becomes the case art and
     * [metadata] fills fields, each only where [policy] allows. Returns how many fields and images changed. Only the
     * Room library is atomic; this default is for test doubles.
     */
    suspend fun applyScraped(gameId: String, label: LabelRef?, metadata: Map<String, MetadataValue>, policy: FillPolicy): Int {
        val game = initialize().slots.filterNotNull().firstOrNull { it.id == gameId } ?: error("This game is no longer in the library")
        val fields = metadata.filter { (field, value) -> value.value.isNotBlank() && game.metadata[field]?.value != value.value &&
            policy.replaces(game.metadata[field]?.takeIf { it.value.isNotBlank() }?.automatic) }
        val art = game.label?.takeIf { it.isCaseArt }
        val replace = label != null && game.label?.assetId != label.assetId && policy.replaces(art?.provenance?.automatic)
        if (fields.isNotEmpty()) setMetadata(gameId, fields)
        if (replace) setLabel(gameId, label)
        return fields.size + if (replace) 1 else 0
    }
    suspend fun setFavorite(gameId: String, favorite: Boolean)
    suspend fun setQuery(query: LibraryQuery)
    suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long? = null)
    suspend fun facts(gameId: String): LibraryFacts
    suspend fun gameRequest(game: LibraryGame): GameRequest
}
