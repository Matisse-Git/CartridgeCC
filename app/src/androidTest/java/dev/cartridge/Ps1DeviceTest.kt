package dev.cartridge

import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.Media
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.SaveBridge
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import androidx.room.Room
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Actual PCSX/native GPU output and production views; only the SAF chooser result is stubbed. */
class Ps1DeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, predicate: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(30_000) { var ready=false; scenario.onActivity { ready=predicate(it) }; ready }
    }
    private fun choose(file: String) {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://dev.cartridge.fixture/$file"))))
            ui.tool("choose-game").performClick(); ui.waitForIdle()
        } finally { Intents.release() }
    }
    private fun color(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        val future=CompletableFuture<Int>()
        scenario.onActivity { activity ->
            val view=checkNotNull(activity.engine.surface.value);val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            val x=view.width/2;val y=view.height/2
            PixelCopy.request(view,Rect(x-4,y-4,x+4,y+4),bitmap,{ result ->
                future.complete(if(result==PixelCopy.SUCCESS) bitmap.getPixel(4,4) else 0);bitmap.recycle()
            },Handler(Looper.getMainLooper()))
        }
        return future.get(3,TimeUnit.SECONDS)
    }
    private fun press(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.waitForIdle()
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B)) };Thread.sleep(150)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_B)) };Thread.sleep(150)
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick();await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Paused }
    }
    private fun resume(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-0").performClick();await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Running }
    }
    private fun eject(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-3").performClick();await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen==CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    private fun runGame(scenario: ActivityScenario<DebugCollectionActivity>) {
        var id="";scenario.onActivity { id=it.coordinator.state.value.selectedGame!!.id;assertEquals(GameSystem.PS1,it.coordinator.state.value.selectedGame!!.system);assertEquals(Media.entries.size * CollectionLayout.POOL,it.collectionSurface!!.meshInstanceCount) }
        ui.tool("insert").performClick();await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Running }
        var before=0
        try { ui.waitUntil(8000) { before=color(scenario);Color.red(before)>Color.blue(before)+40 } }
        catch (failure: Exception) { android.util.Log.e("CartridgePs1","Diagnostic boot center=${Integer.toHexString(before)}");throw failure }
        press(scenario);var changed=0;ui.waitUntil(8000) { changed=color(scenario);Color.blue(changed)>Color.red(changed)+40 }
        pause(scenario)
        val saves=SaveStore(File(context.filesDir,"saves"));val ram=saves.read(id,"sram")!!
        assertEquals(128*1024,ram.size);assertEquals('M'.code,ram[0].toInt());assertEquals('C'.code,ram[1].toInt());assertEquals("The program must write through the emulated BIOS",1,ram[8192].toInt())
        // Exercise the public core memory ABI with an original marker in the test card's data area.
        val done=CompletableFuture<Boolean>()
        scenario.onActivity { activity -> activity.engine.surface.value!!.queueEvent {
            val path=File(context.applicationInfo.nativeLibraryDir,GameSystem.PS1.coreFile).path
            val card=SaveBridge.read(path);card[10000]=0x5a
            val audio=SaveBridge.audioFrames(path);android.util.Log.i("CartridgePs1","Non-silent core PCM frames=$audio")
            done.complete(SaveBridge.restore(path,card) && audio > 0)
        } };assertTrue(done.get(3,TimeUnit.SECONDS))
        ui.onNodeWithTag("menu-1").performClick();await(scenario) { !it.coordinator.state.value.busy }
        assertTrue(saves.read(id,"state")!!.size>1024);assertEquals(0x5a,saves.read(id,"sram")!![10000].toInt())
        resume(scenario);press(scenario);ui.waitUntil(8000) { color(scenario)==before };pause(scenario)
        ui.onNodeWithTag("menu-2").performClick();await(scenario) { !it.coordinator.state.value.busy };resume(scenario)
        ui.waitUntil(8000) { color(scenario)==changed };pause(scenario)
        scenario.onActivity { assertNull(it.engine.status.value.saveWarning);assertNotNull(it.engine.frozenFrame.value) }
        scenario.recreate();await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.coordinator.state.value.resumeSession != null }
        ui.tool("resume-session").performClick();await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Paused }
        scenario.onActivity { activity -> val frame=checkNotNull(activity.engine.frozenFrame.value);assertEquals(changed,frame.getPixel(frame.width/2,frame.height/2));assertNull(activity.engine.status.value.saveWarning) };eject(scenario)
        ui.tool("insert").performClick();await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Running };pause(scenario)
        assertEquals(0x5a,saves.read(id,"sram")!![10000].toInt());eject(scenario)
    }
    @Test fun psxExecutableAndIsoRenderAcceptControllerStatesAndMemoryCardAcrossEjection() {
        val name="m1-test-${UUID.randomUUID()}";val fixtures=File(context.filesDir,"m1-fixtures").apply { mkdirs() }
        for(ext in listOf("exe","iso","chd")) {
            val bytes=File(context.filesDir,"cartridge-probe.$ext").readBytes();UUID.randomUUID().toString().toByteArray().copyInto(bytes,if (ext=="chd") 4096 else 256)
            File(fixtures,"probe.$ext").writeBytes(bytes)
        }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context,DebugCollectionActivity::class.java).putExtra("testDatabase",name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                for(ext in listOf("exe","iso","chd")) { choose("probe.$ext");await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system==GameSystem.PS1 };runGame(scenario) }
                scenario.recreate();await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { assertEquals(3,it.coordinator.state.value.allGames.size);assertEquals(GameSystem.PS1,it.coordinator.state.value.selectedGame!!.system) }
            }
        } finally { DebugCollectionActivity.closeDatabases();context.deleteDatabase("$name.db") }
    }
    @Test fun cueFolderCopiesTracksPlaysAndRescansWithoutDuplicateTrackEntries() {
        val name="m1-test-${UUID.randomUUID()}";val tree=File(context.filesDir,"m1-tree").apply { mkdirs() }
        for(ext in listOf("cue","bin")) File(context.filesDir,"cartridge-probe.$ext").copyTo(File(tree,"probe.$ext"),true)
        val sbi=byteArrayOf(83,66,73,0,0,2,0x10,1)+ByteArray(10);File(tree,"probe.sbi").writeBytes(sbi)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context,DebugCollectionActivity::class.java).putExtra("testDatabase",name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture-tree/tree/ps1-root"))))
                    ui.tool("choose-folder").performClick()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system==GameSystem.PS1 }
                scenario.onActivity { activity -> val game=activity.coordinator.state.value.selectedGame!!;assertArrayEquals(sbi,File(GameFiles.resolve(File(context.filesDir,"roms"),game.id,game.system).parentFile,"game.sbi").readBytes()) }
                val label=File(context.filesDir,"m1-fixtures/red-label.png").apply { parentFile!!.mkdirs() }
                val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(210,37,37)) }
                label.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/red-label.png"))))
                    ui.tool("choose-label").performClick()
                } finally { Intents.release() }
                await(scenario) { it.coordinator.state.value.labelPreviewReady }
                // The renderer acknowledges submission, while SurfaceView presents independently.
                ui.waitForIdle()
                var pixel=0
                try {
                    ui.waitUntil(8000) {
                        val rendered=CompletableFuture<Int>()
                        scenario.onActivity { activity ->
                            val view=checkNotNull(activity.collectionSurface);val sample=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
                            // Sample the printed annulus, away from the genuine center hole.
                            val x=(view.width*activity.labelCentre()).toInt()+view.width/11;val y=view.height/2
                            PixelCopy.request(view,Rect(x-4,y-4,x+4,y+4),sample,{ status -> rendered.complete(if(status==PixelCopy.SUCCESS) sample.getPixel(4,4) else 0);sample.recycle() },Handler(Looper.getMainLooper()))
                        }
                        pixel=rendered.get(3,TimeUnit.SECONDS)
                        Color.red(pixel)>Color.blue(pixel)*2 && Color.red(pixel)>Color.green(pixel)*2
                    }
                } catch (failure: Exception) { throw AssertionError("Real disc label pixel=${Integer.toHexString(pixel)}",failure) }
                ui.onNodeWithTag("label-action-2").performClick();await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen==CollectionScreen.Library }
                runGame(scenario);ui.tool("rescan").performClick();await(scenario) { !it.coordinator.state.value.busy }
                scenario.onActivity { assertEquals(1,it.coordinator.state.value.allGames.size);assertNotNull(it.coordinator.state.value.selectedGame!!.label) }
                File(tree,"probe.bin").delete();ui.tool("rescan").performClick();await(scenario) { !it.coordinator.state.value.busy }
                scenario.onActivity { assertTrue(it.shell.lastScan!!.errors.any { error -> error.contains("missing",true) });assertEquals(1,it.coordinator.state.value.allGames.size) }
            }
        } finally { File(tree,"probe.cue").delete();File(tree,"probe.bin").delete();File(tree,"probe.sbi").delete();DebugCollectionActivity.closeDatabases();context.deleteDatabase("$name.db") }
    }
    @Test fun ps1BackupRevisionsRoundTripAndInvalidBiosPreservesThePriorFile() = runBlocking {
        // Storage format proof; synthetic payloads here are never passed to the emulator.
        val directory=File(context.cacheDir,"ps1-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val firstDb=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        val secondDb=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        val preferences=context.getSharedPreferences(directory.name,0)
        try {
            val id="d".repeat(64);val first=RoomCollectionRepository(context,firstDb);first.initialize();first.add(LibraryGame(id,"Original PS1","content://fixture/game.cue",system=GameSystem.PS1))
            val saves=SaveStore(File(directory,"saves"));val state=ManualState.encode(GameSystem.PS1,byteArrayOf(1,2,3));saves.write(id,"state",state);saves.write(id,"sram",ByteArray(128*1024).apply { this[0]=77;this[1]=67 })
            val sessions=SessionStore(File(directory,"session"));sessions.write(id,GameSystem.PS1,byteArrayOf(4,5,6))
            val source=BackupStore(first,LabelStore(File(directory,"labels")),saves,sessions,File(directory,"source-stage"))
            val output=ByteArrayOutputStream();source.export(output,BackupPreferences.capture(preferences))
            val second=RoomCollectionRepository(context,secondDb);second.initialize()
            val targetSaves=SaveStore(File(directory,"target-saves"));val targetSessions=SessionStore(File(directory,"target-session"))
            val target=BackupStore(second,LabelStore(File(directory,"target-labels")),targetSaves,targetSessions,File(directory,"target-stage"))
            target.inspect(output.toByteArray().inputStream()).use { review ->
                assertEquals(GameSystem.PS1,review.session!!.system);assertEquals(GameSystem.PS1.coreRevision,review.blobs.single { it.kind=="state" }.core);target.merge(review)
            }
            assertArrayEquals(state,targetSaves.read(id,"state"));assertArrayEquals(byteArrayOf(4,5,6),targetSessions.read(targetSessions.available()!!));assertEquals(GameSystem.PS1,second.initialize().slots.filterNotNull().single().system)
            val entries=linkedMapOf<String,ByteArray>();ZipInputStream(output.toByteArray().inputStream()).use { zip -> while(true) { val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes();zip.closeEntry() } }
            val root=JSONObject(String(entries.getValue("backup.json")));val blobs=root.getJSONArray("blobs")
            repeat(blobs.length()) { val blob=blobs.getJSONObject(it);if(blob.getString("kind")=="state") blob.put("core",GameSystem.GBA.coreRevision) }
            entries["backup.json"]=root.toString().toByteArray();val bad=ByteArrayOutputStream()
            ZipOutputStream(bad).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
            try { target.inspect(bad.toByteArray().inputStream()).close();fail("Wrong-console state revision accepted") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(state,targetSaves.read(id,"state"))
            val bios=File(directory,"bios").apply { mkdirs() };File(bios,"scph5501.bin").writeBytes(byteArrayOf(9,8,7))
            File(context.filesDir,"m1-fixtures/invalid.png").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(0)) }
            try { FirmwareStore(bios).import(context.contentResolver,Uri.parse("content://dev.cartridge.fixture/invalid.png"),dev.cartridge.domain.Core.Pcsx);fail("Invalid firmware accepted") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(byteArrayOf(9,8,7),File(bios,"scph5501.bin").readBytes())
        } finally { firstDb.close();secondDb.close();preferences.edit().clear().commit();directory.deleteRecursively() }
    }
}
