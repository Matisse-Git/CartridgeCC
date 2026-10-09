package dev.cartridge

import dev.cartridge.domain.*
import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DomainTest {
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
