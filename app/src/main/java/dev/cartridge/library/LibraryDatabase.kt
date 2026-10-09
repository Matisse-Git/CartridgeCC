package dev.cartridge.library

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "games")
data class GameRecord(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUri: String,
    val labelAssetId: String? = null,
    val labelFit: String = "Fit",
    val labelQuarterTurns: Int = 0,
    val labelSourceUri: String? = null,
    val labelProvenance: String? = null,
    val metadataJson: String? = null,
    val mediaJson: String? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val playMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastPlayed: Long = 0,
    @ColumnInfo(defaultValue = "'GBA'") val system: String = "GBA",
)

@Entity(tableName = "slots", foreignKeys = [ForeignKey(entity = GameRecord::class,
    parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("gameId")])
data class SlotRecord(@PrimaryKey val slot: Int, val gameId: String? = null)

@Entity(tableName = "collection_state")
data class CollectionRecord(@PrimaryKey val singleton: Int = 0, val selectedSlot: Int = 0,
    @ColumnInfo(defaultValue = "'Added'") val sort: String = "Added",
    @ColumnInfo(defaultValue = "0") val favoritesOnly: Boolean = false,
    @ColumnInfo(defaultValue = "''") val search: String = "")

@Entity(tableName = "directories")
data class DirectoryRecord(@PrimaryKey val uri: String,
    @ColumnInfo(defaultValue = "''") val name: String = "",
    @ColumnInfo(defaultValue = "0") val lastScan: Long = 0, val error: String? = null)

@Entity(tableName = "indexed_documents", primaryKeys = ["treeUri", "documentUri"], indices = [Index("gameId")])
data class IndexedDocument(val treeUri: String, val documentUri: String, val gameId: String,
    val bytes: Long, val modified: Long, val missing: Boolean = false)

@Entity(tableName = "removed_games")
data class RemovedGameRecord(@PrimaryKey val gameId: String)

@Entity(tableName = "enrichment_undo", foreignKeys = [ForeignKey(entity = GameRecord::class,
    parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)])
data class EnrichmentUndo(@PrimaryKey val gameId: String, val labelAssetId: String?, val labelFit: String,
    val labelQuarterTurns: Int, val labelSourceUri: String?, val labelProvenance: String?,
    val metadataJson: String?, val mediaJson: String?)

data class SlotRow(val slot: Int, val selectedSlot: Int, val id: String?, val title: String?,
    val sourceUri: String?, val labelAssetId: String?, val labelFit: String?, val labelQuarterTurns: Int?, val labelSourceUri: String?,
    val labelProvenance: String?, val metadataJson: String?, val mediaJson: String?,
    val favorite: Boolean?, val playMs: Long?, val lastPlayed: Long?, val sort: String, val favoritesOnly: Boolean, val search: String,
    val canUndoEnrichment: Boolean, val system: String?)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM games ORDER BY id") suspend fun allGames(): List<GameRecord>
    @Query("SELECT * FROM collection_state WHERE singleton = 0") suspend fun collectionRecord(): CollectionRecord?
    @Update suspend fun updateGame(game: GameRecord)
    @Query("SELECT gameId FROM removed_games") suspend fun removedIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun hide(game: RemovedGameRecord)
    @Query("DELETE FROM removed_games WHERE gameId = :id") suspend fun unhide(id: String)
    @Query("DELETE FROM slots") suspend fun clearSlots()
    @Query("SELECT selectedSlot FROM collection_state WHERE singleton = 0") suspend fun selectedSlot(): Int?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun directory(directory: DirectoryRecord)
    @Query("SELECT uri FROM directories ORDER BY uri") suspend fun directories(): List<String>
    @Query("SELECT * FROM directories ORDER BY uri") suspend fun directoryRecords(): List<DirectoryRecord>
    @Query("UPDATE directories SET name = :name, lastScan = :time, error = :error WHERE uri = :uri")
    suspend fun scanFinished(uri: String, name: String, time: Long, error: String?)
    @Query("DELETE FROM directories WHERE uri = :uri") suspend fun forgetDirectory(uri: String)
    @Query("DELETE FROM indexed_documents WHERE treeUri = :uri") suspend fun forgetIndex(uri: String)
    @Query("SELECT * FROM indexed_documents WHERE treeUri = :uri") suspend fun index(uri: String): List<IndexedDocument>
    @Query("SELECT * FROM indexed_documents WHERE gameId = :id") suspend fun sources(id: String): List<IndexedDocument>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun index(document: IndexedDocument)
    @Query("UPDATE indexed_documents SET missing = 1 WHERE treeUri = :tree AND documentUri = :document")
    suspend fun missing(tree: String, document: String)
    @Query("SELECT * FROM slots ORDER BY slot") suspend fun slots(): List<SlotRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGame(game: GameRecord)
    @Query("UPDATE games SET title = :title, sourceUri = :uri, system = :system WHERE id = :id")
    suspend fun updateSource(id: String, title: String, uri: String, system: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSlot(slot: SlotRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertState(state: CollectionRecord)
    @Query("UPDATE slots SET gameId = :id WHERE slot = :slot") suspend fun assign(slot: Int, id: String)
    @Query("UPDATE collection_state SET selectedSlot = :slot WHERE singleton = 0") suspend fun select(slot: Int)
    @Query("UPDATE games SET labelAssetId = :asset, labelFit = :fit, labelQuarterTurns = :turns, labelSourceUri = :uri, labelProvenance = :provenance WHERE id = :id")
    suspend fun label(id: String, asset: String?, fit: String, turns: Int, uri: String?, provenance: String?): Int
    @Query("SELECT * FROM games WHERE id = :id") suspend fun game(id: String): GameRecord?
    @Query("UPDATE games SET metadataJson = :json WHERE id = :id") suspend fun metadata(id: String, json: String)
    @Query("UPDATE games SET mediaJson = :json WHERE id = :id") suspend fun media(id: String, json: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rememberEnrichment(snapshot: EnrichmentUndo)
    @Query("SELECT * FROM enrichment_undo WHERE gameId = :id") suspend fun enrichmentUndo(id: String): EnrichmentUndo?
    @Query("DELETE FROM enrichment_undo WHERE gameId = :id") suspend fun clearEnrichmentUndo(id: String)
    @Query("UPDATE games SET labelAssetId = :asset, labelFit = :fit, labelQuarterTurns = :turns, labelSourceUri = :source, labelProvenance = :provenance, metadataJson = :metadata, mediaJson = :media WHERE id = :id")
    suspend fun restoreEnrichment(id: String, asset: String?, fit: String, turns: Int, source: String?, provenance: String?, metadata: String?, media: String?)
    @Query("UPDATE games SET favorite = :favorite WHERE id = :id") suspend fun favorite(id: String, favorite: Boolean)
    @Query("UPDATE collection_state SET sort = :sort, favoritesOnly = :favorites, search = :search WHERE singleton = 0")
    suspend fun query(sort: String, favorites: Boolean, search: String)
    @Query("UPDATE games SET playMs = playMs + :elapsed, lastPlayed = CASE WHEN :started IS NULL THEN lastPlayed ELSE :started END WHERE id = :id")
    suspend fun play(id: String, elapsed: Long, started: Long?)
    @Query("""SELECT slots.slot, collection_state.selectedSlot, collection_state.sort, collection_state.favoritesOnly, collection_state.search,
        EXISTS(SELECT 1 FROM enrichment_undo WHERE gameId = slots.gameId) AS canUndoEnrichment, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    fun observe(): Flow<List<SlotRow>>
    @Query("""SELECT slots.slot, collection_state.selectedSlot, collection_state.sort, collection_state.favoritesOnly, collection_state.search,
        EXISTS(SELECT 1 FROM enrichment_undo WHERE gameId = slots.gameId) AS canUndoEnrichment, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    suspend fun read(): List<SlotRow>
}

@Database(entities = [GameRecord::class, SlotRecord::class, CollectionRecord::class, DirectoryRecord::class, RemovedGameRecord::class, IndexedDocument::class, EnrichmentUndo::class], version = 7, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE games ADD COLUMN system TEXT NOT NULL DEFAULT 'GBA'") }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE enrichment_undo (gameId TEXT NOT NULL PRIMARY KEY, labelAssetId TEXT, labelFit TEXT NOT NULL, labelQuarterTurns INTEGER NOT NULL, labelSourceUri TEXT, labelProvenance TEXT, metadataJson TEXT, mediaJson TEXT, FOREIGN KEY(gameId) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN playMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN lastPlayed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collection_state ADD COLUMN sort TEXT NOT NULL DEFAULT 'Added'")
                db.execSQL("ALTER TABLE collection_state ADD COLUMN favoritesOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collection_state ADD COLUMN search TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE directories ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE directories ADD COLUMN lastScan INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE directories ADD COLUMN error TEXT")
                db.execSQL("CREATE TABLE indexed_documents (treeUri TEXT NOT NULL, documentUri TEXT NOT NULL, gameId TEXT NOT NULL, bytes INTEGER NOT NULL, modified INTEGER NOT NULL, missing INTEGER NOT NULL, PRIMARY KEY(treeUri, documentUri))")
                db.execSQL("CREATE INDEX index_indexed_documents_gameId ON indexed_documents(gameId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS removed_games (gameId TEXT NOT NULL PRIMARY KEY)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS directories (uri TEXT NOT NULL PRIMARY KEY)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN labelProvenance TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN metadataJson TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN mediaJson TEXT")
            }
        }
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
        }
    }
}
