package dev.cartridge

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SurfaceHandoffTest {
    @Test fun threeRealFilamentToMgbaCyclesWithSaveAndLoad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The optional fixture is pushed by the documented device command, never bundled in the APK.
        val rom = File(context.filesDir, "cartridge-probe.gba")
        assertTrue("Generate and push the original probe ROM first; see docs/TESTING.md", rom.isFile)
        val intent = Intent(context, SurfaceProbeActivity::class.java).putExtra("rom", rom.path)
        ActivityScenario.launch<SurfaceProbeActivity>(intent).use { scenario ->
            val deadline = System.nanoTime() + 60_000_000_000L
            var completed = 0
            var failure: String? = null
            while (completed < 3 && failure == null && System.nanoTime() < deadline) {
                scenario.onActivity { completed = it.completedCycles; failure = it.failure }
                Thread.sleep(100)
            }
            assertNull(failure, failure)
            assertEquals("Surface handoff did not complete three cycles", 3, completed)
        }
    }
}
