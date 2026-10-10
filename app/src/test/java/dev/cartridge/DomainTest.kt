package dev.cartridge

import dev.cartridge.domain.*
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DomainTest {
    @Test fun onlyAFlatWhiteOrBlackSampleCountsAsADisplayThatIsNotOnYet() {
        fun flat(value: Int, noise: Int = 0) = IntArray(576) { 0xFF000000.toInt() or ((value + it % (noise + 1)) * 0x010101) }
        assertTrue(blankDisplay(flat(255))); assertTrue(blankDisplay(flat(0)))
        // Filtering and dithering leave a little variation in a blank frame.
        assertTrue(blankDisplay(flat(236, noise = 12))); assertTrue(blankDisplay(flat(2, noise = 14)))
        assertTrue(blankDisplay(IntArray(0)))
        // One flat colour that is neither is the game's own picture, as is anything with detail.
        assertFalse(blankDisplay(flat(128))); assertFalse(blankDisplay(IntArray(576) { 0xFF00C800.toInt() }))
        assertFalse(blankDisplay(flat(255).also { it[300] = 0xFF202020.toInt() }))
        assertFalse(blankDisplay(IntArray(576) { if (it % 2 == 0) -1 else 0xFF000000.toInt() }))
    }
    @Test fun lifecycleRejectsDuplicateAndOutOfOrderOperations() {
        val machine = SessionMachine()
        assertThrows(IllegalStateException::class.java) { machine.transition(SessionState.Running) }
        machine.transition(SessionState.Preparing)
        assertThrows(IllegalStateException::class.java) { machine.transition(SessionState.Preparing) }
        machine.transition(SessionState.Running)
        machine.transition(SessionState.Paused)
        machine.transition(SessionState.Running)
        machine.transition(SessionState.Stopping)
        machine.transition(SessionState.Idle)
        assertEquals(SessionState.Idle, machine.state)
    }
    @Test fun failedPreparationCanBeCleanedUpAndRetried() {
        val machine = SessionMachine()
        machine.transition(SessionState.Preparing)
        machine.transition(SessionState.Error)
        machine.transition(SessionState.Stopping)
        machine.transition(SessionState.Idle)
        machine.transition(SessionState.Preparing)
        assertEquals(SessionState.Preparing, machine.state)
    }
    @Test fun selectionClampsAndRapidNavigationRetargetsSpring() {
        assertEquals(0, Selection().move(-1).index)
        assertEquals(2, Selection().move(100).index)
        val spring = FocusSpring()
        repeat(6) { spring.step(2f, 1f / 60) }
        repeat(180) { spring.step(0f, 1f / 60) }
        assertEquals(0f, spring.position, 0.001f)
    }
    @Test fun heldAxisRepeatsWithoutNewMotionEventsAndResets() {
        val axis = AxisRepeater()
        assertEquals(0, axis.poll(0.2f, 0))
        assertEquals(1, axis.poll(0.8f, 1))
        assertEquals(0, axis.poll(0.8f, 299))
        assertEquals(1, axis.poll(0.8f, 301))
        assertEquals(-1, axis.poll(-0.8f, 302))
        assertEquals(0, axis.poll(0f, 303))
        assertEquals(1, axis.poll(0.8f, 304))
    }
    @Test fun saveFilesAreIsolatedAndReplacedWithoutPartialBytes() {
        val directory = Files.createTempDirectory("cartridge-test").toFile()
        try {
            val store = SaveStore(directory)
            val id = "a".repeat(64)
            store.write(id, "state", byteArrayOf(1, 2, 3))
            store.write(id, "state", byteArrayOf(4, 5))
            assertArrayEquals(byteArrayOf(4, 5), store.read(id, "state"))
            assertNull(store.read("b".repeat(64), "state"))
            assertThrows(IllegalArgumentException::class.java) { store.write("../escape", "state", byteArrayOf()) }
            assertFalse(FileCheck.hasTemporary(directory))
        } finally { directory.deleteRecursively() }
    }
    private object FileCheck {
        fun hasTemporary(directory: java.io.File) = directory.listFiles()!!.any { it.extension == "tmp" }
    }
}
