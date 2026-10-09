package dev.cartridge

import androidx.room.Room
import dev.cartridge.library.CollectionRepository
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.storage.LabelStore
import dev.cartridge.scraping.ArtworkProvider
import java.io.File

/** Runs the production UI/engine/renderer against a separate test DB, never the owner's collection. */
class DebugCollectionActivity : MainActivity() {
    override fun createRepository(): CollectionRepository {
        val name = checkNotNull(intent.getStringExtra("testDatabase"))
        require(name.matches(Regex("m1-test-[a-f0-9-]+")))
        val database = synchronized(databases) {
            databases.getOrPut(name) { Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "$name.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2).build() }
        }
        return RoomCollectionRepository(this, database)
    }
    override fun createLabelStore() = LabelStore(File(filesDir, "m1-test-labels"))
    override fun createArtworkProvider() = testProvider ?: super.createArtworkProvider()
    companion object {
        var testProvider: ArtworkProvider? = null
        private val databases = mutableMapOf<String, LibraryDatabase>()
        fun closeDatabases() = synchronized(databases) { databases.values.forEach { it.close() }; databases.clear() }
    }
}
