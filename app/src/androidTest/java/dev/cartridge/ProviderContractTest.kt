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
        // A flat label is kept as extra artwork; only box art is wrapped round a case (ADR 0029).
        assertEquals(listOf("us"), candidate.artwork.filter { it.isCaseArt }.map { it.region })
        assertEquals(3, candidate.artwork.size)
        assertTrue(candidate.artwork.single { it.type == "box-2D" }.isCaseArt)
        assertFalse(candidate.metadata.containsKey("publisher"))
        assertEquals("Original fictional description", candidate.metadata["description"]!!.first())
        assertEquals(3, candidate.metadata["title"]!!.size)
        val ranked = dev.cartridge.scraping.ScrapePreferences("jp", "fr").rank(candidate)
        assertEquals("Orbit Japan", ranked.metadata["title"]!!.first())
        assertEquals("Autre description", ranked.metadata["description"]!!.first())
        assertEquals("jp", ranked.metadataTags["title"]!!.first().region)
        assertEquals("fr", ranked.metadataTags["description"]!!.first().language)
    }

    /** ScreenScraper's v2 game record as its API documents it; every name and text here is invented. */
    @Test fun screenScraperRecordKeepsCaseArtWithoutTheLoginAndRegionalDetails() {
        val login = "devid=dev&devpassword=secret&softname=x&ssid=me&sspassword=hunter2"
        val json = JSONObject("""{"id":"4242","noms":[{"region":"jp","text":"Orbit Japan"},{"region":"us","text":"Orbit Field"},{"region":"ss","text":"Orbit Field"}],
            "systeme":{"id":"12","text":"Game Boy Advance"},"editeur":{"id":"1","text":"Fictional Publisher"},"developpeur":{"id":"2","text":"Fictional Studio"},
            "joueurs":{"text":"1-2"},"synopsis":[{"langue":"fr","text":"Autre description"},{"langue":"en","text":"Original fictional description"}],
            "dates":[{"region":"jp","text":"2001-03-21"},{"region":"us","text":"2002-06-11"}],
            "genres":[{"id":"1","principale":"1","noms":[{"langue":"fr","text":"Plateforme"},{"langue":"en","text":"Platform"}]},{"id":"2","principale":"0","noms":[{"langue":"en","text":"Other"}]}],
            "medias":[{"type":"box-texture","parent":"jeu","region":"us","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?$login&systemeid=12&jeuid=4242&media=box-texture(us)","format":"png"},
            {"type":"box-2D","parent":"jeu","region":"eu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?$login&systemeid=12&jeuid=4242&media=box-2D(eu)","format":"png"},
            {"type":"ss","parent":"jeu","region":"wor","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?$login&media=ss(wor)","format":"png"},
            {"type":"box-2D","parent":"jeu","region":"us","url":"https://untrusted.invalid/box.png","format":"png"}],
            "rom":{"romsha1":"ABCDEF","romcrc":"1234"}}""")
        val provider = dev.cartridge.scraping.ScreenScraperProvider { dev.cartridge.scraping.ScreenScraperLogin() }
        val candidate = checkNotNull(provider.parseGame(json))
        assertEquals("Orbit Field", candidate.title)
        assertEquals(listOf("Orbit Field", "Orbit Japan"), candidate.metadata["title"])
        assertEquals(listOf("2002", "2001"), candidate.metadata["year"])
        assertEquals(listOf("Platform"), candidate.metadata["genre"]); assertEquals(listOf("1-2"), candidate.metadata["players"])
        assertEquals("Fictional Studio", candidate.metadata["developer"]!!.single()); assertEquals("Fictional Publisher", candidate.metadata["publisher"]!!.single())
        assertEquals(listOf("box-texture", "box-2D"), candidate.artwork.map { it.type })
        assertTrue(candidate.artwork.none { "sspassword" in it.url || "devid" in it.url || "ssid" in it.url })
        assertFalse(candidate.exactHash)
        assertEquals("Original fictional description", dev.cartridge.scraping.ScrapePreferences("us", "en").rank(candidate).metadata["description"]!!.first())
        // An empty search answers with one empty game, which is no candidate.
        assertNull(provider.parseGame(JSONObject("{}")))
    }

    /** Needs the owner's login typed into this build's Settings, the network and `-e liveProvider true`. */
    @Test fun liveScreenScraperFindsCaseArtWithTheOwnersLogin(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("liveProvider") != "true") return@runBlocking
        val settings = dev.cartridge.scraping.ArtworkSettings(context)
        assertTrue("Type the ScreenScraper login into Settings › Library › Artwork first", settings.login.complete)
        val provider = dev.cartridge.scraping.ScreenScraperProvider { settings.login }
        val found = provider.search(arguments.getString("liveTitle") ?: "Sonic the Hedgehog", system = dev.cartridge.domain.GameSystem.GENESIS)
        assertTrue("No match", found.isNotEmpty())
        val art = checkNotNull(found.flatMap { it.artwork }.firstOrNull { it.isCaseArt }) { "No case art in ${found.size} matches" }
        val bytes = provider.image(art)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertTrue(bounds.outWidth in 1..dev.cartridge.scraping.ScreenScraperProvider.MAX_WIDTH)
        android.util.Log.i("CartridgeScrape", "Live ScreenScraper: ${found.size} matches, ${art.type} ${art.region} ${bounds.outWidth}x${bounds.outHeight}")
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
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7).build()
        var database = open()
        try {
            var repository = RoomCollectionRepository(context, database)
            val migrated = repository.initialize()
            assertEquals(0, migrated.selectedSlot)
            assertEquals(asset, migrated.slots[0]!!.label!!.assetId)
            assertEquals(LabelFit.Crop, migrated.slots[0]!!.label!!.fit)
            assertEquals(1, migrated.slots[0]!!.label!!.quarterTurns)
            assertTrue(migrated.slots[0]!!.metadata.isEmpty())
            val year = MetadataValue("2002", "Fixture A", "one", "https://example.invalid/one", "Original fixture", 1)
            val developer = year.copy(value = "Fixture Studio", provider = "Fixture B", recordId = "two")
            repository.setMetadata(id, mapOf("year" to year))
            repository.setMetadata(id, mapOf("developer" to developer))
            repository.setMedia(id, MediaSelection(LabelRef("c".repeat(64), sourceUri = "content://extra"), "box-2D", "us"))
            database.close(); database = open(); repository = RoomCollectionRepository(context, database)
            val restored = repository.initialize().slots[0]!!
            assertEquals(id, restored.id)
            assertEquals("Original title", restored.title)
            assertEquals(asset, restored.label!!.assetId)
            assertEquals(year, restored.metadata["year"])
            assertEquals(developer, restored.metadata["developer"])
            assertEquals("c".repeat(64), restored.media["box-2D"]!!.image.assetId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    /** ADR 0029: one write and one Undo; a cartridge label is kept as extra media; the owner's choices survive Fill all. */
    @Test fun automaticApplyIsOneUndoableWriteThatKeepsTheOldLabel() = runBlocking {
        val name = "automatic-test-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val repository = RoomCollectionRepository(context, database)
            val id = "a".repeat(64)
            repository.initialize(); repository.add(LibraryGame(id, "Orbit Field (USA)", "content://original"))
            val flat = LabelRef("b".repeat(64), provenance = AssetProvenance("Original fixture", "r", "support-texture", "us", "https://example.invalid/flat", "Original fixture", 1))
            val owner = MetadataValue("Owner's developer", "Owner", "", "", "", 2)
            repository.setLabel(id, flat); repository.setMetadata(id, mapOf("developer" to owner))
            val wrap = LabelRef("c".repeat(64), provenance = AssetProvenance("Original fixture", "r", "box-texture", "us", "https://example.invalid/wrap", "Original fixture", 3, automatic = true))
            val fields = mapOf("developer" to MetadataValue("Scraped developer", "Original fixture", "r", "", "", 3, automatic = true),
                "players" to MetadataValue("1-2", "Original fixture", "r", "", "", 3, automatic = true))
            assertEquals(2, repository.applyScraped(id, wrap, fields, FillPolicy()))
            var game = repository.initialize().slots.single()!!
            assertEquals(wrap, game.label); assertEquals(owner, game.metadata["developer"]); assertEquals("1-2", game.metadata["players"]!!.value)
            assertTrue(game.metadata["players"]!!.automatic); assertTrue(game.label!!.provenance!!.automatic)
            assertEquals(flat.assetId, game.media["support-texture"]!!.image.assetId) // Kept, not drawn.
            assertEquals("Orbit Field (USA)", game.fileName)
            assertEquals(0, repository.applyScraped(id, wrap, fields, FillPolicy(FillMode.All))) // Nothing new, nothing written.
            assertEquals(1, repository.applyScraped(id, null, fields, FillPolicy(FillMode.All, replaceManual = true)))
            assertEquals("Scraped developer", repository.initialize().slots.single()!!.metadata["developer"]!!.value)
            assertTrue(repository.undoEnrichment(id))
            game = repository.initialize().slots.single()!!
            assertEquals(owner, game.metadata["developer"]); assertEquals(wrap, game.label)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwoUpgradePreservesScrapedProvenanceAndAllowsCollectionGrowth() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/2.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val id = "a".repeat(64)
        val provenance = AssetProvenance("Original fixture", "record-one", "support-texture", "us", "https://example.invalid/label", "Original fixture", 123)
        val label = LabelRef("b".repeat(64), LabelFit.Crop, 1, provenance.url, provenance)
        val year = MetadataValue("2002", provenance.provider, provenance.recordId, provenance.url, provenance.license, 123)
        val media = MediaSelection(LabelRef("c".repeat(64), sourceUri = "content://original-extra"), "box-2D", "jp")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { db.execSQL(setup.getString(it)) }
            db.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(id, "Original title", "content://original", label.assetId,
                label.fit.name, label.quarterTurns, label.sourceUri!!, LibraryJson.provenance(provenance)!!,
                LibraryJson.metadata(mapOf("year" to year)), LibraryJson.media(mapOf(media.type to media))))
            db.execSQL("INSERT INTO slots VALUES (2, ?)", arrayOf(id)); db.execSQL("INSERT INTO collection_state VALUES (0, 2)"); db.version = 2
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7).build()
        try {
            val repository = RoomCollectionRepository(context, database)
            val restored = repository.initialize()
            assertEquals(0, restored.selectedSlot)
            assertEquals(label, restored.slots[0]!!.label)
            assertEquals(year, restored.slots[0]!!.metadata["year"])
            assertEquals(media, restored.slots[0]!!.media["box-2D"])
            repeat(5) { assertTrue(repository.add(LibraryGame((it + 1).toString().repeat(64), "Original $it", "content://$it"))) }
            assertFalse(repository.add(restored.slots[0]!!))
            repository.rememberDirectory("content://original-tree/tree/root")
            assertEquals(6, repository.initialize().slots.size)
            assertEquals(label, repository.initialize().slots[0]!!.label)
            assertEquals(listOf("content://original-tree/tree/root"), repository.directories())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun versionThreeLegacyDuplicatesCollapseAndRemovalSurvivesReopening() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/3.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val id = "a".repeat(64); val regionalId = "b".repeat(64); val asset = "c".repeat(64)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { db.execSQL(setup.getString(it)) }
            for (gameId in listOf(id, regionalId)) db.execSQL("INSERT INTO games (id,title,sourceUri,labelAssetId,labelFit,labelQuarterTurns) VALUES (?,?,?,?,'Crop',1)",
                arrayOf(gameId, "Same title", "content://original", asset))
            repeat(3) { db.execSQL("INSERT INTO slots VALUES (?,?)", arrayOf<Any>(it, id)) }
            db.execSQL("INSERT INTO slots VALUES (3,?)", arrayOf(regionalId))
            db.execSQL("INSERT INTO collection_state VALUES (0,2)"); db.version = 3
        }
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7).build()
        var database = open()
        try {
            var repository = RoomCollectionRepository(context, database)
            val normalized = repository.initialize()
            assertEquals(listOf(id, regionalId), normalized.slots.map { it!!.id })
            assertEquals(0, normalized.selectedSlot)
            assertEquals(asset, normalized.slots[0]!!.label!!.assetId)
            repository.remove(id)
            database.close(); database = open(); repository = RoomCollectionRepository(context, database)
            assertEquals(listOf(regionalId), repository.initialize().slots.map { it!!.id })
            assertFalse(repository.add(normalized.slots[0]!!)) // Rescan must respect removal.
            repository.restore(id, 0)
            assertEquals(normalized, repository.initialize())
            repository.assign(1, normalized.slots[0]!!)
            assertEquals(2, repository.initialize().slots.size) // Legacy assign cannot introduce a duplicate.
            repository.remove(id); repository.remove(regionalId)
            assertEquals(listOf<LibraryGame?>(null), repository.initialize().slots)
            assertTrue(repository.add(normalized.slots[0]!!, restoreRemoved = true))
            assertEquals(asset, repository.initialize().slots[0]!!.label!!.assetId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
