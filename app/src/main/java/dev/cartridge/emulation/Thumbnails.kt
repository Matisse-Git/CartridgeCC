package dev.cartridge.emulation

import android.graphics.Bitmap
import androidx.core.graphics.scale
import dev.cartridge.domain.pictureBounds
import java.io.ByteArrayOutputStream

/** A save's thumbnail: the copied screen without the bars beside the game's picture, small, as a JPEG. */
object Thumbnails {
    const val HEIGHT = 180
    private const val SAMPLE_WIDTH = 480

    fun encode(screen: Bitmap): ByteArray {
        require(screen.width > 0 && screen.height > 0)
        val sample = screen.scale(SAMPLE_WIDTH, (SAMPLE_WIDTH * screen.height / screen.width).coerceAtLeast(1))
        try {
            val pixels = IntArray(sample.width * sample.height).also { sample.getPixels(it, 0, sample.width, 0, 0, sample.width, sample.height) }
            val (left, top, right, bottom) = pictureBounds(pixels, sample.width, sample.height).toList()
            val picture = Bitmap.createBitmap(sample, left, top, right - left, bottom - top)
            try {
                val small = picture.scale((HEIGHT * picture.width / picture.height).coerceIn(1, HEIGHT * 3), HEIGHT)
                try {
                    return ByteArrayOutputStream().use { output -> check(small.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.toByteArray() }
                } finally { if (small !== picture) small.recycle() }
            } finally { if (picture !== sample) picture.recycle() }
        } finally { if (sample !== screen) sample.recycle() }
    }
}
