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
)

@Entity(tableName = "slots", foreignKeys = [ForeignKey(entity = GameRecord::class,
    parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("gameId")])
data class SlotRecord(@PrimaryKey val slot: Int, val gameId: String? = null)

@Entity(tableName = "collection_state")
data class CollectionRecord(@PrimaryKey val singleton: Int = 0, val selectedSlot: Int = 0)

@Entity(tableName = "directories")
data class DirectoryRecord(@PrimaryKey val uri: String)

@Entity(tableName = "removed_games")
data class RemovedGameRecord(@PrimaryKey val gameId: String)

data class SlotRow(val slot: Int, val selectedSlot: Int, val id: String?, val title: String?,
    val sourceUri: String?, val labelAssetId: String?, val labelFit: String?, val labelQuarterTurns: Int?, val labelSourceUri: String?,
    val labelProvenance: String?, val metadataJson: String?, val mediaJson: String?)

@Dao
interface LibraryDao {
    @Query("SELECT gameId FROM removed_games") suspend fun removedIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun hide(game: RemovedGameRecord)
    @Query("DELETE FROM removed_games WHERE gameId = :id") suspend fun unhide(id: String)
    @Query("DELETE FROM slots") suspend fun clearSlots()
    @Query("SELECT selectedSlot FROM collection_state WHERE singleton = 0") suspend fun selectedSlot(): Int?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun directory(directory: DirectoryRecord)
    @Query("SELECT uri FROM directories ORDER BY uri") suspend fun directories(): List<String>
    @Query("SELECT * FROM slots ORDER BY slot") suspend fun slots(): List<SlotRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGame(game: GameRecord)
    @Query("UPDATE games SET title = :title, sourceUri = :uri WHERE id = :id")
    suspend fun updateSource(id: String, title: String, uri: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSlot(slot: SlotRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertState(state: CollectionRecord)
    @Query("UPDATE slots SET gameId = :id WHERE slot = :slot") suspend fun assign(slot: Int, id: String)
    @Query("UPDATE collection_state SET selectedSlot = :slot WHERE singleton = 0") suspend fun select(slot: Int)
    @Query("UPDATE games SET labelAssetId = :asset, labelFit = :fit, labelQuarterTurns = :turns, labelSourceUri = :uri, labelProvenance = :provenance WHERE id = :id")
    suspend fun label(id: String, asset: String?, fit: String, turns: Int, uri: String?, provenance: String?): Int
    @Query("SELECT * FROM games WHERE id = :id") suspend fun game(id: String): GameRecord?
    @Query("UPDATE games SET metadataJson = :json WHERE id = :id") suspend fun metadata(id: String, json: String)
    @Query("UPDATE games SET mediaJson = :json WHERE id = :id") suspend fun media(id: String, json: String)
    @Query("""SELECT slots.slot, collection_state.selectedSlot, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    fun observe(): Flow<List<SlotRow>>
    @Query("""SELECT slots.slot, collection_state.selectedSlot, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    suspend fun read(): List<SlotRow>
}

@Database(entities = [GameRecord::class, SlotRecord::class, CollectionRecord::class, DirectoryRecord::class, RemovedGameRecord::class], version = 4, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
