package dev.cartridge.storage

import dev.cartridge.domain.Media
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class CoverSeamsTest {
    private val cover = Media.HandheldBox.cover  // back 125, spine 22, front 125: seams at 46.0% and 54.0%

    /**
     * A made-up unfolded cover: a back, a spine and a front in their own colours, each with artwork in it, blocks
     * of other colours that never run the whole height, so only the folds step all the way down.
     */
    private fun scan(width: Int, height: Int, backEnd: Int, frontStart: Int, seed: Int = 7): IntArray {
        val random = Random(seed)
        val colours = intArrayOf(0xFF3E8F4A.toInt(), 0xFF101418.toInt(), 0xFFB8BCC4.toInt())
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val panel = when { x < backEnd -> 0; x < frontStart -> 1; else -> 2 }
            val base = colours[panel]
            val n = random.nextInt(-10, 11)
            fun channel(shift: Int) = ((base shr shift and 0xFF) + n).coerceIn(0, 255) shl shift
            (0xFF shl 24) or channel(16) or channel(8) or channel(0)
        }
        // Artwork: rectangles inside the back and the front, at most 60% of the height, some right up to a fold.
        repeat(14) {
            val inBack = it % 2 == 0
            val x0 = if (inBack) random.nextInt(0, backEnd - 20) else random.nextInt(frontStart + 2, width - 20)
            val x1 = (x0 + random.nextInt(10, 60)).coerceAtMost(if (inBack) backEnd - 1 else width)
            val y0 = random.nextInt(0, height / 2); val y1 = (y0 + random.nextInt(height / 8, height * 6 / 10)).coerceAtMost(height)
            val colour = 0xFF000000.toInt() or random.nextInt(0xFFFFFF)
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * width + x] = colour
        }
        return pixels
    }

    @Test fun theFoldsAreFoundWhereTheScanHasThemNotWhereTheCaseSaysTheyShouldBe() {
        // As on a real Emerald scan: the spine is narrower and further right than 46%–54% of a 1024-wide cover.
        val (back, front) = CoverSeams.find(scan(1024, 480, 483, 541), 1024, 480, cover)
        assertEquals(483f, back * 1024, 2f)
        assertEquals(541f, front * 1024, 2f)
    }

    @Test fun aScanWithoutClearFoldsIsCutAtTheCasesOwnProportions() {
        val random = Random(3)
        val noise = IntArray(1024 * 480) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        assertEquals(cover.backEnd to cover.frontStart, CoverSeams.find(noise, 1024, 480, cover))
        val flat = IntArray(1024 * 480) { 0xFF404040.toInt() }
        assertEquals(cover.backEnd to cover.frontStart, CoverSeams.find(flat, 1024, 480, cover))
    }

    @Test fun oneClearFoldPlacesTheOtherAtTheCasesSpineWidthFromIt() {
        // The back runs into the spine in the same colour; only the front's fold shows.
        val width = 1024; val height = 480
        val pixels = scan(width, height, 483, 541)
        for (y in 0 until height) for (x in 0 until 541) pixels[y * width + x] = 0xFF101418.toInt()
        val (back, front) = CoverSeams.find(pixels, width, height, cover)
        assertEquals(541f, front * width, 2f)
        assertEquals(541f - (cover.frontStart - cover.backEnd) * width, back * width, 2f)
    }

    @Test fun aFoldFarFromTheCasesSeamIsNotTakenForOne() {
        // A strong edge a quarter of the way in, well outside where any spine could be, changes nothing.
        val width = 1024; val height = 480
        val pixels = IntArray(width * height) { i -> if (i % width < 256) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(cover.backEnd to cover.frontStart, CoverSeams.find(pixels, width, height, cover))
    }
}
