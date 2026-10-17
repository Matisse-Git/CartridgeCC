package dev.cartridge.storage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * A plain printed case for a game that has no case art yet: its own title, set in type on label stock.
 * It is generated, never stored, and never replaces accepted art. IO thread only.
 */
object TypesetLabel {
    // Label stock, kept below white so studio highlights have room and the type stays crisp.
    private const val PAPER = 0xFFD3CDBF.toInt()
    private const val INK = 0xFF211F1D.toInt()
    private const val RULE = 0xFFA9A294.toInt()
    private const val SMALL = 0xFF5F5A51.toInt()

    fun key(title: String, system: GameSystem, media: Media = system.media(title)) = "typeset:${system.name}:${media.name}:${title.hashCode()}:${title.length}"

    fun render(title: String, system: GameSystem, media: Media = system.media(title)): LabelPixels = cover(title, system, media)

    /** A plain case for a game without case art (ADR 0029): its title on the front and up the spine, on label stock. */
    private fun cover(title: String, system: GameSystem, media: Media): LabelPixels {
        val cover = media.cover
        val width = media.labelWidth; val height = media.labelHeight
        val bitmap = createBitmap(width, height)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(PAPER)
            val front = cover.frontStart * width; val spine = cover.backEnd * width
            val frontWidth = width - front
            val margin = frontWidth * .09f
            val textWidth = (frontWidth - 2 * margin).toInt()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -.01f }
            val room = height * .62f
            val layout = generateSequence(frontWidth * .16f) { it * .88f }.take(12).firstNotNullOfOrNull { size ->
                paint.textSize = size
                layout(title, paint, textWidth, 5, false).takeIf { it.height <= room && it.getEllipsisCount(it.lineCount - 1) == 0 }
            } ?: layout(title, paint, textWidth, 5, false)
            canvas.withTranslation(front + margin, height * .12f) { layout.draw(this) }
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SMALL; textSize = frontWidth * .045f; letterSpacing = .14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            canvas.drawRect(front + margin, height - margin - small.textSize * 1.9f, width - margin, height - margin - small.textSize * 1.9f + 2f, Paint().apply { color = RULE })
            canvas.drawText(system.title.uppercase(), front + margin, height - margin, small)
            // The spine reads from top to bottom, as a shelf of boxes does.
            val spineWidth = front - spine
            val along = TextPaint(paint).apply { textSize = (spineWidth * .52f).coerceAtMost(height * .06f) }
            canvas.withTranslation(spine + spineWidth / 2 - along.textSize * .36f, height * .06f) {
                rotate(90f)
                drawText(TextUtils.ellipsize(title, along, height * .88f, TextUtils.TruncateAt.END).toString(), 0f, 0f, along)
            }
            canvas.drawRect(spine, 0f, spine + 1.5f, height.toFloat(), Paint().apply { color = RULE })
            canvas.drawRect(front - 1.5f, 0f, front, height.toFloat(), Paint().apply { color = RULE })
            canvas.drawText(system.title.uppercase(), margin, height - margin, small)
            val bytes = ByteBuffer.allocateDirect(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(bytes)
            bytes.flip()
            return LabelPixels(key(title, system, media), width, height, bytes)
        } finally { bitmap.recycle() }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, lines: Int, centered: Boolean) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, .96f).setIncludePad(false).build()
}
