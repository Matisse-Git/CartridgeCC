package dev.cartridge.storage

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import dev.cartridge.library.LabelFit
import dev.cartridge.library.LabelRef
import dev.cartridge.domain.Media
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class LabelPixels(val key: String, val width: Int, val height: Int, val rgba: ByteBuffer)

/** A box's printed faces: [spine] only where the art really has one. [edge] is the art's border colour, opaque. */
class CoverFaces(val front: Bitmap, val spine: Bitmap?, val edge: Int) {
    val bytes get() = front.byteCount + (spine?.byteCount ?: 0)
}

/** IO-thread image import/decoding. Originals are retained; recipes derive bounded template textures. */
class LabelStore(private val directory: File) {
    fun exportOriginal(id: String): File = original(id).also { check(it.isFile) { "An accepted image is missing. Import it again before backup." } }
    fun validateImage(file: File) {
        require(file.length() in 1..12L * 1024 * 1024) { "Invalid image size" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp") &&
            max(bounds.outWidth, bounds.outHeight) <= 32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Invalid backup image" }
    }
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
    fun decode(label: LabelRef, media: Media): LabelPixels = cover(label, media, media.cover)

    /** The stored original of an accepted image, or null when it is not on this device. */
    fun file(id: String): File? = runCatching { original(id) }.getOrNull()?.takeIf(File::isFile)

    /**
     * The faces of a box for the 2D-drawn shelf: its front and, for a whole unfolded cover (`box-texture`), its real
     * spine. A front alone has no spine; the box draws one from [CoverFaces.edge]. Decoded no taller than [height].
     */
    fun faces(label: LabelRef, media: Media, height: Int): CoverFaces {
        val file = original(label.assetId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The cached artwork is missing or invalid. Scrape it again." }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= height) sample *= 2
        val source = checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode the artwork" }
        val whole = label.provenance?.type == "box-texture"
        fun scaled(bitmap: Bitmap): Bitmap = if (bitmap.height <= height) bitmap else
            Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width * height / bitmap.height), height, true).also { if (it !== bitmap) bitmap.recycle() }
        return if (whole) {
            val cover = media.cover
            val frontStart = (cover.frontStart * source.width).toInt().coerceIn(0, source.width - 1)
            val backEnd = (cover.backEnd * source.width).toInt().coerceIn(0, frontStart)
            val front = scaled(Bitmap.createBitmap(source, frontStart, 0, source.width - frontStart, source.height))
            val spine = if (frontStart - backEnd >= 2) scaled(Bitmap.createBitmap(source, backEnd, 0, frontStart - backEnd, source.height)) else null
            val edge = edgeColour(front)
            source.recycle()
            CoverFaces(front, spine, edge)
        } else {
            val edge = edgeColour(source)
            CoverFaces(scaled(source), null, edge)
        }
    }

    /**
     * A case's whole unfolded cover (ADR 0029). `box-texture` is the cover already unfolded, back, spine, front, and
     * fills the texture. A front alone (`box-2D`, or the user's own image) fills the front panel; the spine and
     * back take the colour of its edge, a little darker on the spine.
     */
    private fun cover(label: LabelRef, media: Media, cover: dev.cartridge.domain.Cover): LabelPixels {
        val file = original(label.assetId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The cached artwork is missing or invalid. Scrape it again." }
        val whole = label.provenance?.type == "box-texture"
        val width = media.labelWidth; val height = media.labelHeight
        // Decode no more than the texture can show, never above 2048.
        val wantWide = if (whole) width else ((1 - cover.frontStart) * width).toInt()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048 || (bounds.outWidth / (sample * 2) >= wantWide && bounds.outHeight / (sample * 2) >= height)) sample *= 2
        val source = checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode the artwork" }
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            Canvas(target).apply {
                if (whole) {
                    drawColor(Color.BLACK)
                    drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
                } else {
                    val edge = edgeColour(source)
                    drawColor(edge)
                    val darker = Color.rgb((Color.red(edge) * .78f).toInt(), (Color.green(edge) * .78f).toInt(), (Color.blue(edge) * .78f).toInt())
                    drawRect(cover.backEnd * width, 0f, cover.frontStart * width, height.toFloat(), Paint().apply { color = darker })
                    val front = RectF(cover.frontStart * width, 0f, width.toFloat(), height.toFloat())
                    val art = source.width.toFloat() / source.height; val panel = front.width() / front.height()
                    // Fronts of another market's case differ a little and are stretched; a picture of another shape keeps it.
                    if (kotlin.math.abs(art / panel - 1) < .2f) drawBitmap(source, null, front, paint)
                    else {
                        val scale = if (label.fit == LabelFit.Fit) min(front.width() / source.width, front.height() / source.height)
                            else max(front.width() / source.width, front.height() / source.height)
                        val w = source.width * scale; val h = source.height * scale
                        save(); clipRect(front)
                        drawBitmap(source, null, RectF(front.centerX() - w / 2, front.centerY() - h / 2, front.centerX() + w / 2, front.centerY() + h / 2), paint)
                        restore()
                    }
                }
            }
            val bytes = ByteBuffer.allocateDirect(target.byteCount)
            target.copyPixelsToBuffer(bytes)
            bytes.flip()
            return LabelPixels(label.textureKey(media), width, height, bytes)
        } finally { source.recycle(); target.recycle() }
    }

    /** The average colour around a picture's border, opaque: what a printed box shows where the picture ends. */
    private fun edgeColour(bitmap: Bitmap): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = max(1, (bitmap.width + bitmap.height) / 200)
        fun add(x: Int, y: Int) { val c = bitmap.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
        for (x in 0 until bitmap.width step step) { add(x, 0); add(x, bitmap.height - 1) }
        for (y in 0 until bitmap.height step step) { add(0, y); add(bitmap.width - 1, y) }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
