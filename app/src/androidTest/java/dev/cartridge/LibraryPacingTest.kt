package dev.cartridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.library.Shelf
import dev.cartridge.storage.LabelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library as the owner browses it: the production activity, a mixed shelf, a decoded image label on
 * every game, and the D-pad held at the console's own repeat rate. Presentation times are the display's,
 * read from SurfaceFlinger, not the renderer's own clock. Original artwork only.
 */
class LibraryPacingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun percentile(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) {
        try { ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready } }
        catch (failure: Throwable) {
            scenario.onActivity { val state = it.coordinator.state.value
                Log.e("CartridgePacing", "loaded=${state.loaded} ready=${state.rendererReady} busy=${state.busy} games=${state.allGames.size} message=${state.message} renderer=${it.collectionSurface?.diagnosticState}") }
            throw failure
        }
    }
    /** A photographic-sized label that costs a real decode: gradients and shapes, different for every game. */
    private fun artwork(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.HSVToColor(floatArrayOf(index * 37f % 360, .45f, .55f)))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(40) { n -> paint.color = Color.HSVToColor(floatArrayOf((index * 37f + n * 11) % 360, .6f, .4f + n % 5 * .1f)); drawCircle(n * 53f % 1200, n * 97f % 640, 30f + n * 3, paint) }
            paint.color = Color.WHITE; paint.textSize = 160f; drawText("No. $index", 80f, 380f, paint)
        }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it); bitmap.recycle(); it.toByteArray() }
    }

    @Test fun mixedShelfWithDecodedLabelsPresentsAtTheDeviceRefreshRateWhileBrowsing() {
        val name = "m1-test-${UUID.randomUUID()}"
        // `-e games 800` measures a large library; the first $ARTWORK entries carry image labels either way.
        val games = InstrumentationRegistry.getArguments().getString("games")?.toInt() ?: ARTWORK
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        val systems = listOf(GameSystem.GBA, GameSystem.GB, GameSystem.PS1, GameSystem.GBA, GameSystem.GBC)
        val labels = LabelStore(File(context.filesDir, "m1-test-labels"))
        runBlocking {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            repeat(games) { index ->
                val id = (index + 1).toString(16).padStart(64, '0')
                repository.add(LibraryGame(id, "Original pacing fixture $index", "content://fixture", system = systems[index % systems.size]))
                if (index < ARTWORK) repository.setLabel(id, labels.importStream("Original pacing fixture") { artwork(index).inputStream() })
            }
            repository.select(0)
        }
        database.close()
        // Seeding a large library can outlast the screen timeout; an activity launched asleep never draws.
        shell("input keyevent KEYCODE_WAKEUP")
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.All) }
                await(scenario) { it.coordinator.state.value.shelf == Shelf.All && it.coordinator.state.value.selection.count == games }
                val layer = shell("dumpsys SurfaceFlinger --list").lineSequence().single {
                    it.startsWith("SurfaceView[${context.packageName}/dev.cartridge.DebugCollectionActivity](BLAST)") }
                var refresh = 0L
                fun presents(since: Long, into: TreeSet<Long>) {
                    val rows = shell("dumpsys SurfaceFlinger --latency $layer").lines()
                    refresh = rows.first().trim().toLong()
                    rows.drop(1).forEach { line ->
                        val columns = line.trim().split(Regex("\\s+"))
                        if (columns.size == 3) columns[1].toLongOrNull()?.let { if (it > since && it != Long.MAX_VALUE) into.add(it) }
                    }
                }
                for (layout in CollectionLayout.entries) {
                    scenario.onActivity { it.coordinator.settings(layout = layout); it.coordinator.navigate(-999) }
                    await(scenario) { it.collectionSurface?.stagedUnbound == 0 }
                    Thread.sleep(600)
                    for ((phase, hold) in listOf("rest" to false, "held D-pad" to true)) {
                        val late = AtomicInteger(); val cpu = mutableListOf<Double>()
                        scenario.onActivity { activity ->
                            activity.collectionSurface!!.frameObserver = { _, nanos, presented ->
                                if (presented) { synchronized(cpu) { cpu.add(nanos / 1_000_000.0) }; if (activity.collectionSurface!!.unlabelledOnStage > 0) late.incrementAndGet() } }
                        }
                        val start = System.nanoTime(); val seen = TreeSet<Long>()
                        var direction = 1; var nextStep = start; var nextRead = start + 900_000_000L
                        while (System.nanoTime() - start < SECONDS * 1_000_000_000L) {
                            val now = System.nanoTime()
                            if (hold && now >= nextStep) {
                                // The console's held repeat: one step every 110 ms, turning round at the ends.
                                scenario.onActivity {
                                    val state = it.coordinator.state.value
                                    val step = if (layout == CollectionLayout.Grid) 3 else 1
                                    if (state.selection.index + direction * step !in 0 until state.selection.count) direction = -direction
                                    it.coordinator.navigate(direction * step)
                                }
                                nextStep += 110_000_000L
                            }
                            if (now >= nextRead) { presents(start, seen); nextRead += 900_000_000L }
                            Thread.sleep(8)
                        }
                        presents(start, seen)
                        scenario.onActivity { it.collectionSurface!!.frameObserver = null }
                        val intervals = seen.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
                        assertTrue("$layout $phase: no presentation samples", intervals.size > 60)
                        val period = refresh / 1_000_000.0
                        val fps = 1000.0 * intervals.size / intervals.sum()
                        val copy = synchronized(cpu) { cpu.toList() }
                        Log.i("CartridgePacing", "library of $games, $layout $phase: fps=${"%.1f".format(fps)}, frames=${seen.size}, median=${"%.2f".format(percentile(intervals, .5))}ms, " +
                            "p95=${"%.2f".format(percentile(intervals, .95))}ms, p99=${"%.2f".format(percentile(intervals, .99))}ms, gaps=${intervals.count { it > period * 1.5 }}, " +
                            "cpuP95=${"%.2f".format(percentile(copy, .95))}ms, lateLabelFrames=${late.get()}")
                        assertTrue("$layout $phase p95 exceeds 1.5 refresh periods: ${percentile(intervals, .95)}ms", percentile(intervals, .95) <= period * 1.5)
                    }
                }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    private companion object { const val ARTWORK = 40; const val SECONDS = 6 }
}
