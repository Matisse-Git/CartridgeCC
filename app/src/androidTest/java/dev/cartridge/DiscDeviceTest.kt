package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.library.*
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.DirectoryScanner
import dev.cartridge.storage.SaveStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * ADR 0032 on the device: discs found to be one game by a playlist and by their names, through the production scanner and
 * Room; hand changes that a rescan keeps; a disc hidden before it joined; backups; and the real PCSX ReARMed started from
 * a playlist on a chosen disc, changing disc from its pause menu. The discs are the original PS1 probe with a mark of its own.
 */
class DiscDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder get() = File(context.filesDir, "m1-tree/${FixtureTreeProvider.MULTI}")
    private val tree get() = Uri.parse("content://$FIXTURE_TREE/tree/${FixtureTreeProvider.MULTI}")
    private val databases = mutableListOf<String>()

    /** A disc of the probe whose bytes are its own: a mark in the first sector, which nothing reads. */
    private fun disc(name: String) {
        val bin = File(context.filesDir, "cartridge-probe.bin").readBytes()
        UUID.randomUUID().toString().toByteArray().copyInto(bin, 256)
        File(folder, "$name.bin").writeBytes(bin)
        File(folder, "$name.cue").writeText("FILE \"$name.bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")
    }
    private fun database(): LibraryDatabase {
        val name = "m1-test-${UUID.randomUUID()}"; databases += name
        return Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").addMigrations(*LibraryDatabase.MIGRATIONS).build()
    }
    @Before fun clean() { folder.deleteRecursively(); folder.mkdirs() }
    @After fun remove() { folder.deleteRecursively(); DebugCollectionActivity.closeDatabases(); databases.forEach { context.deleteDatabase("$it.db") } }

    @Test fun discsAreGroupedByPlaylistAndByNameAndHandChangesSurviveRescan() = runBlocking {
        disc("Probe (Disc 1)"); disc("Probe (Disc 2)")
        // Named without its discs' region and with the line ends some tools write.
        File(folder, "Probe.m3u").writeText("Probe (Disc 1).cue\r\r\nProbe (Disc 2).cue\r\r\n")
        disc("Other (Japan) (Disc 1)"); disc("Other (Japan) (Disc 2)")
        val database = database()
        val repository = RoomCollectionRepository(context, database); repository.initialize()
        val scanner = DirectoryScanner(context.contentResolver, File(context.filesDir, "roms"), repository, repository)
        val first = scanner.scan(tree, {})
        assertEquals("Two games, not four discs", 2, first.added); assertEquals(0, first.playlists)
        var games = repository.initialize().slots.filterNotNull()
        assertEquals(2, games.size)
        val probe = games.single { it.fileName.startsWith("Probe") }; val other = games.single { it.fileName.startsWith("Other") }
        assertEquals("Probe", probe.title); assertEquals("Other (Japan)", other.title)
        assertEquals(listOf(1, 2), probe.discs.map { it.number }); assertEquals("Probe.m3u", probe.discs.first().playlist)
        assertEquals(GameDisc.NAMES, other.discs.first().origin)
        assertTrue(repository.hiddenGames().isEmpty())
        // A rescan finds the same two games, each once.
        val again = scanner.scan(tree, {})
        assertEquals(0, again.added); assertEquals(2, again.duplicates); assertEquals(2, repository.initialize().slots.filterNotNull().size)

        // Details: each disc's own file, by the name it has in the owner's folder.
        val facts = repository.facts(probe.id)
        assertEquals(listOf("Probe (Disc 1).cue", "Probe (Disc 2).cue"), facts.files.map { it.file })
        assertTrue(facts.files.all { it.cached && it.tracks == 1 && it.bytes >= 150528 })
        val request = repository.gameRequest(probe, 1)
        assertEquals(2, request.discs.size); assertEquals(1, request.disc); assertEquals(request.discs[1], request.rom)

        // Separated by hand: two games again, and a rescan does not put them back together. Undo does.
        val undo = repository.separateDiscs(probe.id)
        assertEquals(3, repository.initialize().slots.filterNotNull().size)
        scanner.scan(tree, {})
        assertEquals(3, repository.initialize().slots.filterNotNull().size)
        repository.undoDiscs(undo)
        games = repository.initialize().slots.filterNotNull()
        assertEquals(2, games.size); assertEquals(2, games.single { it.id == probe.id }.discs.size)

        // Grouped by hand: the game takes the card the joining disc had, and the disc's own is left as it was.
        repository.separateDiscs(other.id)
        val loose = repository.initialize().slots.filterNotNull().single { it.fileName == "Other (Japan) (Disc 2)" }
        val saves = SaveStore(File(context.filesDir, "saves")); val card = ByteArray(128 * 1024).also { it[0] = 'M'.code.toByte(); it[9000] = 7 }
        saves.write(loose.id, "sram", card)
        try {
            repository.addDisc(other.id, loose.id)
            val joined = repository.initialize().slots.filterNotNull().single { it.id == other.id }
            assertEquals(listOf(1, 2), joined.discs.map { it.number }); assertTrue(joined.discs.all { it.manual })
            assertArrayEquals(card, saves.read(other.id, "sram")); assertArrayEquals(card, saves.read(loose.id, "sram"))
            scanner.scan(tree, {})
            assertTrue("A scan leaves the owner's grouping as it is", repository.initialize().slots.filterNotNull().single { it.id == other.id }.discs.all { it.manual })
        } finally { File(context.filesDir, "saves/${other.id}.sram").delete(); File(context.filesDir, "saves/${loose.id}.sram").delete() }

        // A backup brings the discs back; one from before discs (version 1) brings each disc back as a game.
        val backup = repository.exportLibrary()
        val restored = RoomCollectionRepository(context, database()).also { it.initialize(); it.mergeLibrary(backup) }
        assertEquals(listOf(2, 2), restored.initialize().slots.filterNotNull().map { it.discs.size })
        val legacy = RoomCollectionRepository(context, database()).also { it.initialize(); it.mergeLibrary(backup.copy(discs = emptyList(), separated = emptySet())) }
        assertEquals(4, legacy.initialize().slots.filterNotNull().size)
    }

    @Test fun aLibraryFromBeforeDiscsKeepsItsHiddenDiscAndGetsItBackOnSeparate() = runBlocking {
        // A version 7 library, as the owner's is: every disc a game, the second one hidden by hand.
        val name = "m1-test-${UUID.randomUUID()}"; databases += name
        val file = context.getDatabasePath("$name.db").also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/7.json")
            .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) } }
            }
            schema.getJSONArray("setupQueries").let { setup -> repeat(setup.length()) { db.execSQL(setup.getString(it)) } }
            db.execSQL("INSERT INTO collection_state (singleton, selectedSlot, sort, favoritesOnly, search) VALUES (0,0,'Added',0,'')")
            db.execSQL("INSERT INTO slots VALUES (0,NULL)"); db.version = 7
        }
        val repository = RoomCollectionRepository(context, Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db")
            .addMigrations(*LibraryDatabase.MIGRATIONS).build())
        repository.initialize()
        val scanner = DirectoryScanner(context.contentResolver, File(context.filesDir, "roms"), repository, repository)
        // Named so they are not one game yet; a disc's identity is its content, not its name.
        disc("Legacy A"); disc("Legacy B")
        assertEquals(2, scanner.scan(tree, {}).added)
        val hidden = repository.initialize().slots.filterNotNull().single { it.fileName == "Legacy B" }
        repository.remove(hidden.id)
        listOf("cue", "bin").forEach { File(folder, "Legacy A.$it").renameTo(File(folder, "Legacy (Disc 1).$it")); File(folder, "Legacy B.$it").renameTo(File(folder, "Legacy (Disc 2).$it")) }
        File(folder, "Legacy (Disc 1).cue").writeText(File(folder, "Legacy (Disc 1).cue").readText().replace("Legacy A.bin", "Legacy (Disc 1).bin"))
        File(folder, "Legacy (Disc 2).cue").writeText(File(folder, "Legacy (Disc 2).cue").readText().replace("Legacy B.bin", "Legacy (Disc 2).bin"))
        scanner.scan(tree, {})
        val game = repository.initialize().slots.filterNotNull().single()
        assertEquals(listOf(1, 2), game.discs.map { it.number }); assertEquals(hidden.id, game.discs[1].id)
        assertTrue("The hidden disc joined its game", repository.hiddenGames().isEmpty())
        repository.separateDiscs(game.id)
        assertEquals(listOf(game.id), repository.initialize().slots.filterNotNull().map { it.id })
        assertEquals("Separated, it is hidden again", listOf(hidden.id), repository.hiddenGames().map { it.id })
    }

    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, predicate: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = predicate(it) }; ready }
    }
    private fun color(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        val future = CompletableFuture<Int>()
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.engine.surface.value); val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, Rect(view.width / 2 - 4, view.height / 2 - 4, view.width / 2 + 4, view.height / 2 + 4), bitmap,
                { result -> future.complete(if (result == PixelCopy.SUCCESS) bitmap.getPixel(4, 4) else 0); bitmap.recycle() }, Handler(Looper.getMainLooper()))
        }
        return future.get(3, TimeUnit.SECONDS)
    }
    private fun drawing(scenario: ActivityScenario<DebugCollectionActivity>) = ui.waitUntil(8_000) { color(scenario).let { Color.red(it) > Color.blue(it) + 40 || Color.blue(it) > Color.red(it) + 40 } }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    /** The screen as the player sees it, for looking at: `files/diagnostics/<name>.png`. A running game's own surface comes out black here. */
    private fun shot(name: String) {
        ui.waitForIdle(); Thread.sleep(400)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { picture ->
            File(context.filesDir, "diagnostics").apply { mkdirs() }.resolve("$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            picture.recycle()
        }
    }

    @Test fun pcsxStartsOnTheChosenDiscAndChangesDiscFromThePauseMenu() {
        disc("Probe (Disc 1)"); disc("Probe (Disc 2)")
        val name = "m1-test-${UUID.randomUUID()}"; databases += name
        ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name).putExtra("stayInMenu", true)).use { scenario ->
            await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
            Intents.init()
            try {
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(tree)))
                ui.tool("choose-folder").performClick()
            } finally { Intents.release() }
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.discs?.size == 2 }
            ui.home()
            var game: LibraryGame? = null
            scenario.onActivity { game = it.coordinator.state.value.selectedGame; assertEquals(1, it.coordinator.state.value.allGames.size); it.shell.openDetails() }
            // Details lists each disc with its file; a disc row starts the game on that disc.
            ui.waitUntil(5_000) { ui.shown("details-disc-1") }
            assertTrue(ui.shown("details-disc-0"))
            shot("disc-details")
            ui.onNodeWithTag("details-disc-1").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            scenario.onActivity { assertEquals(1, it.engine.status.value.disc); assertEquals(2, it.engine.status.value.discs) }
            assertTrue(File(context.filesDir, "playlists/${game!!.id}.m3u").readLines().size == 2)
            drawing(scenario)
            pause(scenario)
            shot("disc-pause")
            ui.onNodeWithTag("menu-6").performClick(); ui.waitUntil(5_000) { ui.shown("disc-0") }
            shot("disc-change")
            ui.onNodeWithTag("disc-0").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            scenario.onActivity { assertEquals(0, it.engine.status.value.disc); assertEquals(1, it.coordinator.state.value.discBadge) }
            ui.waitUntil(5_000) { ui.shown("disc-badge") }
            shot("disc-badge")
            drawing(scenario)
            // A state made on disc 1 brings disc 1 back after a change to disc 2.
            pause(scenario)
            ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
            ui.onNodeWithTag("menu-6").performClick(); ui.waitUntil(5_000) { ui.shown("disc-1") }
            ui.onNodeWithTag("disc-1").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running && it.engine.status.value.disc == 1 }
            pause(scenario)
            ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
            scenario.onActivity { assertEquals("The state put its disc back", 0, it.engine.status.value.disc) }
            ui.onNodeWithTag("menu-3").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
            // One card for the game, whichever disc wrote it; the next start is on the disc last in the drive.
            assertNotNull(SaveStore(File(context.filesDir, "saves")).read(game!!.id, "sram"))
            assertNull(SaveStore(File(context.filesDir, "saves")).read(game!!.discs[1].id, "sram"))
            await(scenario) { it.coordinator.state.value.selectedGame?.disc == 0 }
            ui.tool("insert").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
            scenario.onActivity { assertEquals(0, it.engine.status.value.disc) }
            pause(scenario); ui.onNodeWithTag("menu-3").performClick()
            await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library }
        }
    }
}
