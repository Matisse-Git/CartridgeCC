package dev.cartridge.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout as TextLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import dev.cartridge.R
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
import dev.cartridge.library.LibraryGame
import dev.cartridge.storage.CoverFaces
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a box shows: real [faces] when the game has case art, otherwise the placeholder drawn from its words and tones. */
class BoxLook(val title: String, val series: String?, val short: String, val system: String, val year: String?,
    val tone1: Int, val tone2: Int, val faces: CoverFaces?) {
    companion object {
        fun of(game: LibraryGame, faces: CoverFaces?): BoxLook {
            val title = displayTitle(game.title)
            val (series, short) = titleParts(title)
            val (t1, t2) = tones(title)
            return BoxLook(title, series, short, game.system.shortName, game.metadata["year"]?.value?.takeIf(String::isNotBlank), t1, t2, faces)
        }
    }
}

/** A title without the dump's region and revision tags. */
fun displayTitle(title: String) = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { title }

/** "The Legend of Zelda: The Minish Cap" is the series "The Legend of Zelda" and the title "The Minish Cap". */
fun titleParts(title: String): Pair<String?, String> {
    val split = title.indexOf(": ")
    if (split <= 0 || split >= title.length - 2) return null to title
    return title.substring(0, split).trim() to title.substring(split + 2).trim()
}

/** The muted, earthy two-tone gradient of a placeholder box, picked by the title so a game always has the same one. */
fun tones(title: String): Pair<Int, Int> {
    val light = MUTED[(title.fold(7) { a, ch -> (a * 31 + ch.code) % 9973 }) % MUTED.size]
    val r = (light shr 16) and 0xFF; val g = (light shr 8) and 0xFF; val b = light and 0xFF
    val dark = (0xFF shl 24) or ((r * .55f).toInt() shl 16) or ((g * .55f).toInt() shl 8) or (b * .55f).toInt()
    return dark to light
}
private val MUTED = intArrayOf(0xFF5E7D8C.toInt(), 0xFF8A7357.toInt(), 0xFF7C9273.toInt(), 0xFF7F7091.toInt(), 0xFFA38C5E.toInt(),
    0xFF56656E.toInt(), 0xFF5A6890.toInt(), 0xFF6E7B5E.toInt(), 0xFF675259.toInt(), 0xFF575063.toInt(), 0xFF9A8F80.toInt(),
    0xFF4E6470.toInt(), 0xFF7A6A62.toInt(), 0xFF5F6B57.toInt())

/** The short name chips, spines and badges use. */
val GameSystem.shortName: String get() = when (this) {
    GameSystem.GENESIS -> "Genesis"; GameSystem.SATURN -> "Saturn"; GameSystem.DREAMCAST -> "Dreamcast"; GameSystem.GAMECUBE -> "GameCube"
    GameSystem.SMS -> "Master System"; else -> name
}

/**
 * The base size of a box at the 1280-wide canvas, following its real packaging (§4.1): the spec's four, and the
 * other cases from their cover proportions at about the same area.
 */
fun boxSize(media: Media): Pair<Float, Float> = when (media) {
    Media.DvdCase -> 176f to 248f
    Media.HandheldBox -> 190f to 211f
    Media.SnesBox -> 240f to 172f
    Media.JewelCase -> 204f to 180f
    else -> {
        val front = media.cover.let { it.aspect * it.tall * (1 - it.frontStart) / it.tall }
        val area = 40_000f
        val w = sqrt(area * front); val h = sqrt(area / front)
        if (h < 150f) (w * 150f / h) to 150f else if (h > 250f) (w * 250f / h) to 250f else w to h
    }
}

/**
 * A real 3D game box (§4.1, Box.dc.html): front cover, left spine, right edge and top, projected with the given
 * perspective. [rotationY] follows CSS: positive turns the spine toward the viewer. [dim] darkens a neighbour.
 */
@Composable fun GameBox(look: BoxLook, width: Dp, height: Dp, depth: Dp, rotationY: Float, modifier: Modifier = Modifier,
    translateZ: Dp = 0.dp, dim: Float = 0f, focused: Boolean = false, reflection: Boolean = true, perspective: Dp = 1100.dp, alpha: Float = 1f) {
    val context = LocalContext.current
    val painter = remember { BoxPainter(context) }
    // Show boxes in 3D off: every box faces front as its cover alone, still sized and lifted where it stands.
    val flat = LocalFlatBoxes.current
    Canvas(modifier.size(width, height)) {
        drawIntoCanvas { canvas ->
            painter.draw(canvas.nativeCanvas, look, width.toPx(), height.toPx(), if (flat) 0f else depth.toPx(), if (flat) 0f else rotationY, translateZ.toPx(), perspective.toPx(),
                dim, focused, reflection, 1.dp.toPx(), alpha)
        }
    }
}

/** False when Settings › Library › Show boxes in 3D is off. */
val LocalFlatBoxes = androidx.compose.runtime.compositionLocalOf { false }

private enum class Face { Front, Spine, Edge, Top }

private class BoxPainter(context: Context) {
    private val base: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
    private fun weight(w: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, w, false) else Typeface.create(base, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
    private val bold = weight(700); private val heavy = weight(800); private val semi = weight(600)
    private val matrix = Matrix()
    private val src = FloatArray(8); private val dst = FloatArray(8)
    private val quads = Array(4) { FloatArray(8) }
    private val depths = FloatArray(4)
    private val visible = BooleanArray(4)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, d: Float, ry: Float, tz: Float, p: Float,
        dim: Float, focused: Boolean, reflection: Boolean, unit: Float, alpha: Float) {
        if (alpha <= 0f) return
        // A see-through box is drawn into its own layer, wide enough for its turned faces and its reflection.
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(-w, -h, w * 2, h * 3, (alpha * 255).toInt()) else -1
        project(w, h, d, ry, tz, p)
        if (reflection) {
            val gap = 8 * unit
            val top = h + gap
            val save = canvas.saveLayer(-w, top, w * 2, top + h, null)
            canvas.save(); canvas.translate(0f, 2 * h + gap); canvas.scale(1f, -1f)
            faces(canvas, look, w, h, d, dim, focused = false, unit)
            canvas.restore()
            mask.shader = LinearGradient(0f, top, 0f, top + h * .24f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(-w, top, w * 2, top + h, mask)
            canvas.restoreToCount(save)
        }
        if (focused && visible[Face.Front.ordinal]) {
            quadPath(quads[Face.Front.ordinal])
            shadow.color = 0xFF000000.toInt()
            shadow.setShadowLayer(30 * unit, 0f, 30 * unit, 0x99000000.toInt())
            canvas.drawPath(path, shadow)
        }
        faces(canvas, look, w, h, d, dim, focused, unit)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** Corner order per face is top-left, top-right, bottom-right, bottom-left as seen from outside the box. */
    private fun project(w: Float, h: Float, d: Float, ry: Float, tz: Float, p: Float) {
        val a = Math.toRadians(ry.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val x0 = -w / 2; val x1 = w / 2; val y0 = -h / 2; val y1 = h / 2; val z0 = -d / 2; val z1 = d / 2
        val corners = arrayOf(
            floatArrayOf(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1),
            floatArrayOf(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0),
            floatArrayOf(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1),
            floatArrayOf(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1))
        for (f in 0 until 4) {
            val q = quads[f]; val k = corners[f]; var depth = 0f
            for (i in 0 until 4) {
                val x = k[i * 3]; val y = k[i * 3 + 1]; val z = k[i * 3 + 2]
                val xr = x * c + z * s; val zr = -x * s + z * c + tz
                val scale = p / max(1f, p - zr)
                q[i * 2] = w / 2 + xr * scale; q[i * 2 + 1] = h / 2 + y * scale
                depth += zr
            }
            depths[f] = depth / 4
            var area = 0f
            for (i in 0 until 4) { val j = (i + 1) % 4; area += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1] }
            visible[f] = area > 1f
        }
    }

    private fun quadPath(q: FloatArray) { path.reset(); path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close() }

    private fun faces(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, d: Float, dim: Float, focused: Boolean, unit: Float) {
        val order = Face.entries.filter { visible[it.ordinal] }.sortedBy { depths[it.ordinal] }
        for (face in order) {
            val (fw, fh) = when (face) { Face.Front -> w to h; Face.Spine, Face.Edge -> d to h; Face.Top -> w to d }
            src[0] = 0f; src[1] = 0f; src[2] = fw; src[3] = 0f; src[4] = fw; src[5] = fh; src[6] = 0f; src[7] = fh
            System.arraycopy(quads[face.ordinal], 0, dst, 0, 8)
            matrix.setPolyToPoly(src, 0, dst, 0, 4)
            canvas.save(); canvas.concat(matrix)
            when (face) {
                Face.Front -> front(canvas, look, fw, fh, dim, focused, unit)
                Face.Spine -> spine(canvas, look, fw, fh, unit)
                Face.Edge -> { fill.shader = LinearGradient(0f, 0f, fw, 0f, 0xFF1A1A1C.toInt(), 0xFF0B0B0C.toInt(), Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, fw, fh, fill); fill.shader = null }
                Face.Top -> { fill.color = look.faces?.edge ?: look.tone1; canvas.drawRect(0f, 0f, fw, fh, fill); fill.color = 0x14FFFFFF; canvas.drawRect(0f, 0f, fw, fh, fill) }
            }
            if (face != Face.Front && dim > 0f) { fill.color = ((dim * 255).toInt().coerceIn(0, 255) shl 24); canvas.drawRect(0f, 0f, fw, fh, fill) }
            canvas.restore()
        }
    }

    private fun front(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, dim: Float, focused: Boolean, unit: Float) {
        val radius = 3 * unit
        canvas.save()
        path.reset(); rect.set(0f, 0f, w, h); path.addRoundRect(rect, radius, radius, Path.Direction.CW); canvas.clipPath(path)
        val art = look.faces?.front
        if (art != null) cover(canvas, art, w, h) else placeholder(canvas, look, w, h)
        stroke.strokeWidth = unit; stroke.color = 0x14FFFFFF; canvas.drawRoundRect(unit / 2, unit / 2, w - unit / 2, h - unit / 2, radius, radius, stroke)
        if (dim > 0f) { fill.color = ((dim * 255).toInt().coerceIn(0, 255) shl 24); canvas.drawRect(0f, 0f, w, h, fill) }
        if (focused) {
            // A sheen at 118°, as light across a glossy sleeve.
            val dx = sin(Math.toRadians(118.0)).toFloat(); val dy = -cos(Math.toRadians(118.0)).toFloat()
            val length = abs(w * dx) + abs(h * dy)
            val sx = w / 2 - dx * length / 2; val sy = h / 2 - dy * length / 2
            fill.shader = LinearGradient(sx, sy, sx + dx * length, sy + dy * length, intArrayOf(0x33FFFFFF, 0x0AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .3f, .42f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null
        }
        canvas.restore()
        stroke.color = if (focused) 0x1FFFFFFF else 0x0DFFFFFF
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, stroke)
    }

    /** object-fit: cover. */
    private fun cover(canvas: AndroidCanvas, bitmap: Bitmap, w: Float, h: Float) {
        if (bitmap.isRecycled) return
        val scale = max(w / bitmap.width, h / bitmap.height)
        val bw = bitmap.width * scale; val bh = bitmap.height * scale
        rect.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        canvas.drawBitmap(bitmap, null, rect, image)
    }

    /** §5: muted two-tone gradient, a faint top highlight, the system and year band, a faint image icon, series and title. */
    private fun placeholder(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float) {
        val k = min(w, h * .9f) / 180f
        val dx = sin(Math.toRadians(160.0)).toFloat(); val dy = -cos(Math.toRadians(160.0)).toFloat()
        val length = abs(w * dx) + abs(h * dy)
        fill.shader = LinearGradient(w / 2 - dx * length / 2, h / 2 - dy * length / 2, w / 2 + dx * length / 2, h / 2 + dy * length / 2,
            look.tone1, look.tone2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        canvas.save(); canvas.scale(1.2f, .8f, w * .25f, 0f)
        fill.shader = RadialGradient(w * .25f, 0f, max(w, h) * .6f, intArrayOf(0x29FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(-w, -h, w * 2, h * 2, fill); fill.shader = null
        canvas.restore()
        val band = 20 * k
        fill.color = 0x2E000000; canvas.drawRect(0f, 0f, w, band, fill)
        text.typeface = bold; text.textSize = max(5f, 7.5f * k); text.letterSpacing = .14f; text.color = 0xB8FFFFFF.toInt()
        val baseline = band / 2 - (text.descent() + text.ascent()) / 2
        text.textAlign = Paint.Align.LEFT; canvas.drawText(look.system.uppercase(), 9 * k, baseline, text)
        look.year?.let { text.textAlign = Paint.Align.RIGHT; canvas.drawText(it, w - 9 * k, baseline, text) }
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0f
        // The faint picture icon: a frame, a hill line and a sun.
        val s = 30 * k / 24f; val ix = w / 2 - 12 * s; val iy = h * .42f - 12 * s
        stroke.strokeWidth = 1.5f * s; stroke.color = 0x52FFFFFF; stroke.strokeJoin = Paint.Join.ROUND; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawRect(ix + 4 * s, iy + 5 * s, ix + 20 * s, iy + 19 * s, stroke)
        path.reset(); path.moveTo(ix + 4 * s, iy + 16 * s); path.lineTo(ix + 9 * s, iy + 11 * s); path.lineTo(ix + 13 * s, iy + 15 * s)
        path.lineTo(ix + 16 * s, iy + 12 * s); path.lineTo(ix + 20 * s, iy + 16 * s); canvas.drawPath(path, stroke)
        canvas.drawPoint(ix + 15 * s, iy + 9 * s, stroke)
        // Series and title from the bottom up.
        val inset = 13 * k
        val width = (w - inset * 2).toInt().coerceAtLeast(1)
        text.typeface = bold; text.textSize = 16 * k; text.letterSpacing = -.01f; text.color = 0xF2FFFFFF.toInt()
        val title = StaticLayout.Builder.obtain(look.short, 0, look.short.length, text, width).setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.0f).setAlignment(TextLayout.Alignment.ALIGN_NORMAL).build()
        var bottom = h - 14 * k - title.height
        canvas.save(); canvas.translate(inset, bottom); title.draw(canvas); canvas.restore()
        look.series?.let { series ->
            text.typeface = semi; text.textSize = max(5f, 8f * k); text.letterSpacing = .08f; text.color = 0x9EFFFFFF.toInt()
            val line = TextUtils.ellipsize(series.uppercase(), text, width.toFloat(), TextUtils.TruncateAt.END).toString()
            bottom -= max(1f, 3 * k)
            canvas.drawText(line, inset, bottom - text.descent(), text)
        }
        text.letterSpacing = 0f
    }

    /** The real spine where the art has one; otherwise the art's edge colour (or the placeholder tone) with the title running down it. */
    private fun spine(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, unit: Float) {
        val art = look.faces?.spine
        if (art != null && !art.isRecycled) {
            rect.set(0f, 0f, w, h); canvas.drawBitmap(art, null, rect, image)
            fill.shader = LinearGradient(0f, 0f, w, 0f, 0x40000000, 0x14000000, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null
            return
        }
        val tone = look.faces?.edge?.let { darker(it, .78f) } ?: look.tone2
        fill.color = tone; canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = LinearGradient(0f, 0f, w, 0f, 0x8C000000.toInt(), 0x40000000, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val pad = h * .055f
        // The console at the foot, the title from the top, both turned to run down the spine.
        text.typeface = heavy; text.textSize = max(5f, w * .28f); text.letterSpacing = .12f; text.color = 0x99FFFFFF.toInt()
        val system = look.system.uppercase()
        val systemLength = text.measureText(system)
        canvas.save(); canvas.translate(w / 2, h - pad - systemLength); canvas.rotate(90f)
        canvas.drawText(system, 0f, -(text.ascent() + text.descent()) / 2, text); canvas.restore()
        text.typeface = bold; text.textSize = max(6f, w * .36f); text.letterSpacing = .02f; text.color = 0xE0FFFFFF.toInt()
        val room = h - pad * 3 - systemLength
        if (room > text.textSize) {
            val title = TextUtils.ellipsize(look.short, text, room, TextUtils.TruncateAt.END).toString()
            canvas.save(); canvas.translate(w / 2, pad); canvas.rotate(90f)
            canvas.drawText(title, 0f, -(text.ascent() + text.descent()) / 2, text); canvas.restore()
        }
        text.letterSpacing = 0f
    }

    private fun darker(color: Int, by: Float) = (0xFF shl 24) or (((color shr 16 and 0xFF) * by).toInt() shl 16) or
        (((color shr 8 and 0xFF) * by).toInt() shl 8) or ((color and 0xFF) * by).toInt()
}
