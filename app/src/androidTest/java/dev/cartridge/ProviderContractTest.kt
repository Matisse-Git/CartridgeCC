package dev.cartridge

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.library.*
import dev.cartridge.scraping.RetroBaseProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProviderContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun documentedResponseSeparatesFlatLabelsAndPreservesRegionalMetadata() {
        val json = JSONObject("""{"id":"00000000-0000-0000-0000-000000000001","name":"Regional title",
            "platform":{"name":"Game Boy Advance"},"names":{"us":"Orbit Field","jp":"Orbit Japan"},
            "year":"2002","publisher":null,"synopses":{"en":"Original fictional description","fr":"Autre description"},
            "media":[{"type":"support-texture","region":"us","url":"https://retrocollection.s3.fr-par.scw.cloud/one.png"},
            {"type":"support-texture","region":"jp","url":"https://retrocollection.s3.fr-par.scw.cloud/two.png"},
            {"type":"box-2D","region":"us","url":"https://retrocollection.s3.fr-par.scw.cloud/box.png"},
            {"type":"support-texture","region":"eu","url":"http://untrusted.invalid/label.png"}]}""")
        val candidate = checkNotNull(RetroBaseProvider(File(context.cacheDir, "contract-test")).parseGame(json, JSONObject().put("license", "Original fixture")))
        assertEquals("Orbit Field", candidate.title)
        assertEquals(listOf("us", "jp"), candidate.artwork.filter { it.isLabel }.map { it.region }.sortedDescending())
        assertEquals(3, candidate.artwork.size)
        assertFalse(candidate.artwork.single { it.type == "box-2D" }.isLabel)
        assertFalse(candidate.metadata.containsKey("publisher"))
        assertEquals("Original fictional description", candidate.metadata["description"]!!.first())
        assertEquals(3, candidate.metadata["title"]!!.size)
    }

    @Test fun migrationAndIndependentAcceptedFieldsSurviveDatabaseReopen() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/1.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val id = "a".repeat(64); val asset = "b".repeat(64)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { db.execSQL(setup.getString(it)) }
            db.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(id, "Original title", "content://original", asset, "Crop", 1, "content://original-label"))
            db.execSQL("INSERT INTO slots VALUES (1, ?)", arrayOf(id))
            db.execSQL("INSERT INTO collection_state VALUES (0, 1)")
            db.version = 1
        }
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2).build()
        var database = open()
        try {
            var repository = RoomCollectionRepository(context, database)
            val migrated = repository.initialize()
            assertEquals(1, migrated.selectedSlot)
            assertEquals(asset, migrated.slots[1]!!.label!!.assetId)
            assertEquals(LabelFit.Crop, migrated.slots[1]!!.label!!.fit)
            assertEquals(1, migrated.slots[1]!!.label!!.quarterTurns)
            assertTrue(migrated.slots[1]!!.metadata.isEmpty())
            val year = MetadataValue("2002", "Fixture A", "one", "https://example.invalid/one", "Original fixture", 1)
            val developer = year.copy(value = "Fixture Studio", provider = "Fixture B", recordId = "two")
            repository.setMetadata(id, mapOf("year" to year))
            repository.setMetadata(id, mapOf("developer" to developer))
            repository.setMedia(id, MediaSelection(LabelRef("c".repeat(64), sourceUri = "content://extra"), "box-2D", "us"))
            database.close(); database = open(); repository = RoomCollectionRepository(context, database)
            val restored = repository.initialize().slots[1]!!
            assertEquals(id, restored.id)
            assertEquals("Original title", restored.title)
            assertEquals(asset, restored.label!!.assetId)
            assertEquals(year, restored.metadata["year"])
            assertEquals(developer, restored.metadata["developer"])
            assertEquals("c".repeat(64), restored.media["box-2D"]!!.image.assetId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
