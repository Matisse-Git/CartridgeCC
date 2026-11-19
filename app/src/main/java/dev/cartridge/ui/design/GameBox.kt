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
import android.graphics.Rect
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a box shows: real [faces] when the game has case art, otherwise the placeholder drawn from its words and tones. */
class BoxLook(val title: String, val series: String?, val short: String, val system: String, val year: String?,
    val tone1: Int, val tone2: Int, val faces: CoverFaces?, val shape: CaseShape = Media.HandheldBox.shape) {
    companion object {
        fun of(game: LibraryGame, faces: CoverFaces?): BoxLook {
            val title = displayTitle(game.title)
            val (series, short) = titleParts(title)
            val (t1, t2) = tones(title)
            return BoxLook(title, series, short, game.system.shortName, game.metadata["year"]?.value?.takeIf(String::isNotBlank), t1, t2, faces, game.shownAs.shape)
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
 * A box's front at the 1280-wide canvas. Its shape is the real case's, so art is never cropped or stretched to fit;
 * its height is the spec's for the four cases §4.1 names (PS2 248, GBA 211, SNES 172, PS1 180), and for the others
 * about the same area.
 */
fun boxSize(media: Media): Pair<Float, Float> {
    val aspect = media.shape.aspect
    val tall = when (media) { Media.DvdCase -> 248f; Media.HandheldBox -> 211f; Media.SnesBox -> 172f; Media.JewelCase -> 180f; else -> null }
    if (tall != null) return tall * aspect to tall
    val area = 40_000f
    val w = sqrt(area * aspect); val h = sqrt(area / aspect)
    return if (h < 150f) (w * 150f / h) to 150f else if (h > 250f) (w * 250f / h) to 250f else w to h
}

/**
 * A game's case in 3D (§4.1): the front [width] by [height], as deep as the real case is for that width, turned
 * [rotationY] degrees (CSS sense: positive brings the spine toward the viewer) under one light from the upper left.
 * [dim] darkens a neighbour.
 */
@Composable fun GameBox(look: BoxLook, width: Dp, height: Dp, rotationY: Float, modifier: Modifier = Modifier,
    translateZ: Dp = 0.dp, dim: Float = 0f, focused: Boolean = false, reflection: Boolean = true, perspective: Dp = 1100.dp, alpha: Float = 1f) {
    val context = LocalContext.current
    val painter = remember { BoxPainter(context) }
    // Show boxes in 3D off: every box faces front as its cover alone, still sized and lifted where it stands.
    val flat = LocalFlatBoxes.current
    Canvas(modifier.size(width, height)) {
        drawIntoCanvas { canvas ->
            val w = width.toPx()
            painter.draw(canvas.nativeCanvas, look, w, height.toPx(), if (flat) 0f else w * look.shape.depth / look.shape.width, if (flat) 0f else rotationY,
                translateZ.toPx(), perspective.toPx(), dim, focused, reflection, 1.dp.toPx(), alpha)
        }
    }
}

/** False when Settings › Library › Show boxes in 3D is off. */
val LocalFlatBoxes = androidx.compose.runtime.compositionLocalOf { false }

/** Which part of the cover a strip of the case carries. */
private enum class Panel { Front, Spine, Side, Back }

/**
 * Draws a case as a prism: its cross-section, a rectangle with rounded corners, swept from top to bottom. Each strip
 * of the sweep is a flat quad projected with the given perspective, so the art bends round a bevel or a rounded
 * spine as a printed sleeve does. A strip carries the cover by where it faces: the front by its x, the spine by its
 * depth, so the art runs on across a corner without a jump. Seen from outside the case is convex, so the strips
 * that face the viewer never overlap and need no sorting. The cover is never cropped: the front fills the front,
 * the spine the spine, cut where the scan really folds (CoverSeams).
 */
private class BoxPainter(context: Context) {
    private val base: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
    private fun weight(w: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, w, false) else Typeface.create(base, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
    private val bold = weight(700); private val heavy = weight(800); private val semi = weight(600)
    private val matrix = Matrix()
    private val src = FloatArray(8); private val dst = FloatArray(8)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val path = Path()
    private val rect = RectF()
    private val from = Rect()

    // The cross-section loop: points, outward normals, and the strips between them.
    private val px = FloatArray(MAX); private val pz = FloatArray(MAX); private val nx = FloatArray(MAX); private val nz = FloatArray(MAX)
    private var points = 0
    // The pose: the turn and where a point lands on screen.
    private var cos = 1f; private var sin = 0f; private var tz = 0f; private var persp = 1100f; private var cw = 0f; private var ch = 0f

    // Faces drawn from words, for a game without art: made once per look at a fixed size, then mapped like art.
    private var madeFor: BoxLook? = null
    private var madeFront: Bitmap? = null
    private var madeSpine: Bitmap? = null

    fun draw(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, d: Float, ry: Float, z: Float, p: Float,
        dim: Float, focused: Boolean, reflection: Boolean, unit: Float, alpha: Float) {
        if (alpha <= 0f) return
        val a = Math.toRadians(ry.toDouble()); cos = cos(a).toFloat(); sin = sin(a).toFloat(); tz = z; persp = p; cw = w; ch = h
        section(look.shape, w, d)
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(-w, -h, w * 2, h * 3, (alpha * 255).toInt()) else -1
        if (reflection) {
            val gap = 8 * unit
            val top = h + gap
            val save = canvas.saveLayer(-w, top, w * 2, top + h, null)
            canvas.save(); canvas.translate(0f, 2 * h + gap); canvas.scale(1f, -1f)
            strips(canvas, look, w, h, d, dim, focused = false)
            canvas.restore()
            mask.shader = LinearGradient(0f, top, 0f, top + h * .24f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(-w, top, w * 2, top + h, mask)
            canvas.restoreToCount(save)
        }
        if (focused) {
            // The drop shadow follows the front's outline.
            project(-w / 2, -h / 2, d / 2, 0); project(w / 2, -h / 2, d / 2, 1); project(w / 2, h / 2, d / 2, 2); project(-w / 2, h / 2, d / 2, 3)
            path.reset(); path.moveTo(dst[0], dst[1]); path.lineTo(dst[2], dst[3]); path.lineTo(dst[4], dst[5]); path.lineTo(dst[6], dst[7]); path.close()
            shadow.color = 0xFF000000.toInt()
            shadow.setShadowLayer(30 * unit, 0f, 30 * unit, 0x99000000.toInt())
            canvas.drawPath(path, shadow)
        }
        strips(canvas, look, w, h, d, dim, focused)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** A point of the case, (x, y, z) about its centre, onto the screen, into [dst] at corner [i]. */
    private fun project(x: Float, y: Float, z: Float, i: Int) {
        val xr = x * cos + z * sin; val zr = -x * sin + z * cos + tz
        val scale = persp / max(1f, persp - zr)
        dst[i * 2] = cw / 2 + xr * scale; dst[i * 2 + 1] = ch / 2 + y * scale
    }

    /**
     * The cross-section: a w by d rectangle, the front toward +z and the spine at -x, its corners rounded. A
     * cardboard box is softly bevelled; a keep case's spine is well rounded and its open edge a little; a jewel case
     * is crisp. Each corner is split at 45°, where the art passes from one panel to the next.
     */
    private fun section(shape: CaseShape, w: Float, d: Float) {
        points = 0
        val hw = w / 2; val hd = d / 2
        val (spineR, edgeR) = when (shape.kind) {
            CaseKind.Box -> min(d * .1f, w * .02f).let { it to it }
            CaseKind.Keep -> d * .4f to d * .12f
            CaseKind.Jewel -> d * .12f to d * .12f
        }
        val steps = if (shape.kind == CaseKind.Keep) 4 else 2
        // Clockwise seen from above, starting where the front meets its right-hand corner.
        corner(hw - edgeR, hd - edgeR, edgeR, 0f, if (shape.kind == CaseKind.Keep) 2 else steps)      // front-right: +z to +x
        corner(hw - edgeR, -hd + edgeR, edgeR, 90f, if (shape.kind == CaseKind.Keep) 2 else steps)    // back-right: +x to -z
        corner(-hw + spineR, -hd + spineR, spineR, 180f, steps)                                         // back-left: -z to -x
        corner(-hw + spineR, hd - spineR, spineR, 270f, steps)                                          // front-left: -x to +z
        // Where the art begins and ends on a plastic case's front, so a strip never straddles it.
        if (shape.kind != CaseKind.Box) {
            val k = w / shape.width
            val right = hw - shape.inset * k
            split(right, hd)
            if (shape.kind == CaseKind.Jewel) split(right - shape.sheetWidth * k, hd)
        }
    }

    /** A quarter arc about (cx, cz), from [start] degrees (0 = facing +z, 90 = +x, clockwise seen from above), in [n] strips. */
    private fun corner(cx: Float, cz: Float, r: Float, start: Float, n: Int) {
        for (i in 0..n) {
            val t = Math.toRadians((start + 90f * i / n).toDouble())
            val sx = sin(t).toFloat(); val cz2 = cos(t).toFloat()
            add(cx + r * sx, cz + r * cz2, sx, cz2)
        }
    }

    private fun add(x: Float, z: Float, nX: Float, nZ: Float) {
        if (points >= MAX) return
        px[points] = x; pz[points] = z; nx[points] = nX; nz[points] = nZ; points++
    }

    /** Puts a point on the front (z = [front]) at [x], between the two loop points either side of it. */
    private fun split(x: Float, front: Float) {
        for (i in 0 until points) {
            val j = (i + 1) % points
            if (abs(pz[i] - front) < .01f && abs(pz[j] - front) < .01f && nz[i] > .99f && nz[j] > .99f && x < max(px[i], px[j]) - .01f && x > min(px[i], px[j]) + .01f) {
                if (points >= MAX) return
                // Between the last point and the first, it simply goes on the end.
                if (j == 0) { add(x, front, 0f, 1f); return }
                for (k in points downTo j + 1) { px[k] = px[k - 1]; pz[k] = pz[k - 1]; nx[k] = nx[k - 1]; nz[k] = nz[k - 1] }
                px[j] = x; pz[j] = front; nx[j] = 0f; nz[j] = 1f
                points++
                return
            }
        }
    }

    private fun strips(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float, d: Float, dim: Float, focused: Boolean) {
        val shape = look.shape
        val k = w / shape.width
        val band = if (shape.kind == CaseKind.Box) h else min(h, shape.sheetHeight * k)
        // The front art's span across the case: a box is printed edge to edge; a sleeve runs from the spine to its
        // inset; a booklet sits in the lid right of the hinge.
        val artRight = if (shape.kind == CaseKind.Box) w / 2 else w / 2 - shape.inset * k
        val artLeft = when (shape.kind) { CaseKind.Box, CaseKind.Keep -> -w / 2; CaseKind.Jewel -> artRight - shape.sheetWidth * k }
        val faces = look.faces
        val front = faces?.front?.takeIf { !it.isRecycled } ?: madeFront(look, (artRight - artLeft) / band)
        val spine = faces?.spine?.takeIf { !it.isRecycled } ?: madeSpine(look, if (d > 0f) d / band else .1f)
        val side = when (shape.kind) { CaseKind.Box -> faces?.side ?: darker(look.tone2, .85f); else -> shape.shell }
        // The front's picture: all of it, unless its shape is far from the panel's, when its middle is taken.
        val panel = (artRight - artLeft) / band
        val art = front.width.toFloat() / front.height
        val crop = when {
            abs(art / panel - 1f) < .2f -> floatArrayOf(0f, front.width.toFloat(), 0f, front.height.toFloat())
            art > panel -> (front.height * panel).let { cut -> floatArrayOf((front.width - cut) / 2, (front.width + cut) / 2, 0f, front.height.toFloat()) }
            else -> (front.width / panel).let { cut -> floatArrayOf(0f, front.width.toFloat(), (front.height - cut) / 2, (front.height + cut) / 2) }
        }.let { Quad(it[0], it[1], it[2], it[3]) } // left, right, top, bottom in the bitmap
        // The light, from the upper left and a little in front, and the direction a highlight faces, in the turned view.
        val lx = -.52f; val lz = .85f
        val hx0 = lx; val hz0 = lz + 1f; val hl = hypot(hx0, hz0); val hx = hx0 / hl; val hz = hz0 / hl
        val frontLight = AMBIENT + DIFFUSE * max(0f, sin * lx + cos * lz)
        for (i in 0 until points) {
            val j = (i + 1) % points
            // The strip's facing, in the case and turned toward the viewer.
            val mx0 = (nx[i] + nx[j]) / 2; val mz0 = (nz[i] + nz[j]) / 2
            val ml = hypot(mx0, mz0).takeIf { it > 0f } ?: continue
            val mx = mx0 / ml; val mz = mz0 / ml
            val which = when { mz > abs(mx) -> Panel.Front; -mx >= abs(mz) -> Panel.Spine; mx >= abs(mz) -> Panel.Side; else -> Panel.Back }
            if (which == Panel.Back) continue
            // Seen from outside, the left end is the one further against the strip's rightward direction (mz, -mx).
            val di = px[i] * mz - pz[i] * mx; val dj = px[j] * mz - pz[j] * mx
            val (l, r) = if (di <= dj) i to j else j to i
            project(px[l], -h / 2, pz[l], 0); project(px[r], -h / 2, pz[r], 1); project(px[r], h / 2, pz[r], 2); project(px[l], h / 2, pz[l], 3)
            var area = 0f
            for (c in 0 until 4) { val n = (c + 1) % 4; area += dst[c * 2] * dst[n * 2 + 1] - dst[n * 2] * dst[c * 2 + 1] }
            if (area <= .5f) continue
            val length = hypot(px[r] - px[l], pz[r] - pz[l]).coerceAtLeast(.01f)
            src[0] = 0f; src[1] = 0f; src[2] = length; src[3] = 0f; src[4] = length; src[5] = h; src[6] = 0f; src[7] = h
            matrix.setPolyToPoly(src, 0, dst, 0, 4)
            canvas.save(); canvas.concat(matrix)
            // Half a pixel more each side, so neighbouring strips meet without a seam of background between them.
            val screen = hypot(dst[2] - dst[0], dst[3] - dst[1]).coerceAtLeast(.5f)
            val e = min(length * .25f, .6f * length / screen)
            rect.set(-e, 0f, length + e, h)
            val top = (h - band) / 2; val bottom = top + band
            val curved = abs(nx[i] - nx[j]) + abs(nz[i] - nz[j]) > .01f
            when (which) {
                Panel.Front -> {
                    val inArt = min(px[l], px[r]) >= artLeft - .01f && max(px[l], px[r]) <= artRight + .01f
                    if (shape.kind != CaseKind.Box) { fill.color = shape.shell; canvas.drawRect(rect, fill) }
                    if (inArt) {
                        val u0 = (px[l] - artLeft) / (artRight - artLeft); val u1 = (px[r] - artLeft) / (artRight - artLeft)
                        val f0 = (crop.a + u0 * (crop.b - crop.a)).toInt().coerceIn(0, front.width - 1)
                        val f1 = (crop.a + u1 * (crop.b - crop.a)).toInt().coerceIn(f0 + 1, front.width)
                        from.set(f0, crop.c.toInt(), f1, crop.d.toInt())
                        rect.set(-e, top, length + e, bottom); canvas.drawBitmap(front, from, rect, image)
                        rect.set(-e, 0f, length + e, h)
                    } else if (shape.kind == CaseKind.Jewel && !curved && px[r] <= artLeft + .01f) hinge(canvas, length, h)
                }
                Panel.Spine -> {
                    if (shape.kind != CaseKind.Box) { fill.color = shape.shell; canvas.drawRect(rect, fill) }
                    val u0 = ((pz[l] + d / 2) / d).coerceIn(0f, 1f); val u1 = ((pz[r] + d / 2) / d).coerceIn(0f, 1f)
                    val a0 = (u0 * spine.width).toInt(); val a1 = max(a0 + 1, (u1 * spine.width).toInt()).coerceAtMost(spine.width)
                    from.set(min(a0, spine.width - 1), 0, a1, spine.height)
                    rect.set(-e, top, length + e, bottom); canvas.drawBitmap(spine, from, rect, image)
                    // Seen through a jewel case's clear side, the tray card is a shade darker.
                    if (shape.kind == CaseKind.Jewel) { fill.color = 0x33000000; canvas.drawRect(rect, fill) }
                    rect.set(-e, 0f, length + e, h)
                }
                Panel.Side -> { fill.color = side; canvas.drawRect(rect, fill) }
                Panel.Back -> Unit
            }
            // Light: each strip against the front, so the front always shows its art's own colours.
            val tx = mx * cos + mz * sin; val tzz = -mx * sin + mz * cos
            val light = (AMBIENT + DIFFUSE * max(0f, tx * lx + tzz * lz)) / frontLight
            if (light < .995f) { fill.color = (((1f - light).coerceIn(0f, .7f) * 255).toInt() shl 24); canvas.drawRect(rect, fill) }
            else if (light > 1.005f) { fill.color = (((light - 1f) * .5f).coerceIn(0f, .18f) * 255).toInt() shl 24 or 0xFFFFFF; canvas.drawRect(rect, fill) }
            // A highlight runs along the rounded edges of plastic; cardboard is matte.
            if (curved && shape.kind != CaseKind.Box) {
                val spec = max(0f, tx * hx + tzz * hz).pow(28f) * if (shape.kind == CaseKind.Jewel) .4f else .26f
                if (spec > .01f) { fill.color = ((spec * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF; canvas.drawRect(rect, fill) }
            }
            // A clear sleeve or lid catches a soft band of light across the front; the focused box a brighter one.
            if (which == Panel.Front && !curved && (focused || shape.kind != CaseKind.Box)) sheen(canvas, length, h, px[l] + w / 2, w, focused, shape.kind)
            if (dim > 0f) { fill.color = ((dim * 255).toInt().coerceIn(0, 255) shl 24); canvas.drawRect(rect, fill) }
            canvas.restore()
        }
    }

    /** A sheen at 118°, as light across a glossy sleeve, laid over the whole front so its strips line up. */
    private fun sheen(canvas: AndroidCanvas, length: Float, h: Float, offset: Float, w: Float, focused: Boolean, kind: CaseKind) {
        val dx = sin(Math.toRadians(118.0)).toFloat(); val dy = -cos(Math.toRadians(118.0)).toFloat()
        val span = abs(w * dx) + abs(h * dy)
        val sx = w / 2 - dx * span / 2 - offset; val sy = h / 2 - dy * span / 2
        val strength = when { focused -> 0x33; kind == CaseKind.Jewel -> 0x1F; else -> 0x14 }
        fill.shader = LinearGradient(sx, sy, sx + dx * span, sy + dy * span, intArrayOf(strength shl 24 or 0xFFFFFF, (strength / 5) shl 24 or 0xFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, .3f, .42f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, length, h, fill); fill.shader = null
    }

    /** A jewel case's hinge: clear plastic over the black tray, with its two moulded ridges. */
    private fun hinge(canvas: AndroidCanvas, length: Float, h: Float) {
        stroke.strokeWidth = max(.6f, length * .05f)
        stroke.color = 0x29FFFFFF; canvas.drawLine(length * .32f, 0f, length * .32f, h, stroke)
        stroke.color = 0x1AFFFFFF; canvas.drawLine(length * .62f, 0f, length * .62f, h, stroke)
        stroke.color = 0x66000000; canvas.drawLine(length * .36f, 0f, length * .36f, h, stroke)
    }

    private fun madeFront(look: BoxLook, aspect: Float): Bitmap {
        if (madeFor !== look) { madeFront = null; madeSpine = null; madeFor = look }
        madeFront?.let { return it }
        val h = MADE_HEIGHT; val w = max(8, (h * aspect).toInt())
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { placeholder(AndroidCanvas(it), look, w.toFloat(), h.toFloat()); madeFront = it }
    }

    private fun madeSpine(look: BoxLook, aspect: Float): Bitmap {
        if (madeFor !== look) { madeFront = null; madeSpine = null; madeFor = look }
        madeSpine?.let { return it }
        val h = MADE_HEIGHT; val w = max(4, (h * aspect).toInt())
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { typesetSpine(AndroidCanvas(it), look, w.toFloat(), h.toFloat()); madeSpine = it }
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

    /** A spine for art without one: the art's edge colour (or the placeholder's tone), the title down it and the console at its foot. */
    private fun typesetSpine(canvas: AndroidCanvas, look: BoxLook, w: Float, h: Float) {
        val tone = look.faces?.edge?.let { darker(it, .78f) } ?: look.tone2
        fill.color = tone; canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = LinearGradient(0f, 0f, w, 0f, 0x4D000000, 0x1A000000, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val pad = h * .055f
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

    private class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    companion object {
        private const val MAX = 48
        /** How light the darkest side of a case is, and how much the light adds. */
        private const val AMBIENT = .45f
        private const val DIFFUSE = .55f
        /** The height procedural faces are drawn at before they are mapped onto a case. */
        private const val MADE_HEIGHT = 420
    }
}
