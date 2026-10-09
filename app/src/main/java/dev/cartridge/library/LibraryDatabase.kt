package dev.cartridge.library

import android.content.Context
import androidx.room.*
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
)

@Entity(tableName = "slots", foreignKeys = [ForeignKey(entity = GameRecord::class,
    parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("gameId")])
data class SlotRecord(@PrimaryKey val slot: Int, val gameId: String? = null)

@Entity(tableName = "collection_state")
data class CollectionRecord(@PrimaryKey val singleton: Int = 0, val selectedSlot: Int = 0)

data class SlotRow(val slot: Int, val selectedSlot: Int, val id: String?, val title: String?,
    val sourceUri: String?, val labelAssetId: String?, val labelFit: String?, val labelQuarterTurns: Int?, val labelSourceUri: String?)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGame(game: GameRecord)
    @Query("UPDATE games SET title = :title, sourceUri = :uri WHERE id = :id")
    suspend fun updateSource(id: String, title: String, uri: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSlot(slot: SlotRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertState(state: CollectionRecord)
    @Query("UPDATE slots SET gameId = :id WHERE slot = :slot") suspend fun assign(slot: Int, id: String)
    @Query("UPDATE collection_state SET selectedSlot = :slot WHERE singleton = 0") suspend fun select(slot: Int)
    @Query("UPDATE games SET labelAssetId = :asset, labelFit = :fit, labelQuarterTurns = :turns, labelSourceUri = :uri WHERE id = :id")
    suspend fun label(id: String, asset: String?, fit: String, turns: Int, uri: String?): Int
    @Query("""SELECT slots.slot, collection_state.selectedSlot, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    fun observe(): Flow<List<SlotRow>>
    @Query("""SELECT slots.slot, collection_state.selectedSlot, games.* FROM slots
        CROSS JOIN collection_state LEFT JOIN games ON slots.gameId = games.id
        WHERE collection_state.singleton = 0 ORDER BY slots.slot""")
    suspend fun read(): List<SlotRow>
}

@Database(entities = [GameRecord::class, SlotRecord::class, CollectionRecord::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "library.db")
                .build().also { instance = it }
        }
    }
}
