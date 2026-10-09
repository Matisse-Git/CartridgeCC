package dev.cartridge.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.cartridge.emulation.GameRequest
import dev.cartridge.storage.RomImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class RoomCollectionRepository(context: Context, private val database: LibraryDatabase = LibraryDatabase.get(context)) : CollectionRepository {
    private val context = context.applicationContext
    private val dao = database.library()
    private val roms = File(context.filesDir, "roms")
    override val snapshots = dao.observe().map(::snapshot).distinctUntilChanged()
    override suspend fun initialize(): LibrarySnapshot {
        database.withTransaction {
            repeat(3) { dao.insertSlot(SlotRecord(it)) }
            dao.insertState(CollectionRecord())
        }
        return snapshot(dao.read())
    }
    override suspend fun assign(slot: Int, game: LibraryGame) {
        require(slot in 0..2)
        database.withTransaction {
            dao.insertGame(GameRecord(game.id, game.title, game.sourceUri))
            // Reimporting identical content preserves the game's accepted label.
            dao.updateSource(game.id, game.title, game.sourceUri)
            dao.assign(slot, game.id)
        }
    }
    override suspend fun select(slot: Int) { require(slot in 0..2); dao.select(slot) }
    override suspend fun setLabel(gameId: String, label: LabelRef?) {
        require(gameId.matches(Regex("[a-f0-9]{64}")))
        check(dao.label(gameId, label?.assetId, label?.fit?.name ?: "Fit", label?.quarterTurns ?: 0, label?.sourceUri) == 1) {
            "This game is no longer in the library"
        }
    }
    override suspend fun gameRequest(game: LibraryGame): GameRequest = withContext(Dispatchers.IO) {
        val file = File(roms, "${game.id}.gba")
        if (!file.isFile) {
            val restored = RomImporter(context.contentResolver, roms).import(Uri.parse(game.sourceUri))
            check(restored.request.id == game.id) { "The original game changed. Choose the .gba file again." }
        }
        GameRequest(file, game.id)
    }
    private fun snapshot(rows: List<SlotRow>): LibrarySnapshot = LibrarySnapshot(
        List(3) { slot -> rows.firstOrNull { it.slot == slot }?.let { row ->
            row.id?.let { LibraryGame(it, row.title ?: "Untitled game", row.sourceUri.orEmpty(),
                row.labelAssetId?.let { asset -> LabelRef(asset, LabelFit.valueOf(row.labelFit ?: "Fit"), row.labelQuarterTurns ?: 0, row.labelSourceUri) }) }
        } }, rows.firstOrNull()?.selectedSlot?.coerceIn(0, 2) ?: 0)
}
