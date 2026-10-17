package dev.cartridge

import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.ManualState
import dev.cartridge.storage.SaveStore
import dev.cartridge.storage.SlotKind
import dev.cartridge.storage.StateSlots
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StateSlotsTest {
    private val id = "c".repeat(64)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private fun folder(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("cartridge-slots-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun theQuickSaveIsTheExistingStateFileAndKeepsTheOneItReplaced() = folder { saves ->
        val slots = StateSlots(saves)
        // A state written by an earlier build, with no folder beside it, is the quick save as it is.
        SaveStore(saves).write(id, "state", byteArrayOf(1))
        File(saves, "$id.state").setLastModified(1_000_000)
        assertEquals(listOf(SlotKind.Quick), slots.list(id, GameSystem.GBA).map { it.kind })
        assertNull(slots.list(id, GameSystem.GBA).single().thumbnail)

        slots.writeQuick(id, byteArrayOf(2), jpeg)
        assertArrayEquals(byteArrayOf(2), SaveStore(saves).read(id, "state"))
        val listed = slots.list(id, GameSystem.GBA)
        assertEquals(listOf(SlotKind.Quick, SlotKind.Previous), listed.map { it.kind })
        assertArrayEquals(byteArrayOf(1), slots.read(id, GameSystem.GBA, StateSlots.PREVIOUS))
        assertEquals(1_000_000, listed[1].savedAt)
        assertNotNull(listed[0].thumbnail); assertNull(listed[1].thumbnail)

        // A third quick save moves the second, picture and all, and drops the first.
        slots.writeQuick(id, byteArrayOf(3), null)
        val again = slots.list(id, GameSystem.GBA)
        assertArrayEquals(byteArrayOf(2), slots.read(id, GameSystem.GBA, StateSlots.PREVIOUS))
        assertNull("no picture of another moment", again[0].thumbnail)
        assertNotNull(again[1].thumbnail)
    }

    @Test fun savedStatesAreListedNewestFirstAndOverwriteReplacesOnlyAfterTheNewOneIsPublished() = folder { saves ->
        val slots = StateSlots(saves)
        val first = slots.write(id, GameSystem.GBA, byteArrayOf(1), jpeg, now = 100)
        val second = slots.write(id, GameSystem.GBA, byteArrayOf(2), null, now = 200)
        assertEquals(listOf(second, first), slots.list(id, GameSystem.GBA).map { it.name })
        assertArrayEquals(byteArrayOf(1), slots.read(id, GameSystem.GBA, first))

        val replaced = slots.write(id, GameSystem.GBA, byteArrayOf(9), jpeg, now = 300, replacing = first)
        assertEquals(listOf(replaced, second), slots.list(id, GameSystem.GBA).map { it.name })
        assertFalse(File(saves, "$id.slots/$first.state").exists())
        assertArrayEquals(byteArrayOf(9), slots.read(id, GameSystem.GBA, replaced))

        slots.delete(id, second)
        assertEquals(listOf(replaced), slots.list(id, GameSystem.GBA).map { it.name })
        assertThrows(IllegalArgumentException::class.java) { slots.delete(id, StateSlots.QUICK) }
        assertThrows(IllegalArgumentException::class.java) { slots.delete(id, StateSlots.PREVIOUS) }
    }

    @Test fun anInterruptedWriteIsNeverListedAndADamagedStateIsKeptAndRefused() = folder { saves ->
        val slots = StateSlots(saves)
        val name = slots.write(id, GameSystem.GBA, byteArrayOf(1, 2, 3), null, now = 100)
        // A state whose record was never written.
        File(saves, "$id.slots/00000000-0000-0000-0000-000000000000.state").writeBytes(byteArrayOf(7))
        assertEquals(listOf(name), slots.list(id, GameSystem.GBA).map { it.name })
        val state = File(saves, "$id.slots/$name.state").apply { writeBytes(byteArrayOf(1, 2, 4)) }
        assertThrows(IllegalStateException::class.java) { slots.read(id, GameSystem.GBA, name) }
        assertArrayEquals(byteArrayOf(1, 2, 4), state.readBytes())
    }

    @Test fun aStateFromAnotherCoreBuildIsShownButNeverRead() = folder { saves ->
        val slots = StateSlots(saves)
        val name = slots.write(id, GameSystem.PS1, ManualState.encode(GameSystem.PS1, byteArrayOf(5)), null, now = 100)
        val record = File(saves, "$id.slots/$name.properties")
        record.writeText(record.readText().replace(GameSystem.PS1.coreRevision, "an-older-build"))
        assertFalse(slots.list(id, GameSystem.PS1).single().compatible)
        assertThrows(IllegalStateException::class.java) { slots.read(id, GameSystem.PS1, name) }
        // The quick save says so by its own bytes.
        File(saves, "$id.state").writeBytes("Cartridge state 1\nan-older-build\n".toByteArray() + byteArrayOf(5))
        assertFalse(slots.list(id, GameSystem.PS1).first { it.kind == SlotKind.Quick }.compatible)
        SaveStore(saves).write(id, "state", ManualState.encode(GameSystem.PS1, byteArrayOf(5)))
        assertTrue(slots.list(id, GameSystem.PS1).first { it.kind == SlotKind.Quick }.compatible)
    }

    @Test fun theLatestStateIsTheNewestOfTheQuickSaveAndTheListButNeverThePreviousOne() = folder { saves ->
        val slots = StateSlots(saves)
        assertNull(slots.latest(id, GameSystem.GBA))
        val saved = slots.write(id, GameSystem.GBA, byteArrayOf(1), null, now = 5_000)
        slots.writeQuick(id, byteArrayOf(2), null); File(saves, "$id.state").setLastModified(4_000)
        assertEquals(saved, slots.latest(id, GameSystem.GBA)!!.name)
        slots.writeQuick(id, byteArrayOf(3), null); File(saves, "$id.state").setLastModified(6_000)
        File(saves, "$id.slots/previous.state").setLastModified(9_000)
        assertEquals(StateSlots.QUICK, slots.latest(id, GameSystem.GBA)!!.name)
    }

    @Test fun aBackupAddsOnlyWhatIsMissingAndKeepsItsNames() = folder { saves ->
        val source = StateSlots(File(saves, "source"))
        SaveStore(File(saves, "source")).write(id, "state", byteArrayOf(1))
        source.writeQuick(id, byteArrayOf(2), jpeg)
        val name = source.write(id, GameSystem.GBA, byteArrayOf(3), jpeg, now = 700)
        val entries = source.backupEntries(id, GameSystem.GBA)
        assertEquals(setOf(StateSlots.QUICK, StateSlots.PREVIOUS, name), entries.map { it.name }.toSet())
        assertNull(entries.first { it.name == StateSlots.QUICK }.state)

        val target = StateSlots(File(saves, "target"))
        SaveStore(File(saves, "target")).write(id, "state", byteArrayOf(8))
        entries.forEach { target.importMissing(id, GameSystem.GBA, it.name, it.state?.readBytes(), it.savedAt, it.thumbnail?.readBytes()) }
        val restored = target.list(id, GameSystem.GBA)
        assertEquals(listOf(SlotKind.Quick, SlotKind.Previous, SlotKind.Saved), restored.map { it.kind })
        assertEquals(name, restored.last().name); assertEquals(700, restored.last().savedAt)
        assertArrayEquals(byteArrayOf(8), SaveStore(File(saves, "target")).read(id, "state"))
        // The first quick save had no picture, so the one it became has none either.
        assertEquals(listOf(true, false, true), restored.map { it.thumbnail != null })
        // A second merge of the same backup changes nothing.
        assertFalse(entries.any { target.importMissing(id, GameSystem.GBA, it.name, it.state?.readBytes(), it.savedAt, it.thumbnail?.readBytes()) })
        assertEquals(3, target.list(id, GameSystem.GBA).size)
    }
}
