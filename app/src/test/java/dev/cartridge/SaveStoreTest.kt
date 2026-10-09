package dev.cartridge

import dev.cartridge.storage.SaveStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SaveStoreTest {
    @Test fun oversizedAndEmptyStateFilesFailWithoutDeletingTheOriginal() {
        val directory = Files.createTempDirectory("cartridge-save-test").toFile()
        try {
            val id = "a".repeat(64); val store = SaveStore(directory)
            val good = byteArrayOf(1, 2, 3)
            store.write(id, "sram", good)
            assertThrows(IllegalArgumentException::class.java) { store.write(id, "sram", ByteArray(128 * 1024 + 1)) }
            assertArrayEquals(good, store.read(id, "sram"))
            store.write(id, "state", good)
            assertThrows(IllegalArgumentException::class.java) { store.write(id, "state", byteArrayOf()) }
            assertArrayEquals(good, store.read(id, "state"))
            val oversized = ByteArray(128 * 1024 + 1) { 7 }
            val file = File(directory, "$id.sram").apply { writeBytes(oversized) }
            assertThrows(IllegalArgumentException::class.java) { store.read(id, "sram") }
            assertArrayEquals(oversized, file.readBytes())
            File(directory, "$id.state").writeBytes(byteArrayOf())
            assertThrows(IllegalArgumentException::class.java) { store.read(id, "state") }
        } finally { directory.deleteRecursively() }
    }
    @Test fun competingWritersPublishWholeSavesWithIndependentTemporaryFiles() {
        val directory = Files.createTempDirectory("cartridge-save-test").toFile()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val id = "b".repeat(64)
            val first = ByteArray(16 * 1024) { 1 }; val second = ByteArray(8 * 1024) { 2 }
            val store = SaveStore(directory)
            store.write(id, "sram", first)
            val start = CountDownLatch(1)
            val jobs = listOf(first, second).map { bytes -> workers.submit {
                start.await()
                repeat(10) {
                    SaveStore(directory).write(id, "sram", bytes)
                    val read = store.read(id, "sram")!!
                    assertTrue(read.contentEquals(first) || read.contentEquals(second))
                }
            } }
            start.countDown(); jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
        } finally { workers.shutdownNow(); directory.deleteRecursively() }
    }
}
