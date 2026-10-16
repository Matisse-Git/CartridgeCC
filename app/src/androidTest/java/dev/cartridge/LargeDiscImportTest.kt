package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.GameFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * A DVD-sized disc on the device. The image is an original, empty one, longer than a 32-bit count of bytes: it is
 * told from a PlayStation disc by its size alone, copied and hashed whole into private storage, and listed. It
 * holds no game, so nothing is started; the test removes its own copy.
 */
class LargeDiscImportTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, timeout: Long = 30_000, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(timeout) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }

    @Test fun aDvdImageLongerThanTwoGigabytesIsCopiedWholeAndListedUnderItsConsole() {
        val name = "m1-test-${UUID.randomUUID()}"
        val fixtures = File(context.filesDir, "m1-fixtures").apply { mkdirs() }
        val image = File(fixtures, "probe.iso")
        val length = 2_200L * 1024 * 1024 + 2048 * 7
        // Sparse: the fixture takes no room. An identity of its own at the start, and a volume descriptor's place left empty.
        RandomAccessFile(image, "rw").use { file -> file.setLength(0); file.setLength(length); file.write(UUID.randomUUID().toString().toByteArray()) }
        var bundle: File? = null
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                val started = System.nanoTime()
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.iso"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario, 300_000) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame != null }
                scenario.onActivity { activity ->
                    val game = activity.coordinator.state.value.selectedGame!!
                    assertEquals(GameSystem.PS2, game.system)
                    val copy = GameFiles.resolve(File(context.filesDir, "roms"), game.id, GameSystem.PS2)
                    bundle = copy.parentFile
                    assertEquals("game.iso", copy.name); assertEquals(length, copy.length())
                }
                android.util.Log.i("CartridgeDiscs", "Imported ${length / (1024 * 1024)} MiB as PlayStation 2 in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } finally {
            DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db")
            image.delete(); bundle?.takeIf { it.name.endsWith(".ps2") }?.deleteRecursively()
        }
    }
}
