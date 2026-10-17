package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.library.*
import dev.cartridge.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "7".repeat(64)
    private val other = "8".repeat(64)
    private fun image(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(160, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 130, 170)) }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
    }
    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }; output.toByteArray()
    }
    @Test fun roundTripPreservesImagesHistoryRemovedMembershipAndSavesWithoutRomsOrOverwrites() = runBlocking {
        val directory = File(context.filesDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val firstDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val secondDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val preferences = context.getSharedPreferences(directory.name, 0)
        try {
            val first = RoomCollectionRepository(context, firstDb); first.initialize()
            first.add(LibraryGame(id, "Original probe", "content://fixture/probe.gb", system = GameSystem.GB))
            first.add(LibraryGame(other, "Hidden probe", "content://fixture/probe.gbc", system = GameSystem.GBC)); first.remove(other)
            val firstLabels = LabelStore(File(directory, "labels"))
            val original = image(); val label = firstLabels.importStream("content://fixture/original.png") { original.inputStream() }.copy(fit = LabelFit.Crop, quarterTurns = 1)
            first.setLabel(id, label)
            val accepted = MetadataValue("2001", "Original provider", "record", "https://example.invalid", "Original fixture", 123)
            first.setMetadata(id, mapOf("year" to accepted)); first.setMedia(id, MediaSelection(label.copy(quarterTurns = 0), "screenshot", "world"))
            first.setFavorite(id, true); first.recordPlay(id, 42000, 321)
            val saves = SaveStore(File(directory, "saves")); saves.write(id, "sram", byteArrayOf(67, 1)); saves.write(id, "rtc", byteArrayOf(35)); saves.write(id, "state", byteArrayOf(1, 2, 3))
            val session = SessionStore(File(directory, "session")); session.write(id, GameSystem.GB, byteArrayOf(4, 5, 6), 456)
            preferences.edit().putBoolean("swapGameButtons", true).putString("layout", "Grid").commit()
            // Shader choices travel by name; the library itself is not part of a backup (ADR 0027).
            val shaders = dev.cartridge.shaders.ShaderChoices(preferences)
            val gameTarget = dev.cartridge.shaders.ShaderTarget.Game(id, GameSystem.GB, "Original probe")
            shaders.set(dev.cartridge.shaders.ShaderTarget.Global, dev.cartridge.shaders.ShaderChoice.Preset("crt/crt-royale.glslp"))
            shaders.set(dev.cartridge.shaders.ShaderTarget.Console(GameSystem.GB), dev.cartridge.shaders.ShaderChoice.Off)
            shaders.set(gameTarget, dev.cartridge.shaders.ShaderChoice.Preset("handheld/gb-dot-matrix-DMG.glslp"))
            val output = ByteArrayOutputStream(); BackupStore(first, firstLabels, saves, session, File(directory, "staging")).export(output, BackupPreferences.capture(preferences))
            val bytes = output.toByteArray(); val entries = unzip(bytes)
            assertEquals(6, entries.size); assertTrue(entries.keys.none { it.endsWith(".gb") || it.endsWith(".gba") || it.endsWith(".gbc") || it.contains("bios") })
            val second = RoomCollectionRepository(context, secondDb); second.initialize()
            val secondLabels = LabelStore(File(directory, "restored-labels")); val secondSaves = SaveStore(File(directory, "restored-saves")); val secondSession = SessionStore(File(directory, "restored-session"))
            val store = BackupStore(second, secondLabels, secondSaves, secondSession, File(directory, "restore-staging"))
            store.inspect(bytes.inputStream()).use { review ->
                assertEquals(2, review.library.games.size); assertEquals(1, review.imageCount); assertEquals(3, review.saveCount)
                assertTrue(store.merge(review).contains("2 new games"))
                assertEquals("Grid", review.preferences.getString("layout"))
                // A backup made before shader choices existed still restores.
                BackupPreferences.validate(org.json.JSONObject(review.preferences.toString()).apply { remove("shaders") })
                preferences.edit().putBoolean("swapGameButtons", false).commit()
                shaders.resetConsoles(); shaders.set(gameTarget, dev.cartridge.shaders.ShaderChoice.Inherit)
                assertFalse(preferences.getBoolean("swapGameButtons", false)) // Optional preferences are not silently applied.
                assertEquals(dev.cartridge.shaders.ResolvedShader(null, dev.cartridge.shaders.ShaderLevel.None), shaders.resolve(GameSystem.GB, id))
                BackupPreferences.restore(preferences, review.preferences); assertTrue(preferences.getBoolean("swapGameButtons", false))
                assertEquals(dev.cartridge.shaders.ResolvedShader("handheld/gb-dot-matrix-DMG.glslp", dev.cartridge.shaders.ShaderLevel.Game), shaders.resolve(GameSystem.GB, id))
                assertEquals(dev.cartridge.shaders.ResolvedShader(null, dev.cartridge.shaders.ShaderLevel.Console), shaders.resolve(GameSystem.GB, null))
                assertEquals(dev.cartridge.shaders.ResolvedShader("crt/crt-royale.glslp", dev.cartridge.shaders.ShaderLevel.Global), shaders.resolve(GameSystem.GBA, null))
            }
            val restored = second.initialize().slots.filterNotNull().single(); assertEquals(id, restored.id); assertEquals(GameSystem.GB, restored.system)
            assertEquals(label, restored.label); assertEquals(accepted, restored.metadata["year"]); assertEquals(42000L, restored.playMs); assertEquals(321L, restored.lastPlayed); assertTrue(restored.favorite)
            assertEquals(setOf(other), second.exportLibrary().removed)
            assertArrayEquals(original, secondLabels.exportOriginal(label.assetId).readBytes())
            assertArrayEquals(byteArrayOf(67, 1), secondSaves.read(id, "sram")); assertArrayEquals(byteArrayOf(35), secondSaves.read(id, "rtc")); assertArrayEquals(byteArrayOf(1, 2, 3), secondSaves.read(id, "state"))
            assertArrayEquals(byteArrayOf(4, 5, 6), secondSession.read(secondSession.available()!!))
            val local = accepted.copy(value = "1998", provider = "Local")
            second.setMetadata(id, mapOf("year" to local)); second.setLabel(id, label.copy(fit = LabelFit.Fit)); secondSaves.write(id, "state", byteArrayOf(99))
            second.remove(id)
            store.inspect(bytes.inputStream()).use { assertTrue(store.merge(it).contains("0 new games")) }
            assertTrue(second.initialize().slots.all { it == null }); assertEquals(local, LibraryJson.metadata(second.exportLibrary().games.first { it.id == id }.metadataJson)["year"])
            assertArrayEquals(byteArrayOf(99), secondSaves.read(id, "state")); assertEquals(42000L, second.exportLibrary().games.first { it.id == id }.playMs)
            assertTrue(File(directory, "restore-staging").listFiles().orEmpty().isEmpty())
        } finally { firstDb.close(); secondDb.close(); preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
    @Test fun tamperedUnsafeUnknownAndOversizedArchiveDataAreRejectedBeforeAcceptance() = runBlocking {
        val directory = File(context.filesDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val preferences = context.getSharedPreferences(directory.name, 0)
        try {
            val repository = RoomCollectionRepository(context, database); repository.initialize(); repository.add(LibraryGame(id, "Original", "content://fixture"))
            val labels = LabelStore(File(directory, "labels")); val label = labels.importStream("content://image") { image().inputStream() }; repository.setLabel(id, label)
            val saves = SaveStore(File(directory, "saves")); saves.write(id, "sram", byteArrayOf(43))
            val store = BackupStore(repository, labels, saves, SessionStore(File(directory, "session")), File(directory, "staging"))
            val output = ByteArrayOutputStream(); store.export(output, BackupPreferences.capture(preferences)); val original = unzip(output.toByteArray())
            val bad = listOf(
                original.toMutableMap().apply { this["images/${label.assetId}.image"] = byteArrayOf(0, 1) },
                original.toMutableMap().apply { this["../escape"] = byteArrayOf(1) },
                original.toMutableMap().apply { this["roms/unwanted.gba"] = byteArrayOf(1) },
                original.toMutableMap().apply { this["bomb.bin"] = ByteArray(17 * 1024 * 1024) },
                original.toMutableMap().apply { this["backup.json"] = JSONObject(String(checkNotNull(this["backup.json"]))).put("version", 99).toString().toByteArray() })
            bad.forEach { entries ->
                try { store.inspect(zip(entries).inputStream()).close(); fail("Invalid archive must fail") } catch (_: Exception) { }
                assertEquals(1, repository.initialize().slots.filterNotNull().size); assertArrayEquals(byteArrayOf(43), saves.read(id, "sram"))
                assertTrue(File(directory, "staging").listFiles().orEmpty().isEmpty())
            }
        } finally { database.close(); preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 40)) }
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); bitmap.recycle(); output.toByteArray()
    }
    /** ADR 0028: the list of saves, the previous quick save and their pictures go in only when asked, and come back as they were. */
    @Test fun everySaveStateTravelsOnlyWhenAskedAndEachPictureNeedsItsSave() = runBlocking {
        val directory = File(context.filesDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val firstDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val secondDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val preferences = context.getSharedPreferences(directory.name, 0)
        try {
            val first = RoomCollectionRepository(context, firstDb); first.initialize()
            first.add(LibraryGame(id, "Original probe", "content://fixture/probe.gb", system = GameSystem.GB))
            val labels = LabelStore(File(directory, "labels"))
            val saves = SaveStore(File(directory, "saves")); val slots = StateSlots(File(directory, "saves"))
            slots.writeQuick(id, byteArrayOf(1), jpeg()); slots.writeQuick(id, byteArrayOf(2), jpeg())
            val listed = slots.write(id, GameSystem.GB, byteArrayOf(3), jpeg(), now = 777)
            val session = SessionStore(File(directory, "session"))
            suspend fun export(all: Boolean) = ByteArrayOutputStream().also {
                BackupStore(first, labels, saves, session, File(directory, "staging"), includeSlots = all).export(it, BackupPreferences.capture(preferences)) }.toByteArray()
            assertTrue(unzip(export(false)).keys.none { it.startsWith("slots/") })
            val bytes = export(true); val entries = unzip(bytes)
            assertEquals(setOf("slots/$id/previous.state", "slots/$id/$listed.state", "slots/$id/quick.jpg", "slots/$id/previous.jpg", "slots/$id/$listed.jpg"),
                entries.keys.filter { it.startsWith("slots/") }.toSet())

            val second = RoomCollectionRepository(context, secondDb); second.initialize()
            val restoredSaves = SaveStore(File(directory, "restored-saves"))
            val store = BackupStore(second, LabelStore(File(directory, "restored-labels")), restoredSaves, SessionStore(File(directory, "restored-session")), File(directory, "restore-staging"))
            store.inspect(bytes.inputStream()).use { review -> assertEquals(3, review.saveCount); store.merge(review) }
            val restored = StateSlots(File(directory, "restored-saves")).list(id, GameSystem.GB)
            assertEquals(listOf(SlotKind.Quick, SlotKind.Previous, SlotKind.Saved), restored.map { it.kind })
            assertEquals(listed, restored.last().name); assertEquals(777, restored.last().savedAt)
            assertTrue(restored.all { it.thumbnail != null })
            assertArrayEquals(byteArrayOf(2), restoredSaves.read(id, "state"))
            assertArrayEquals(byteArrayOf(1), StateSlots(File(directory, "restored-saves")).read(id, GameSystem.GB, StateSlots.PREVIOUS))

            // A picture whose save was taken out, and a save that names another core build, are refused before anything is accepted.
            fun without(path: String) = entries.toMutableMap().apply {
                remove(path)
                val root = JSONObject(String(checkNotNull(this["backup.json"])))
                val blobs = root.getJSONArray("blobs"); val kept = org.json.JSONArray()
                for (index in 0 until blobs.length()) blobs.getJSONObject(index).takeIf { it.getString("path") != path }?.let(kept::put)
                this["backup.json"] = root.put("blobs", kept).toString().toByteArray()
            }
            val otherCore = entries.toMutableMap().apply {
                val root = JSONObject(String(checkNotNull(this["backup.json"])))
                val blobs = root.getJSONArray("blobs")
                for (index in 0 until blobs.length()) blobs.getJSONObject(index).takeIf { it.optString("kind") == "slot" }?.put("core", "an-older-build")
                this["backup.json"] = root.toString().toByteArray()
            }
            listOf(without("slots/$id/$listed.state"), otherCore).forEach { archive ->
                try { store.inspect(zip(archive).inputStream()).close(); fail("Invalid archive must fail") } catch (_: Exception) { }
                assertTrue(File(directory, "restore-staging").listFiles().orEmpty().isEmpty())
            }
        } finally { firstDb.close(); secondDb.close(); preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
    private fun action(tag: String) { ui.onNodeWithTag(tag).performScrollTo().performClick() }
    @Test fun safExportReviewOptInAndCancelledReviewWorkInTheRealConsolePanel() {
        val name = "m1-test-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        runBlocking { val repository = RoomCollectionRepository(context, database); repository.initialize(); repository.add(LibraryGame(id, "Original backup title", "content://fixture", system = GameSystem.GB)) }
        database.close()
        val preferences = context.getSharedPreferences("$name-presentation", 0)
        val fixture = File(context.filesDir, "m1-fixtures/backup.zip").apply { parentFile!!.mkdirs() }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                fun await(check: (DebugCollectionActivity) -> Boolean) { ui.waitUntil(30000) { var ready = false; scenario.onActivity { ready = check(it) }; ready } }
                await { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                ui.tool("console-settings").performClick()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://dev.cartridge.fixture/backup.zip"))))
                    action("settings-export"); await { !it.coordinator.state.value.busy && fixture.length() > 0 }
                } finally { Intents.release() }
                assertEquals(1, JSONObject(String(unzip(fixture.readBytes()).getValue("backup.json"))).getJSONArray("games").length())
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://dev.cartridge.fixture/backup.zip"))))
                    action("settings-restore"); await { !it.coordinator.state.value.busy }; ui.onNodeWithTag("page-backup").assertExists()
                    action("backup-cancel"); ui.tool("console-settings").performClick(); action("settings-restore"); await { !it.coordinator.state.value.busy }
                } finally { Intents.release() }
                preferences.edit().putString("layout", "Grid").commit()
                action("backup-settings"); action("backup-merge"); await { !it.coordinator.state.value.busy && it.coordinator.state.value.layout.name == "Horizontal" }
                assertEquals("Horizontal", preferences.getString("layout", ""))
                scenario.recreate(); await { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(id, it.coordinator.state.value.selectedGame!!.id) }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db"); preferences.edit().clear().commit(); fixture.delete() }
    }
}
