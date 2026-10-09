package dev.cartridge

import dev.cartridge.domain.GameSystem
import org.junit.Assert.*
import org.junit.Test

class GameSystemTest {
    @Test fun colorHeaderDeterminesPlatformIndependentlyOfGbFilename() {
        val header = ByteArray(512)
        assertEquals(GameSystem.GB, GameSystem.detect("HOME.GBC", header))
        header[0x143] = 0x80.toByte()
        assertEquals(GameSystem.GBC, GameSystem.detect("home.gb", header))
        header[0x143] = 0xC0.toByte()
        assertEquals(GameSystem.GBC, GameSystem.detect("home.gbc", header))
        assertEquals(GameSystem.GBA, GameSystem.detect("home.GBA", ByteArray(192)))
    }
    @Test fun unsupportedArchivesAndIncompleteHeadersAreRejected() {
        assertFalse(GameSystem.supports("home.gb.zip")); assertTrue(GameSystem.supports("home.GB"))
        for ((name, bytes) in listOf("a.gb" to ByteArray(511), "a.gba" to ByteArray(191), "a.zip" to ByteArray(512))) {
            try { GameSystem.detect(name, bytes); fail("Accepted unsupported/incomplete ROM") } catch (_: IllegalArgumentException) { }
        }
    }
}
