package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RenderRefinementTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun asymmetricLabelKeepsItsOrientationAndReducedMotionSurvivesReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir,"m1-fixtures").apply { mkdirs() }
        val probe = File(context.filesDir,"cartridge-probe.gba").readBytes()
        File(root,"probe-one.gba").writeBytes(probe)
        File(root,"probe-two.gba").writeBytes(probe+UUID.randomUUID().toString().toByteArray())
        val bitmap = Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        // Mid-tone print swatches stay outside the filmic mapper's highlight desaturation.
        for (y in 0 until 256) for (x in 0 until 512) bitmap.setPixel(x,y,
            if(y<128) { if(x<256) Color.rgb(160,0,0) else Color.rgb(0,160,0) }
            else { if(x<256) Color.rgb(0,0,160) else Color.rgb(160,160,0) })
        File(root,"red-label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        ActivityScenario.launch<DebugCollectionActivity>(Intent(context,DebugCollectionActivity::class.java)
            .putExtra("testDatabase","m1-test-${UUID.randomUUID()}")).use { scenario ->
            fun await(condition: (DebugCollectionActivity)->Boolean) = ui.waitUntil(30_000) {
                var result = false; scenario.onActivity { result = condition(it) }; result }
            fun choose(tag: String, filename: String) {
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/$filename"))))
                    ui.tool(tag).performClick()
                } finally { Intents.release() }
            }
            await { it.coordinator.state.value.rendererReady }
            ui.onNodeWithTag("choose-game").assertDoesNotExist()
            choose("choose-game","probe-two.gba")
            await { !it.coordinator.state.value.busy }
            choose("choose-game","probe-one.gba")
            await { !it.coordinator.state.value.busy }
            choose("choose-label","red-label.png")
            await { it.coordinator.state.value.labelPreviewReady }
            ui.onNodeWithTag("label-action-2").performClick()
            await { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.label != null }
            ui.waitForIdle() // Flush Compose updates before sampling its independently clocked SurfaceView.
            Thread.sleep(500)
            val colors = CompletableFuture<List<Int>>()
            scenario.onActivity {
                val view = checkNotNull(it.collectionSurface)
                val pixels = Bitmap.createBitmap(192,108,Bitmap.Config.ARGB_8888)
                PixelCopy.request(view,Rect(0,0,view.width,view.height),pixels, { status ->
                    if(status==PixelCopy.SUCCESS) colors.complete(listOf(pixels.getPixel(86,48),pixels.getPixel(106,48),pixels.getPixel(86,59),pixels.getPixel(106,59)))
                    else colors.completeExceptionally(IllegalStateException("GPU copy failed: $status"))
                    pixels.recycle()
                },Handler(Looper.getMainLooper()))
            }
            val actual = colors.get(3,TimeUnit.SECONDS)
            Log.i("CartridgePacing", "Label quadrants: ${actual.map { Integer.toHexString(it) }}")
            assertTrue("Top-left must be red", Color.red(actual[0]) > Color.green(actual[0])*2)
            assertTrue("Top-right must be green", Color.green(actual[1]) > Color.red(actual[1])*2)
            assertTrue("Bottom-left must be blue", Color.blue(actual[2]) > Color.red(actual[2])*2)
            assertTrue("Bottom-right must be yellow", Color.red(actual[3]) > Color.blue(actual[3])*2 && Color.green(actual[3]) > Color.blue(actual[3])*2)
            scenario.onActivity { it.coordinator.settings(reducedMotion=false, alternateShortcut=false) }
            ui.waitForIdle()
            ui.tool("motion-setting").performClick()
            ui.tool("shortcut-setting").performClick()
            scenario.onActivity { assertTrue(it.coordinator.state.value.reducedMotion); assertTrue(it.coordinator.state.value.alternateShortcut) }
            scenario.onActivity { it.coordinator.navigate(-1) }
            await { it.collectionSurface?.focusPosition == it.coordinator.state.value.selection.index.toFloat() }
            scenario.recreate()
            await { it.coordinator.state.value.rendererReady }
            scenario.onActivity { assertTrue(it.coordinator.state.value.reducedMotion); assertTrue(it.coordinator.state.value.alternateShortcut) }
            ui.tool("motion-setting").performClick() // Restore the isolated debug Activity's preference.
            ui.tool("shortcut-setting").performClick()
        }
        DebugCollectionActivity.closeDatabases()
    }
}
