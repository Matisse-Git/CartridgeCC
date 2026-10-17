package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.SessionState
import dev.cartridge.session.CollectionScreen
import dev.cartridge.storage.SessionStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SessionRecoveryTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var loggedPause = false
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        try { ui.waitUntil(30_000) { var done = false; scenario.onActivity { done = check(it) }; done } }
        catch (failure: Throwable) {
            scenario.onActivity { android.util.Log.e("CartridgeRecovery", "engine=${it.engine.status.value} screen=${it.coordinator.state.value.screen} busy=${it.coordinator.state.value.busy} resume=${it.coordinator.state.value.resumeSession} message=${it.coordinator.state.value.message}") }
            throw failure
        }
    }
    private fun pixels(scenario: ActivityScenario<DebugCollectionActivity>): Int {
        ui.waitForIdle(); val result = CompletableFuture<Int>()
        scenario.onActivity { activity ->
            if (activity.engine.status.value.state == SessionState.Paused) {
                val frame = activity.engine.frozenFrame.value
                if (frame == null) { result.complete(0); return@onActivity }
                val colors=IntArray(64); frame.getPixels(colors,0,8,frame.width/2-4,frame.height/2-4,8,8)
                result.complete(if(colors.toSet().size>1) colors.contentHashCode() else 0); return@onActivity
            }
            val view = checkNotNull(activity.engine.surface.value); val x=view.width/2; val y=view.height/2
            val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, Rect(x-4,y-4,x+4,y+4), bitmap, { status ->
                val colors = IntArray(64); if(status==PixelCopy.SUCCESS) bitmap.getPixels(colors,0,8,0,0,8,8)
                if (!loggedPause && activity.engine.status.value.state == SessionState.Paused) {
                    loggedPause=true; android.util.Log.i("CartridgeRecovery", "paused copy status=$status size=${view.width}x${view.height} colors=${colors.toSet()}")
                }
                result.complete(if(status==PixelCopy.SUCCESS && colors.toSet().size>1) colors.contentHashCode() else 0); bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3,TimeUnit.SECONDS)
    }
    @Test fun realExecutionResumesPausedAfterBackgroundAndColdLaunchAndRejectsCorruptCheckpoint() {
        val name = "m1-test-${UUID.randomUUID()}"
        val intent = Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)
        val fixture = File(context.filesDir,"m1-fixtures/probe.gb").apply { parentFile!!.mkdirs() }
        val bytes = File(context.filesDir,"cartridge-probe.gb").readBytes()
        UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes,0x134); fixture.writeBytes(bytes)
        var changed=0; var id=""
        try {
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://$FIXTURE/probe.gb"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame!=null }
                scenario.onActivity { id=it.coordinator.state.value.selectedGame!!.id }
                ui.tool("insert").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Running }
                var before=0; ui.waitUntil(8000) { before=pixels(scenario); before!=0 }
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A)) }; Thread.sleep(120)
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_A)) }
                ui.waitUntil(8000) { changed=pixels(scenario); changed!=0 && changed!=before }
                scenario.moveToState(Lifecycle.State.CREATED)
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Idle && it.coordinator.state.value.resumeSession?.id==id }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Paused }
                android.util.Log.i("CartridgeRecovery", "wake expected=$changed actual=${pixels(scenario)} initial=$before saved=${dev.cartridge.storage.SaveStore(File(context.filesDir, "saves")).read(id,"sram")!!.take(2)}")
                ui.waitUntil(8000) { pixels(scenario)==changed }
                android.util.Log.i("CartridgeRecovery", "resume after wake")
                ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Running }
                try {
                    shell("input keyevent KEYCODE_SLEEP")
                    await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Idle }
                    assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                } finally { shell("input keyevent KEYCODE_WAKEUP") }
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Paused }
                ui.waitUntil(8000) { pixels(scenario)==changed }
                scenario.onActivity { it.coordinator.background() }
                await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Idle && it.coordinator.state.value.resumeSession!=null }
            }
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.coordinator.state.value.resumeSession?.id==id }
                scenario.onActivity { assertEquals(SessionState.Idle,it.engine.status.value.state); assertEquals(CollectionScreen.Library,it.coordinator.state.value.screen) }
                ui.tool("resume-session").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Paused }
                ui.waitUntil(8000) { pixels(scenario)==changed }
                scenario.onActivity { it.coordinator.background() }; await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state==SessionState.Idle }
            }
            val directory=File(context.filesDir,"$name-session")
            val store=SessionStore(directory); val saved=store.available()!!
            assertEquals(id,saved.id); assertTrue(store.read(saved).isNotEmpty())
            directory.listFiles()!!.single { it.extension=="state" }.let { file -> file.writeBytes(ByteArray(file.length().toInt())) }
            ActivityScenario.launch<DebugCollectionActivity>(intent).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady && it.coordinator.state.value.resumeSession!=null }
                ui.tool("resume-session").performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.message?.contains("integrity") == true }
                scenario.onActivity { assertEquals(SessionState.Idle,it.engine.status.value.state); assertEquals(id,it.coordinator.state.value.selectedGame!!.id) }
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-gameplay").performClick(); ui.onNodeWithTag("settings-discard").performScrollTo().performClick()
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.resumeSession==null }
                assertNull(store.available())
            }
        } finally { shell("input keyevent KEYCODE_WAKEUP"); DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
