package dev.cartridge.library

import dev.cartridge.emulation.GameRequest
import dev.cartridge.domain.DiscGroup
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
    val fileName: String = title,
    /** The discs the game is made of, in order (ADR 0032). Empty for a game of one disc; the first is this game's own. */
    val discs: List<GameDisc> = emptyList(),
    /** The disc last in the drive, by its place in [discs], or in the engine's count for one file that holds several. */
    val disc: Int = 0,
    /** How many discs the engine found in this game's one file the last time it ran; 0 until then. */
    val discCount: Int = 0) {
    init { require(id.matches(Regex("[a-f0-9]{64}"))) }
    /** The object this game is shown as. Presentation only: it follows the console and how the file is named. */
    val shownAs: Media get() = system.media(fileName)
    /** How many discs the player can change between: the game's own discs, or what its one file was found to hold. */
    val discTotal: Int get() = if (discs.size > 1) discs.size else discCount
}
/** One disc of a game made of several: its own content id, number and record. [origin] is how it was found to belong. */
data class GameDisc(val id: String, val number: Int, val fileName: String, val sourceUri: String, val origin: String) {
    val manual get() = origin == MANUAL
    /** The playlist the discs came from, if one said so. */
    val playlist get() = origin.removePrefix(PLAYLIST).takeIf { origin.startsWith(PLAYLIST) }
    companion object { const val NAMES = "names"; const val MANUAL = "manual"; const val PLAYLIST = "playlist:" }
}
/** What a disc change by hand undoes: the discs, separations, hidden entries and shelf order as they were. */
class DiscUndo(val gameId: String, val discs: List<DiscRecord>, val separated: Set<String>, val hidden: Set<String>, val affected: Set<String>, val order: List<String>)
data class LibrarySnapshot(val slots: List<LibraryGame?> = listOf(null), val selectedSlot: Int = 0,
    val query: LibraryQuery = LibraryQuery()) {
    init { require(slots.isNotEmpty()); require(selectedSlot in slots.indices) }
}
data class LibraryFacts(val cachedRom: Boolean, val sourceStatus: String, val batteryAt: Long, val stateAt: Long,
    /** Each disc's file, in the game's order; one entry for a game of one disc (ADR 0032). */
    val files: List<DiscFacts> = emptyList(),
    /** Discs other than the first whose memory card the game could use instead of its own. */
    val cards: List<String> = emptyList(),
    /** The game's own card was replaced by a disc's and is kept: it can be put back. */
    val previousCard: Boolean = false)
/**
 * One disc's file as Details shows it: [file] is where the owner keeps it (its folder and name), [bytes] the size of the
 * game's private copy with its tracks, [tracks] how many track files a sheet names. [cached] is false when the private copy is gone.
 */
data class DiscFacts(val id: String, val file: String, val bytes: Long, val tracks: Int, val cached: Boolean, val sourceMissing: Boolean)

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
    /** The game started on its disc [disc] (ADR 0032), by its place in the game's discs; null is the disc last in the drive. */
    suspend fun gameRequest(game: LibraryGame, disc: Int? = null): GameRequest

    /**
     * Discs a scan found to be one game (ADR 0032). A disc the owner grouped or separated by hand is left as it is. The
     * game is the first disc's record; the rest leave the shelf. Returns the ids of those discs that are shown as another game.
     */
    suspend fun groupDiscs(groups: List<DiscGroup>): Set<String> = emptySet()
    /** By hand: [discId], a game of one disc, joins [gameId]'s discs. Returns what Undo puts back. */
    suspend fun addDisc(gameId: String, discId: String): DiscUndo = error("Discs cannot be grouped here")
    /** By hand: each disc of [gameId] is a game of its own again, and hidden again if it was hidden before it joined. */
    suspend fun separateDiscs(gameId: String): DiscUndo = error("Discs cannot be separated here")
    suspend fun undoDiscs(undo: DiscUndo) = Unit
    /** The disc in the drive when the game stopped, and how many the engine had. */
    suspend fun setDisc(gameId: String, disc: Int, count: Int) = Unit
}
