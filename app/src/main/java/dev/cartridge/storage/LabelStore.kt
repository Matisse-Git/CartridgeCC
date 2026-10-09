package dev.cartridge.storage

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import dev.cartridge.library.LabelFit
import dev.cartridge.library.LabelRef
import dev.cartridge.domain.GameSystem
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class LabelPixels(val key: String, val width: Int, val height: Int, val rgba: ByteBuffer)

/** IO-thread image import/decoding. Originals are retained; recipes derive bounded template textures. */
class LabelStore(private val directory: File) {
    private fun original(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$id.image")
    }
    fun import(resolver: ContentResolver, uri: Uri): LabelRef {
        return importStream(uri.toString()) { requireNotNull(resolver.openInputStream(uri)) { "The label image is unavailable" } }
    }
    fun importStream(source: String, open: () -> InputStream): LabelRef {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create label cache" }
        val temporary = File.createTempFile("label-", ".tmp", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 12L * 1024 * 1024) { "Label images must be at most 12 MiB" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    require(size > 0) { "The label image is empty" }
                    output.fd.sync()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) {
                "Choose a PNG, JPEG or WebP label image"
            }
            require(max(bounds.outWidth, bounds.outHeight) <= 32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) {
                "The label image dimensions are too large"
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = original(id)
            if (!destination.exists()) check(temporary.renameTo(destination)) { "Cannot store the label" }
            return LabelRef(id, sourceUri = source)
        } finally { temporary.delete() }
    }
    fun decode(label: LabelRef, system: GameSystem = GameSystem.GBA): LabelPixels {
        val file = original(label.assetId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The cached label is missing or invalid. Import it again." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val source = checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode the label" }
        val target = Bitmap.createBitmap(512, system.labelHeight, Bitmap.Config.ARGB_8888)
        try {
            val rotatedWidth = if (label.quarterTurns % 2 == 0) source.width else source.height
            val rotatedHeight = if (label.quarterTurns % 2 == 0) source.height else source.width
            val sx = target.width.toFloat() / rotatedWidth; val sy = target.height.toFloat() / rotatedHeight
            val scale = if (label.fit == LabelFit.Fit) min(sx, sy) else max(sx, sy)
            Canvas(target).apply {
                drawColor(Color.rgb(39, 38, 34))
                translate(target.width / 2f, target.height / 2f)
                scale(scale, scale)
                rotate(label.quarterTurns * 90f)
                drawBitmap(source, -source.width / 2f, -source.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val pixels = IntArray(target.width * target.height)
            target.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
            val bytes = ByteBuffer.allocateDirect(pixels.size * 4)
            pixels.forEach { pixel ->
                bytes.put((pixel shr 16).toByte()).put((pixel shr 8).toByte()).put(pixel.toByte()).put(255.toByte())
            }
            bytes.flip()
            return LabelPixels(label.textureKey(system), target.width, target.height, bytes)
        } finally { source.recycle(); target.recycle() }
    }
}
