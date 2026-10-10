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
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * A plain printed label for a game that has no artwork yet: its own title, set in type on label stock.
 * It is generated, never stored, and never replaces an accepted label. IO thread only.
 */
object TypesetLabel {
    // Label stock, kept below white so studio highlights have room and the type stays crisp.
    private const val PAPER = 0xFFD3CDBF.toInt()
    private const val INK = 0xFF211F1D.toInt()
    private const val RULE = 0xFFA9A294.toInt()
    private const val SMALL = 0xFF5F5A51.toInt()

    fun key(title: String, system: GameSystem) = "typeset:${system.name}:${title.hashCode()}:${title.length}"

    fun render(title: String, system: GameSystem): LabelPixels {
        val width = 512; val height = system.labelHeight
        val bitmap = createBitmap(width, height)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(PAPER)
            val disc = system == GameSystem.PS1
            // A disc is printed around its hole: the title sits in the upper band where the chord is still wide.
            val textWidth = if (disc) 300 else width - 76
            val top = when { disc -> 66f; system.tallCartridge -> 44f; else -> 26f }
            val bottom = when { disc -> 190f; system.tallCartridge -> height - 96f; else -> height - 62f }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -.01f }
            val lines = if (system == GameSystem.GBA) 3 else 4
            val layout = (if (disc) listOf(44f, 38f, 32f, 27f, 23f) else listOf(58f, 50f, 43f, 37f, 31f, 26f)).firstNotNullOfOrNull { size ->
                paint.textSize = size
                layout(title, paint, textWidth, lines, disc).takeIf { it.height <= bottom - top && it.lineCount <= lines && it.getEllipsisCount(it.lineCount - 1) == 0 }
            } ?: layout(title, paint, textWidth, lines, disc)
            canvas.withTranslation(if (disc) (width - textWidth) / 2f else 38f, top + max(0f, (bottom - top - layout.height) / 2)) { layout.draw(this) }
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SMALL; textSize = if (disc) 17f else 19f; letterSpacing = .14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = if (disc) Paint.Align.CENTER else Paint.Align.LEFT }
            if (disc) canvas.drawText(system.title.uppercase(), width / 2f, 412f, small)
            else {
                canvas.drawRect(38f, height - 52f, width - 38f, height - 50.5f, Paint().apply { color = RULE })
                canvas.drawText(system.title.uppercase(), 38f, height - 24f, small)
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val bytes = ByteBuffer.allocateDirect(pixels.size * 4)
            pixels.forEach { pixel -> bytes.put(Color.red(pixel).toByte()).put(Color.green(pixel).toByte()).put(Color.blue(pixel).toByte()).put(255.toByte()) }
            bytes.flip()
            return LabelPixels(key(title, system), width, height, bytes)
        } finally { bitmap.recycle() }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, lines: Int, centered: Boolean) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, .96f).setIncludePad(false).build()
}
