package dev.cartridge

import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.CueSheet
import dev.cartridge.storage.GameFiles
import dev.cartridge.storage.SessionStore
import dev.cartridge.storage.ManualState
import dev.cartridge.storage.SbiFile
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Ps1StorageTest {
    @Test fun cuePathsAreConfinedAndNormalizedWhileTrackLayoutIsRetained() {
        val cue = CueSheet.parse("FILE \"Example track.bin\" BINARY\r\n TRACK 01 MODE2/2352\r\n INDEX 01 00:00:00\r\n TRACK 02 AUDIO\r\n INDEX 01 10:00:00\r\n")
        assertEquals(listOf("Example track.bin"), cue.files)
        assertTrue(cue.text.contains("FILE \"track-00.bin\" BINARY")); assertTrue(cue.text.contains("TRACK 02 AUDIO"))
        listOf("../escape.bin", "C:\\escape.bin", "dir/escape.bin", "..", "bad\nname.bin").forEach { path ->
            assertThrows(RuntimeException::class.java) { CueSheet.parse("FILE\t\"$path\" BINARY\n TRACK 01 MODE2/2352") }
        }
        assertThrows(IllegalStateException::class.java) { CueSheet.parse("FILE \"audio.wav\" WAVE\n TRACK 01 AUDIO") }
    }
    @Test fun ps1IdentityCannotSelectTheCartridgeCoreAndResumeRejectsAnotherCore() {
        assertEquals(GameSystem.PS1, GameSystem.detect("test.exe", ByteArray(512).apply { "PS-X EXE".toByteArray().copyInto(this) }))
        assertNotEquals(GameSystem.GBA.coreFile, GameSystem.PS1.coreFile)
        val directory = Files.createTempDirectory("ps1-state").toFile()
        try {
            val store=SessionStore(directory);store.write("a".repeat(64),GameSystem.PS1,byteArrayOf(1,2,3))
            assertArrayEquals(byteArrayOf(1,2,3),store.read(store.available()!!))
            val manifest=directory.resolve("session.properties")
            manifest.writeText(manifest.readText().replace(GameSystem.PS1.coreRevision,GameSystem.GBA.coreRevision))
            assertThrows(IllegalArgumentException::class.java) { store.available() }
            assertThrows(IllegalArgumentException::class.java) { GameFiles.resolve(directory,"../bad",GameSystem.PS1) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun manualPs1StateRejectsAnUnboundOrIncompatibleFile() {
        val bytes=byteArrayOf(1,2,3)
        assertArrayEquals(bytes,ManualState.decode(GameSystem.PS1,ManualState.encode(GameSystem.PS1,bytes)))
        assertThrows(IllegalArgumentException::class.java) { ManualState.decode(GameSystem.PS1,bytes) }
        assertArrayEquals(bytes,ManualState.encode(GameSystem.GBA,bytes))
    }
    @Test fun sbiRejectsTruncatedAndNegativeNativeSectorIndices() {
        val valid=byteArrayOf(83,66,73,0,0,2,0,1)+ByteArray(10);SbiFile.validate(valid)
        assertThrows(IllegalArgumentException::class.java) { SbiFile.validate(valid.copyOf(valid.size-1)) }
        assertThrows(IllegalArgumentException::class.java) { SbiFile.validate(valid.copyOf().apply { this[5]=0 }) }
        assertThrows(IllegalArgumentException::class.java) { SbiFile.validate(valid.copyOf().apply { this[6]=0x75 }) }
    }
}
