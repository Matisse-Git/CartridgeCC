package dev.cartridge.library

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.cartridge.emulation.GameRequest
import dev.cartridge.domain.DiscGroup
import dev.cartridge.domain.DiscNames
import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.DiscCards
import dev.cartridge.storage.GameFiles
import dev.cartridge.storage.RomImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
        LibraryBackup(dao.allGames(), dao.slots().mapNotNull { it.gameId }, dao.removedIds().toSet(), dao.collectionRecord() ?: CollectionRecord(),
            dao.allDiscs(), dao.separatedIds().toSet())
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
        // A game's discs come back as they were (ADR 0032), unless this library already decided about any of them.
        val decided = dao.allDiscs().map { it.discId }.toSet() + dao.separatedIds()
        backup.discs.groupBy { it.gameId }.forEach { (gameId, discs) ->
            if (discs.none { it.discId in decided }) join(gameId, discs.sortedBy { it.number }, discs.associateBy { it.discId })
        }
        backup.separated.filter { it !in decided }.forEach { dao.separate(SeparatedDisc(it)) }
        // A new disc whose game this library already grouped otherwise is listed on its own rather than nowhere.
        val members = members(); val hidden = dao.removedIds().toSet(); val listed = dao.slots().mapNotNull { it.gameId }
        val unlisted = backup.games.map { it.id }.filter { it !in existing && it !in listed && it !in hidden && it !in members }
        if (unlisted.isNotEmpty()) replaceSlots(listed + unlisted, (dao.selectedSlot() ?: 0).coerceAtMost(listed.size + unlisted.size - 1))
        backup.games.count { it.id !in existing && it.id !in members }
    }
    // Mapping parses every game's stored JSON, so it never runs on the main thread. A write that only moves
    // the selection is not announced: its writer already holds it, and re-reading a large library for every
    // browse step costs frames. initialize() always returns the stored selection.
    override val snapshots = dao.observe().combine(dao.observeDiscs(), ::snapshot)
        .distinctUntilChanged { old, new -> old.slots == new.slots && old.query == new.query }.flowOn(Dispatchers.Default)
    override suspend fun initialize(): LibrarySnapshot {
        database.withTransaction {
            dao.insertState(CollectionRecord())
            val previous = dao.slots()
            val selected = dao.selectedSlot() ?: 0
            val focusedId = previous.firstOrNull { it.slot == selected }?.gameId
            val removed = dao.removedIds().toSet()
            // A disc that joined another game is shown as that game (ADR 0032).
            val members = members()
            // Legacy study slots could assign the same content to several positions.
            // Collapse identical hashes only; preserve order, accepted data and focused identity.
            val ids = previous.mapNotNull { it.gameId }.filterNot { it in removed || it in members }.distinct()
            val normalized = if (ids.isEmpty()) listOf(SlotRecord(0)) else ids.mapIndexed { index, id -> SlotRecord(index, id) }
            val focus = ids.indexOf(focusedId).takeIf { it >= 0 } ?: selected.coerceIn(0, normalized.lastIndex)
            if (previous != normalized) replaceSlots(ids, focus)
            else if (focus != selected) dao.select(focus)
        }
        val rows = dao.read(); val discs = dao.discRows()
        return withContext(Dispatchers.Default) { snapshot(rows, discs) }
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
        // A disc of a game made of several is listed as that game, never on its own.
        if (game.id in members()) {
            dao.insertGame(GameRecord(game.id, game.title, game.sourceUri, system = game.system.name))
            dao.updateSource(game.id, game.title, game.sourceUri, game.system.name)
            return@withTransaction false
        }
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
        val system = GameSystem.valueOf(checkNotNull(dao.game(gameId)).system)
        val ids = dao.discsOf(gameId).map { it.discId }.takeIf { it.size > 1 } ?: listOf(gameId)
        val cards = DiscCards(saves)
        LibraryFacts(GameFiles.resolve(roms, gameId, system).isFile,
            when { sources.isEmpty() -> "Original not checked by a folder scan"
                sources.all { it.missing } -> "Original missing at last complete scan"
                else -> "Original found at last scan" },
            File(saves, "$gameId.sram").lastModified(), File(saves, "$gameId.state").lastModified(),
            ids.map { discFacts(it, system) }, ids.drop(1).filter { cards.has(it, system) }, cards.hasPrevious(gameId))
    }
    /** One disc's file as Details shows it: where the owner keeps it, and the size of the private copy with its tracks. */
    private suspend fun discFacts(id: String, system: GameSystem): DiscFacts {
        val launcher = GameFiles.resolve(roms, id, system)
        val copy = if (system.bundle) launcher.parentFile!!.listFiles().orEmpty().filter(File::isFile) else listOf(launcher).filter(File::isFile)
        val documents = dao.sources(id)
        val uri = documents.firstOrNull { !it.missing }?.documentUri ?: documents.firstOrNull()?.documentUri ?: dao.game(id)?.sourceUri
        return DiscFacts(id, uri?.let(::where).orEmpty().ifEmpty { dao.game(id)?.title.orEmpty() }, copy.sumOf(File::length),
            copy.count { it.name.startsWith("track-") }, launcher.isFile, documents.isNotEmpty() && documents.all { it.missing })
    }
    /** Where the owner keeps a file: its folder and name where the provider's document id is a path, else the name it shows. */
    private fun where(uri: String): String {
        val parsed = uri.toUri()
        val path = runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()?.substringAfter(':', "")?.takeIf { '/' in it }
        if (path != null) return path.split('/').filter(String::isNotEmpty).takeLast(2).joinToString("/")
        return runCatching {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: parsed.lastPathSegment.orEmpty().substringAfterLast('/')
    }
    override suspend fun gameRequest(game: LibraryGame, disc: Int?): GameRequest = withContext(Dispatchers.IO) {
        if (game.discs.size > 1) {
            // Every disc must be there: the engine numbers discs by their place in the list, and so do its states.
            val files = game.discs.map { item ->
                GameFiles.resolve(roms, item.id, game.system).also { check(it.isFile) { "Disc ${item.number}'s file is missing. Reconnect it from the game's details." } }
            }
            val start = (disc ?: game.disc).coerceIn(0, files.lastIndex)
            return@withContext GameRequest(files[start], game.id, game.system, discs = files, disc = start)
        }
        val file = GameFiles.resolve(roms, game.id, game.system)
        if (!file.isFile) {
            val restored = RomImporter(context.contentResolver, roms).import(Uri.parse(game.sourceUri))
            check(restored.request.id == game.id) { "The original game changed. Choose the same ROM file again." }
        }
        GameRequest(GameFiles.resolve(roms, game.id, game.system), game.id, game.system,
            disc = (disc ?: game.disc).coerceIn(0, (game.discCount - 1).coerceAtLeast(0)))
    }

    // ---- Discs (ADR 0032) ---------------------------------------------------------------------------------------

    /** Discs shown as another game: every disc row but a game's own. */
    private suspend fun members(): Set<String> = dao.allDiscs().filter { it.discId != it.gameId }.map { it.discId }.toSet()

    override suspend fun groupDiscs(groups: List<DiscGroup>): Set<String> {
        if (groups.isEmpty()) return emptySet()
        val joined = database.withTransaction { joinGroups(groups) }
        // A game that has no memory card yet takes the first card one of its discs has (ADR 0032). Nothing is running:
        // a scan happens only in the library.
        withContext(Dispatchers.IO) {
            val cards = DiscCards(File(context.filesDir, "saves"))
            joined.forEach { (gameId, ids) -> runCatching { cards.adopt(gameId, ids, GameSystem.valueOf(checkNotNull(dao.game(gameId)).system)) } }
        }
        return database.withTransaction { groups.flatMap { it.ids }.filter { id -> dao.disc(id)?.let { it.gameId != id } == true }.toSet() }
    }
    /** Returns each game that changed, with its discs in order. */
    private suspend fun joinGroups(groups: List<DiscGroup>): List<Pair<String, List<String>>> {
        val separated = dao.separatedIds().toSet()
        val left = mutableListOf<Pair<String, List<String>>>()
        for (group in groups) {
            val pairs = group.ids.zip(group.numbers).filter { it.first !in separated && dao.game(it.first) != null }
            if (pairs.size < 2) continue
            val ids = pairs.map { it.first }
            val existing = ids.mapNotNull { dao.disc(it) }
            // The owner's own grouping is never redone by a scan.
            if (existing.any { it.origin == GameDisc.MANUAL }) continue
            val games = existing.map { it.gameId }.toSet()
            // A disc already counted in another set is not taken from it.
            if (games.size > 1 || games.any { gameId -> dao.discsOf(gameId).any { it.discId !in ids } }) continue
            val origin = group.playlist?.let { GameDisc.PLAYLIST + it } ?: GameDisc.NAMES
            // The game keeps the identity it already had; otherwise it is the first disc's record.
            val gameId = games.firstOrNull() ?: ids.first()
            val wanted = pairs.map { (id, number) -> DiscRecord(id, gameId, number, origin) }
            if (existing.size == wanted.size && wanted.all { want -> existing.any { it.discId == want.discId && it.gameId == gameId && it.number == want.number && it.origin == origin } }) continue
            join(gameId, wanted, existing.associateBy { it.discId })
            left += gameId to wanted.map { it.discId }
        }
        return left
    }
    /**
     * Writes [discs] as the game [gameId]. A disc that was hidden comes back as a disc of the game, and the game is hidden
     * only if every disc was. Only the game is on the shelf, where its first listed disc was; the focus follows it.
     */
    private suspend fun join(gameId: String, discs: List<DiscRecord>, before: Map<String, DiscRecord>): Set<String> {
        val removed = dao.removedIds().toSet()
        val hidden = discs.associate { it.discId to (before[it.discId]?.wasHidden ?: (it.discId in removed)) }
        val slots = dao.slots().mapNotNull { it.gameId }
        val selected = dao.selectedSlot() ?: 0
        val focused = slots.getOrNull(selected)
        discs.forEach { dao.insertDisc(it.copy(wasHidden = hidden.getValue(it.discId))); dao.unhide(it.discId) }
        val ids = discs.map { it.discId }.toSet()
        val visible = hidden.values.any { !it }
        val order = slots.toMutableList()
        val at = order.indexOfFirst { it in ids }
        order.removeAll { it in ids }
        if (visible) order.add(if (at < 0) order.size else at.coerceAtMost(order.size), gameId) else dao.hide(RemovedGameRecord(gameId))
        dao.setDisc(gameId, 0)
        val focus = (if (focused in ids) gameId else focused)?.let(order::indexOf)?.takeIf { it >= 0 } ?: selected.coerceAtMost((order.size - 1).coerceAtLeast(0))
        replaceSlots(order, focus)
        return ids - gameId
    }
    private suspend fun undoFor(gameId: String, extra: Set<String> = emptySet()): DiscUndo {
        val affected = (dao.discsOf(gameId).map { it.discId } + gameId + extra).toSet()
        return DiscUndo(gameId, affected.mapNotNull { dao.disc(it) }, dao.separatedIds().toSet().intersect(affected),
            dao.removedIds().toSet().intersect(affected), affected, dao.slots().mapNotNull { it.gameId })
    }
    override suspend fun addDisc(gameId: String, discId: String): DiscUndo {
        val undo = joinByHand(gameId, discId)
        withContext(Dispatchers.IO) {
            val system = GameSystem.valueOf(checkNotNull(dao.game(gameId)).system)
            runCatching { DiscCards(File(context.filesDir, "saves")).adopt(gameId, dao.discsOf(gameId).map { it.discId }, system) }
        }
        return undo
    }
    private suspend fun joinByHand(gameId: String, discId: String): DiscUndo = database.withTransaction {
        require(gameId != discId) { "A disc cannot join itself" }
        val game = checkNotNull(dao.game(gameId)) { "This game is no longer in the library" }
        val disc = checkNotNull(dao.game(discId)) { "That game is no longer in the library" }
        require(game.system == disc.system) { "The discs of one game are for one console" }
        dao.disc(discId)?.let { error(if (it.gameId == discId) "That game has discs of its own. Separate them first." else "That disc is already part of another game") }
        val current = dao.discsOf(gameId)
        check(current.isEmpty() || current.any { it.discId == gameId }) { "Add discs to the game itself" }
        check(current.size < DiscNames.MAX) { "A game has at most ${DiscNames.MAX} discs" }
        val undo = undoFor(gameId, setOf(discId))
        val discs = current.ifEmpty { listOf(DiscRecord(gameId, gameId, DiscNames.number(game.title) ?: 1, GameDisc.MANUAL)) }
        val taken = discs.map { it.number }.toSet()
        val number = DiscNames.number(disc.title)?.takeIf { it !in taken } ?: ((taken.maxOrNull() ?: 0) + 1)
        // Grouped by hand, the whole game is the owner's: no scan regroups it.
        val manual = (discs + DiscRecord(discId, gameId, number, GameDisc.MANUAL)).map { it.copy(origin = GameDisc.MANUAL) }.sortedBy { it.number }
        manual.forEach { dao.unseparate(it.discId) }
        join(gameId, manual, current.associateBy { it.discId })
        undo
    }
    override suspend fun separateDiscs(gameId: String): DiscUndo = database.withTransaction {
        val discs = dao.discsOf(gameId)
        check(discs.size > 1) { "This game has one disc" }
        val undo = undoFor(gameId)
        val order = dao.slots().mapNotNull { it.gameId }.toMutableList()
        var at = order.indexOf(gameId).let { if (it < 0) order.size else it + 1 }
        dao.clearDiscs(gameId)
        discs.forEach { disc ->
            dao.separate(SeparatedDisc(disc.discId))
            if (disc.discId == gameId) return@forEach
            if (disc.wasHidden) dao.hide(RemovedGameRecord(disc.discId))
            else if (disc.discId !in order) order.add(at++.coerceAtMost(order.size), disc.discId)
        }
        dao.setDisc(gameId, 0)
        replaceSlots(order, order.indexOf(gameId).coerceAtLeast(0))
        undo
    }
    override suspend fun undoDiscs(undo: DiscUndo) = database.withTransaction {
        undo.affected.forEach { id ->
            dao.removeDisc(id); dao.unseparate(id)
            if (id in undo.hidden) dao.hide(RemovedGameRecord(id)) else dao.unhide(id)
        }
        undo.discs.forEach { dao.insertDisc(it) }
        undo.separated.forEach { dao.separate(SeparatedDisc(it)) }
        val members = members(); val removed = dao.removedIds().toSet()
        val order = undo.order.filter { dao.game(it) != null && it !in members && it !in removed }.distinct()
        replaceSlots(order, order.indexOf(undo.gameId).coerceAtLeast(0))
    }
    override suspend fun setDisc(gameId: String, disc: Int, count: Int) {
        require(disc >= 0 && count >= 0)
        database.withTransaction { dao.setDisc(gameId, disc); if (dao.discsOf(gameId).size <= 1) dao.setDiscCount(gameId, count) }
    }

    private fun snapshot(rows: List<SlotRow>, discRows: List<DiscRow>): LibrarySnapshot {
        val bySlot = rows.associateBy { it.slot }
        val sets = discRows.groupBy { it.gameId }
        val count = (rows.maxOfOrNull { it.slot }?.plus(1) ?: 1).coerceAtLeast(1)
        return LibrarySnapshot(List(count) { slot -> bySlot[slot]?.let { row ->
            row.id?.let {
                val metadata = LibraryJson.metadata(row.metadataJson)
                val discs = sets[it].orEmpty().takeIf { set -> set.size > 1 }.orEmpty()
                val others = discs.filter { disc -> disc.discId != it }
                val fileName = row.title ?: "Untitled game"
                val total = if (discs.isNotEmpty()) discs.size else row.discCount ?: 0
                // A game made of discs is named without its disc tag, and its play history is all of its discs' (ADR 0032).
                LibraryGame(it, metadata["title"]?.value ?: if (discs.isEmpty()) fileName else DiscNames.title(fileName), row.sourceUri.orEmpty(),
                    row.labelAssetId?.let { asset -> LabelRef(asset, LabelFit.valueOf(row.labelFit ?: "Fit"), row.labelQuarterTurns ?: 0,
                        row.labelSourceUri, LibraryJson.provenance(row.labelProvenance)) }, metadata, LibraryJson.media(row.mediaJson),
                    row.favorite ?: false, (row.playMs ?: 0) + others.sumOf { disc -> disc.playMs },
                    maxOf(row.lastPlayed ?: 0, others.maxOfOrNull { disc -> disc.lastPlayed } ?: 0), row.canUndoEnrichment, GameSystem.valueOf(row.system ?: "GBA"),
                    fileName, discs.map { disc -> GameDisc(disc.discId, disc.number, disc.title, disc.sourceUri, disc.origin) },
                    (row.disc ?: 0).coerceIn(0, (total - 1).coerceAtLeast(0)), row.discCount ?: 0)
            }
        } }, rows.firstOrNull()?.selectedSlot?.coerceIn(0, count - 1) ?: 0,
            rows.firstOrNull()?.let { LibraryQuery(LibrarySort.valueOf(it.sort), it.favoritesOnly, it.search) } ?: LibraryQuery())
    }
}
