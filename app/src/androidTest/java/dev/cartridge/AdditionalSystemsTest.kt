package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.library.*
import dev.cartridge.scraping.RetroBaseProvider
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AdditionalSystemsTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun choose(file: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://dev.cartridge.fixture/$file"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    private fun pixels(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        val result = CompletableFuture<Int>()
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.engine.surface.value)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x-4, y-4, x+4, y+4), bitmap, { status ->
                val colors = IntArray(64)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(colors, 0, 8, 0, 0, 8, 8)
                result.complete(if (status == PixelCopy.SUCCESS && colors.toSet().size > 1) colors.contentHashCode() else 0)
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun button(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.waitForIdle() // Route Compose's resumed state before injecting a physical gameplay key.
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    private fun resume(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    private fun eject(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-3").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    @Test fun originalGbAndColorProgramsRenderReceiveInputRestoreStatesAndKeepBatteryClock() {
        val name = "m1-test-${UUID.randomUUID()}"
        val seed = UUID.randomUUID().toString().take(8).toByteArray()
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        for (ext in listOf("gb", "gbc")) {
            val bytes = File(context.filesDir, "cartridge-probe.$ext").readBytes()
            seed.copyInto(bytes, 0x134)
            File(fixtures, "probe.$ext").writeBytes(bytes)
        }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                for (system in listOf(GameSystem.GB, GameSystem.GBC)) {
                    choose("probe.${system.extension}"); await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == system }
                    var id = ""; scenario.onActivity { id = it.coordinator.state.value.selectedGame!!.id; assertEquals(45, it.collectionSurface!!.meshInstanceCount) }
                    ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                    var before = 0; ui.waitUntil(8000) { before = pixels(scenario); before != 0 }
                    button(scenario); var changed = 0; ui.waitUntil(8000) { changed = pixels(scenario); changed != 0 && changed != before }
                    pause(scenario)
                    val saves = SaveStore(File(context.filesDir, "saves"))
                    assertEquals(1, saves.read(id, "sram")!![1].toInt())
                    if (system == GameSystem.GBC) {
                        val rtc = saves.read(id, "rtc")!!; assertEquals(48, rtc.size); assertEquals(36, rtc[20].toInt())
                    }
                    ui.onNodeWithTag("menu-1").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    assertTrue(saves.read(id, "state")!!.isNotEmpty())
                    resume(scenario); button(scenario); ui.waitUntil(8000) { pixels(scenario) == before }
                    pause(scenario); ui.onNodeWithTag("menu-2").performClick(); await(scenario) { !it.coordinator.state.value.busy }
                    resume(scenario); ui.waitUntil(8000) { pixels(scenario) == changed }; pause(scenario)
                    // Restored RAM must be synchronized from mGBA's temporary state buffer.
                    assertEquals(1, saves.read(id, "sram")!![1].toInt())
                    resume(scenario); button(scenario); pause(scenario)
                    assertEquals(0, saves.read(id, "sram")!![1].toInt()); eject(scenario)
                    scenario.recreate(); await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                    scenario.onActivity { assertEquals(system, it.coordinator.state.value.selectedGame!!.system); assertEquals(id, it.coordinator.state.value.selectedGame!!.id) }
                    ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                    var boot = 0; ui.waitUntil(8000) { boot = pixels(scenario); boot != 0 }; pause(scenario)
                    android.util.Log.i("CartridgeSystems", "$system boot=$boot expected=$changed original=$before RAM=${saves.read(id, "sram")!!.take(2)}")
                    assertEquals(0, saves.read(id, "sram")!![1].toInt())
                    assertEquals("Boot must use the retained battery choice", before, boot)
                    if (system == GameSystem.GBC) assertEquals(37, saves.read(id, "rtc")!![20].toInt())
                    eject(scenario)
                }
                scenario.onActivity { assertEquals(setOf(GameSystem.GB, GameSystem.GBC), it.coordinator.state.value.allGames.map { it.system }.toSet()) }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    @Test fun schemaSixMigratesGbaIdentityAndMixedSafScanningPreservesSystemsAndLabelProportions() = runBlocking {
        val name = "m5-system-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("dev.cartridge.library.LibraryDatabase/6.json").use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { n ->
                val entity = entities.getJSONObject(n); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) } }
            }
            schema.getJSONArray("setupQueries").let { setup -> repeat(setup.length()) { db.execSQL(setup.getString(it)) } }
            db.execSQL("INSERT INTO games (id,title,sourceUri,labelFit,labelQuarterTurns,favorite,playMs) VALUES ('${"f".repeat(64)}','Original legacy','content://fixture','Fit',0,1,1234)")
            db.execSQL("INSERT INTO collection_state VALUES (0,0,'Added',0,'')"); db.execSQL("INSERT INTO slots VALUES (0,'${"f".repeat(64)}')"); db.version = 6
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_6_7).build()
        try {
            val repository = RoomCollectionRepository(context, database)
            val legacy = repository.initialize().slots.single()!!
            assertEquals(GameSystem.GBA, legacy.system); assertTrue(legacy.favorite); assertEquals(1234L, legacy.playMs)
            val tree = File(context.filesDir, "m1-tree").apply { mkdirs() }
            tree.listFiles()?.forEach { it.deleteRecursively() }
            for (ext in listOf("gba", "gb", "gbc")) File(context.filesDir, "cartridge-probe.$ext").copyTo(File(tree, "m5-$ext.$ext"), overwrite = true)
            val scanner = DirectoryScanner(context.contentResolver, File(context.filesDir, "roms"), repository, repository)
            val uri = android.provider.DocumentsContract.buildTreeDocumentUri("dev.cartridge.fixture-tree", "root")
            val first = scanner.scan(uri, {}); assertEquals(first.message, 3, first.added); assertEquals(0, scanner.scan(uri, {}).added)
            assertEquals(setOf(GameSystem.GBA, GameSystem.GB, GameSystem.GBC), repository.initialize().slots.filterNotNull().map { it.system }.toSet())
            val bitmap = Bitmap.createBitmap(64, 56, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val store = LabelStore(File(context.cacheDir, "m5-label-${UUID.randomUUID()}"))
            val image = File(context.cacheDir, "m5-original.png")
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val label = store.importStream("Original test") { image.inputStream() }
            assertEquals(448, store.decode(label, GameSystem.GBC).height); assertEquals(256, store.decode(label).height)
            val provider = RetroBaseProvider(File(context.cacheDir, "m5-provider"))
            val record = JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("name", "Original fixture").put("platform", JSONObject().put("name", "Game Boy Color"))
            assertNull(provider.parseGame(record, null)); assertEquals("Game Boy Color", provider.parseGame(record, null, system = GameSystem.GBC)!!.platform)
        } finally {
            database.close(); context.deleteDatabase(name)
            for (ext in listOf("gba", "gb", "gbc")) File(context.filesDir, "m1-tree/m5-$ext.$ext").delete()
        }
    }
}
