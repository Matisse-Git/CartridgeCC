package dev.cartridge

import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.SessionStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class SessionStoreTest {
    @Test fun completedGenerationsReplaceAtomicallyAndRejectInvalidRecoveryData() {
        val directory = Files.createTempDirectory("cartridge-session").toFile()
        try {
            val store = SessionStore(directory); val id = "a".repeat(64)
            assertNull(store.available())
            store.write(id, GameSystem.GBC, byteArrayOf(1, 2), 123)
            val previous = store.available()!!
            assertArrayEquals(byteArrayOf(1, 2), store.read(previous))
            assertThrows(IllegalArgumentException::class.java) { store.write(id, GameSystem.GBC, byteArrayOf(), 124) }
            assertArrayEquals(byteArrayOf(1, 2), store.read(previous))
            store.write(id, GameSystem.GBC, byteArrayOf(3, 4), 125)
            assertEquals(1, directory.listFiles()!!.count { it.extension == "state" })
            assertThrows(IllegalArgumentException::class.java) { store.read(previous) }
            val current = store.available()!!
            directory.listFiles()!!.single { it.extension == "state" }.writeBytes(byteArrayOf(5, 6))
            assertThrows(IllegalArgumentException::class.java) { store.read(current) }
            store.clear(); assertNull(store.available())
        } finally { directory.deleteRecursively() }
    }
    @Test fun incompatibleCoreAndUnsafeStatePathCannotBeResumed() {
        val directory = Files.createTempDirectory("cartridge-session").toFile()
        try {
            val store = SessionStore(directory); store.write("b".repeat(64), GameSystem.GB, byteArrayOf(7), 1)
            val manifest = File(directory, "session.properties"); val original = manifest.readText()
            manifest.writeText(original.replace(SessionStore.CORE, "unknown-core"))
            assertThrows(IllegalArgumentException::class.java) { store.available() }
            manifest.writeText(original.replace(Regex("generation=.*"), "generation=../../outside"))
            assertThrows(IllegalArgumentException::class.java) { store.available() }
        } finally { directory.deleteRecursively() }
    }
}
