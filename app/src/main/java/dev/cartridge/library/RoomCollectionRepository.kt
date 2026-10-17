package dev.cartridge.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.cartridge.emulation.GameRequest
import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.RomImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

interface DirectoryRepository {
    suspend fun rememberDirectory(uri: String)
    suspend fun directories(): List<String>
    suspend fun directoryRecords(): List<DirectoryRecord>
    suspend fun forgetDirectory(uri: String)
    suspend fun indexed(uri: String): List<IndexedDocument>
    suspend fun index(document: IndexedDocument)
    suspend fun finishScan(uri: String, name: String, seen: Set<String>, complete: Boolean, error: String?)
}

class RoomCollectionRepository(context: Context, private val database: LibraryDatabase = LibraryDatabase.get(context)) : CollectionRepository, DirectoryRepository, BackupRepository {
    private val context = context.applicationContext
    private val dao = database.library()
    private val roms = File(context.filesDir, "roms")
    override suspend fun exportLibrary(): LibraryBackup = database.withTransaction {
        LibraryBackup(dao.allGames(), dao.slots().mapNotNull { it.gameId }, dao.removedIds().toSet(), dao.collectionRecord() ?: CollectionRecord())
    }
    override suspend fun mergeLibrary(backup: LibraryBackup): Int = database.withTransaction {
        dao.insertState(CollectionRecord())
        val existing = dao.allGames().associateBy { it.id }
        val ids = dao.slots().mapNotNull { it.gameId }.toMutableList()
        val selected = dao.selectedSlot() ?: 0
        backup.games.forEach { incoming ->
            val current = existing[incoming.id]
            if (current == null) {
                dao.insertGame(incoming)
                if (incoming.id in backup.removed) dao.hide(RemovedGameRecord(incoming.id))
            } else {
                require(current.system == incoming.system) { "The same ROM has conflicting console identities" }
                val metadata = LibraryJson.metadata(incoming.metadataJson) + LibraryJson.metadata(current.metadataJson)
                val media = LibraryJson.media(incoming.mediaJson) + LibraryJson.media(current.mediaJson)
                val merged = current.copy(
                    labelAssetId = current.labelAssetId ?: incoming.labelAssetId,
                    labelFit = if (current.labelAssetId == null) incoming.labelFit else current.labelFit,
                    labelQuarterTurns = if (current.labelAssetId == null) incoming.labelQuarterTurns else current.labelQuarterTurns,
                    labelSourceUri = if (current.labelAssetId == null) incoming.labelSourceUri else current.labelSourceUri,
                    labelProvenance = if (current.labelAssetId == null) incoming.labelProvenance else current.labelProvenance,
                    metadataJson = LibraryJson.metadata(metadata), mediaJson = LibraryJson.media(media),
                    favorite = current.favorite || incoming.favorite, playMs = maxOf(current.playMs, incoming.playMs),
                    lastPlayed = maxOf(current.lastPlayed, incoming.lastPlayed))
                if (merged != current) {
                    if (merged.labelAssetId != current.labelAssetId || merged.metadataJson != current.metadataJson || merged.mediaJson != current.mediaJson) rememberEnrichment(current)
                    dao.updateGame(merged)
                }
            }
        }
        backup.order.filter { it !in existing && it !in backup.removed }.forEach { if (it !in ids) ids.add(it) }
        replaceSlots(ids, if (existing.isEmpty()) backup.state.selectedSlot.coerceIn(0, (ids.size - 1).coerceAtLeast(0)) else selected)
        if (existing.isEmpty()) dao.query(backup.state.sort, backup.state.favoritesOnly, backup.state.search)
        backup.games.count { it.id !in existing }
    }
    // Mapping parses every game's stored JSON, so it never runs on the main thread. A write that only moves
    // the selection is not announced: its writer already holds it, and re-reading a large library for every
    // browse step costs frames. initialize() always returns the stored selection.
    override val snapshots = dao.observe().map(::snapshot)
        .distinctUntilChanged { old, new -> old.slots == new.slots && old.query == new.query }.flowOn(Dispatchers.Default)
    override suspend fun initialize(): LibrarySnapshot {
        database.withTransaction {
            dao.insertState(CollectionRecord())
            val previous = dao.slots()
            val selected = dao.selectedSlot() ?: 0
            val focusedId = previous.firstOrNull { it.slot == selected }?.gameId
            val removed = dao.removedIds().toSet()
            // Legacy study slots could assign the same content to several positions.
            // Collapse identical hashes only; preserve order, accepted data and focused identity.
            val ids = previous.mapNotNull { it.gameId }.filterNot { it in removed }.distinct()
            val normalized = if (ids.isEmpty()) listOf(SlotRecord(0)) else ids.mapIndexed { index, id -> SlotRecord(index, id) }
            val focus = ids.indexOf(focusedId).takeIf { it >= 0 } ?: selected.coerceIn(0, normalized.lastIndex)
            if (previous != normalized) replaceSlots(ids, focus)
            else if (focus != selected) dao.select(focus)
        }
        val rows = dao.read()
        return withContext(Dispatchers.Default) { snapshot(rows) }
    }
    override suspend fun assign(slot: Int, game: LibraryGame) {
        require(slot >= 0)
        database.withTransaction {
            dao.insertGame(GameRecord(game.id, game.title, game.sourceUri, system = game.system.name))
            // Reimporting identical content preserves the game's accepted label.
            dao.updateSource(game.id, game.title, game.sourceUri, game.system.name)
            dao.unhide(game.id)
            val ids = dao.slots().mapNotNull { it.gameId }.toMutableList()
            val existing = ids.indexOf(game.id)
            if (existing >= 0) dao.select(existing) else {
                val position = slot.coerceAtMost(ids.size)
                if (position == ids.size) ids.add(game.id) else ids[position] = game.id
                replaceSlots(ids, position)
            }
        }
    }
    override suspend fun add(game: LibraryGame, restoreRemoved: Boolean): Boolean = database.withTransaction {
        if (!restoreRemoved && game.id in dao.removedIds()) return@withTransaction false
        if (restoreRemoved) dao.unhide(game.id)
        val slots = dao.slots()
        dao.insertGame(GameRecord(game.id, game.title, game.sourceUri, system = game.system.name))
        dao.updateSource(game.id, game.title, game.sourceUri, game.system.name)
        if (slots.any { it.gameId == game.id }) false else {
            val position = slots.firstOrNull { it.gameId == null }?.slot ?: ((slots.lastOrNull()?.slot ?: -1) + 1)
            dao.insertSlot(SlotRecord(position))
            dao.assign(position, game.id)
            true
        }
    }
    override suspend fun remove(gameId: String) = database.withTransaction {
        val ids = dao.slots().mapNotNull { it.gameId }.toMutableList()
        val position = ids.indexOf(gameId)
        check(position >= 0) { "This game is no longer in the collection" }
        val selected = dao.selectedSlot() ?: 0
        val focusedId = ids.getOrNull(selected)
        ids.removeAll { it == gameId }
        dao.hide(RemovedGameRecord(gameId))
        replaceSlots(ids, ids.indexOf(focusedId).takeIf { it >= 0 } ?: position.coerceAtMost((ids.size - 1).coerceAtLeast(0)))
    }
    override suspend fun restore(gameId: String, position: Int) = database.withTransaction {
        checkNotNull(dao.game(gameId)) { "The saved collection entry is unavailable" }
        val ids = dao.slots().mapNotNull { it.gameId }.toMutableList()
        dao.unhide(gameId)
        val existing = ids.indexOf(gameId)
        val target = if (existing >= 0) existing else position.coerceIn(0, ids.size).also { ids.add(it, gameId) }
        replaceSlots(ids, target)
    }
    override suspend fun hiddenGames(): List<LibraryGame> = dao.removedGames().map { record ->
        val metadata = LibraryJson.metadata(record.metadataJson)
        LibraryGame(record.id, metadata["title"]?.value ?: record.title, record.sourceUri, metadata = metadata,
            favorite = record.favorite, playMs = record.playMs, lastPlayed = record.lastPlayed, system = GameSystem.valueOf(record.system), fileName = record.title)
    }
    private suspend fun replaceSlots(ids: List<String>, selected: Int) {
        dao.clearSlots()
        if (ids.isEmpty()) dao.insertSlot(SlotRecord(0))
        else ids.forEachIndexed { index, id -> dao.insertSlot(SlotRecord(index, id)) }
        dao.select(selected)
    }
    override suspend fun rememberDirectory(uri: String) { dao.directory(DirectoryRecord(uri)) }
    override suspend fun directories() = dao.directories()
    override suspend fun directoryRecords() = dao.directoryRecords()
    override suspend fun forgetDirectory(uri: String) = database.withTransaction { dao.forgetDirectory(uri); dao.forgetIndex(uri) }
    override suspend fun indexed(uri: String) = dao.index(uri)
    override suspend fun index(document: IndexedDocument) { dao.index(document) }
    override suspend fun finishScan(uri: String, name: String, seen: Set<String>, complete: Boolean, error: String?) = database.withTransaction {
        if (complete) dao.index(uri).filterNot { it.documentUri in seen }.forEach { dao.missing(uri, it.documentUri) }
        dao.scanFinished(uri, name, System.currentTimeMillis(), error)
    }
    override suspend fun select(slot: Int) { require(slot >= 0); dao.select(slot) }
    override suspend fun setLabel(gameId: String, label: LabelRef?) {
        require(gameId.matches(Regex("[a-f0-9]{64}")))
        database.withTransaction {
            val game = checkNotNull(dao.game(gameId)) { "This game is no longer in the library" }
            val provenance = LibraryJson.provenance(label?.provenance)
            val fit = label?.fit?.name ?: "Fit"; val turns = label?.quarterTurns ?: 0
            if (game.labelAssetId == label?.assetId && game.labelFit == fit && game.labelQuarterTurns == turns &&
                game.labelSourceUri == label?.sourceUri && game.labelProvenance == provenance) return@withTransaction
            rememberEnrichment(game)
            dao.label(gameId, label?.assetId, fit, turns, label?.sourceUri, provenance)
        }
    }
    override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) {
        writeMetadata(gameId, values, missingOnly = false)
    }
    override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>): Int = writeMetadata(gameId, values, missingOnly = true)
    private suspend fun writeMetadata(gameId: String, values: Map<String, MetadataValue>, missingOnly: Boolean): Int {
        require(values.keys.all { it in METADATA_FIELDS })
        require(values.values.all { it.value.length <= 16_384 }) { "The metadata field is too large" }
        return database.withTransaction {
            val game = checkNotNull(dao.game(gameId)) { "This game is no longer in the library" }
            val before = LibraryJson.metadata(game.metadataJson)
            val changed = values.filter { (field, value) -> (!missingOnly || before[field]?.value.isNullOrBlank()) && before[field] != value }
            val after = (before + changed).filterValues { it.value.isNotBlank() }
            if (after == before) return@withTransaction 0
            rememberEnrichment(game)
            dao.metadata(gameId, LibraryJson.metadata(after))
            changed.size
        }
    }
    override suspend fun setMedia(gameId: String, media: MediaSelection) {
        database.withTransaction {
            val game = checkNotNull(dao.game(gameId)) { "This game is no longer in the library" }
            val before = LibraryJson.media(game.mediaJson)
            if (before[media.type] == media) return@withTransaction
            rememberEnrichment(game)
            dao.media(gameId, LibraryJson.media(before + (media.type to media)))
        }
    }
    override suspend fun applyScraped(gameId: String, label: LabelRef?, metadata: Map<String, MetadataValue>, policy: FillPolicy): Int {
        require(gameId.matches(Regex("[a-f0-9]{64}")))
        require(metadata.keys.all { it in METADATA_FIELDS })
        require(metadata.values.all { it.value.length <= 16_384 }) { "The metadata field is too large" }
        return database.withTransaction {
            val game = checkNotNull(dao.game(gameId)) { "This game is no longer in the library" }
            val before = LibraryJson.metadata(game.metadataJson)
            val fields = metadata.filter { (field, value) -> value.value.isNotBlank() && before[field]?.value != value.value &&
                policy.replaces(before[field]?.takeIf { it.value.isNotBlank() }?.automatic) }
            val provenance = LibraryJson.provenance(game.labelProvenance)
            val art = game.labelAssetId != null && (provenance == null || provenance.type in CASE_ART)
            val replace = label != null && game.labelAssetId != label.assetId && policy.replaces(if (art) provenance!!.automatic else null)
            if (fields.isEmpty() && !replace) return@withTransaction 0
            rememberEnrichment(game)
            if (fields.isNotEmpty()) dao.metadata(gameId, LibraryJson.metadata(before + fields))
            if (replace) {
                // A cartridge or disc label chosen before the cases is kept as extra media, never thrown away.
                if (game.labelAssetId != null && !art) {
                    val media = LibraryJson.media(game.mediaJson)
                    val type = provenance?.type ?: "label"
                    if (type !in media) dao.media(gameId, LibraryJson.media(media + (type to MediaSelection(
                        LabelRef(game.labelAssetId, sourceUri = game.labelSourceUri, provenance = provenance), type, provenance?.region.orEmpty()))))
                }
                dao.label(gameId, label!!.assetId, label.fit.name, label.quarterTurns, label.sourceUri, LibraryJson.provenance(label.provenance))
            }
            fields.size + if (replace) 1 else 0
        }
    }
    private suspend fun rememberEnrichment(game: GameRecord) {
        dao.rememberEnrichment(EnrichmentUndo(game.id, game.labelAssetId, game.labelFit, game.labelQuarterTurns,
            game.labelSourceUri, game.labelProvenance, game.metadataJson, game.mediaJson))
    }
    override suspend fun undoEnrichment(gameId: String): Boolean = database.withTransaction {
        val before = dao.enrichmentUndo(gameId) ?: return@withTransaction false
        dao.restoreEnrichment(gameId, before.labelAssetId, before.labelFit, before.labelQuarterTurns,
            before.labelSourceUri, before.labelProvenance, before.metadataJson, before.mediaJson)
        dao.clearEnrichmentUndo(gameId)
        true
    }
    override suspend fun setFavorite(gameId: String, favorite: Boolean) { dao.favorite(gameId, favorite) }
    override suspend fun setQuery(query: LibraryQuery) { dao.query(query.sort.name, query.favoritesOnly, query.search) }
    override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) {
        require(elapsedMs >= 0) { "Invalid playtime checkpoint" }
        dao.play(gameId, elapsedMs, startedAt)
    }
    override suspend fun facts(gameId: String): LibraryFacts = withContext(Dispatchers.IO) {
        val sources = dao.sources(gameId)
        val saves = File(context.filesDir, "saves")
        LibraryFacts(dev.cartridge.storage.GameFiles.resolve(roms, gameId, GameSystem.valueOf(checkNotNull(dao.game(gameId)).system)).isFile,
            when { sources.isEmpty() -> "Original not checked by a folder scan"
                sources.all { it.missing } -> "Original missing at last complete scan"
                else -> "Original found at last scan" },
            File(saves, "$gameId.sram").lastModified(), File(saves, "$gameId.state").lastModified())
    }
    override suspend fun gameRequest(game: LibraryGame): GameRequest = withContext(Dispatchers.IO) {
        val file = dev.cartridge.storage.GameFiles.resolve(roms, game.id, game.system)
        if (!file.isFile) {
            val restored = RomImporter(context.contentResolver, roms).import(Uri.parse(game.sourceUri))
            check(restored.request.id == game.id) { "The original game changed. Choose the same ROM file again." }
        }
        GameRequest(dev.cartridge.storage.GameFiles.resolve(roms, game.id, game.system), game.id, game.system)
    }
    private fun snapshot(rows: List<SlotRow>): LibrarySnapshot {
        val bySlot = rows.associateBy { it.slot }
        val count = (rows.maxOfOrNull { it.slot }?.plus(1) ?: 1).coerceAtLeast(1)
        return LibrarySnapshot(List(count) { slot -> bySlot[slot]?.let { row ->
            row.id?.let {
                val metadata = LibraryJson.metadata(row.metadataJson)
                LibraryGame(it, metadata["title"]?.value ?: row.title ?: "Untitled game", row.sourceUri.orEmpty(),
                    row.labelAssetId?.let { asset -> LabelRef(asset, LabelFit.valueOf(row.labelFit ?: "Fit"), row.labelQuarterTurns ?: 0,
                        row.labelSourceUri, LibraryJson.provenance(row.labelProvenance)) }, metadata, LibraryJson.media(row.mediaJson),
                    row.favorite ?: false, row.playMs ?: 0, row.lastPlayed ?: 0, row.canUndoEnrichment, GameSystem.valueOf(row.system ?: "GBA"),
                    row.title ?: "Untitled game")
            }
        } }, rows.firstOrNull()?.selectedSlot?.coerceIn(0, count - 1) ?: 0,
            rows.firstOrNull()?.let { LibraryQuery(LibrarySort.valueOf(it.sort), it.favoritesOnly, it.search) } ?: LibraryQuery())
    }
}
