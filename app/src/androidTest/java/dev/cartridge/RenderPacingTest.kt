package dev.cartridge

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.TreeSet

class RenderPacingTest {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun percentile(values: List<Double>, p: Double) = values.sorted()[((values.size-1)*p).toInt()]
    @Test fun originalStudioCarouselAndInsertionPresentAtTheDeviceRefreshRate() {
        ActivityScenario.launch(CollectionProfileActivity::class.java).use { scenario ->
            var start = 0L
            val deadline = System.nanoTime()+55_000_000_000L
            while (start == 0L && System.nanoTime() < deadline) {
                scenario.onActivity { assertNull(it.failure); start = it.startedNanos }
                Thread.sleep(50)
            }
            assertTrue("Renderer did not start", start > 0)
            val layer = shell("dumpsys SurfaceFlinger --list").lineSequence().single {
                it.startsWith("SurfaceView[dev.cartridge/dev.cartridge.CollectionProfileActivity](BLAST)") }
            val presents = TreeSet<Long>()
            var refresh = 0L
            var finished = false
            while (!finished && System.nanoTime() < deadline) {
                // UiAutomation executes argv directly; unlike adb shell, quotes become literal.
                val rows = shell("dumpsys SurfaceFlinger --latency $layer").lines()
                refresh = rows.first().trim().toLong()
                rows.drop(1).forEach { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size == 3) columns[1].toLongOrNull()?.let { timestamp ->
                        if (timestamp > start && timestamp != Long.MAX_VALUE) presents.add(timestamp)
                    }
                }
                scenario.onActivity { assertNull(it.failure); finished = it.finished }
                if (!finished) Thread.sleep(1_000)
            }
            assertTrue("Profile did not finish", finished)
            assertTrue(refresh > 0)
            for ((name, range) in listOf("rest" to (500L..9_500L), "rapid navigation" to (10_500L..29_500L), "insertion/ejection" to (30_500L..35_500L))) {
                val phase = presents.filter { (it-start)/1_000_000 in range }
                assertTrue("No real presentation samples for $name", phase.size > 100)
                val intervals = phase.zipWithNext { a,b -> (b-a)/1_000_000.0 }
                val p95 = percentile(intervals,.95)
                Log.i("CartridgePacing", "$name frames=${phase.size}, median=${percentile(intervals,.5)}ms, p95=${p95}ms, p99=${percentile(intervals,.99)}ms, gaps=${intervals.count { it > refresh/1_000_000.0*1.5 }}")
                assertTrue("$name p95 exceeds 1.5 refresh periods: ${p95}ms", p95 <= refresh/1_000_000.0*1.5)
            }
            scenario.onActivity {
                val cpu = it.cpuNanos.map { ns -> ns/1_000_000.0 }
                Log.i("CartridgePacing", "CPU submission p95=${percentile(cpu,.95)}ms, submitted=${it.submitted}/${it.callbacks}")
            }
        }
    }
}
