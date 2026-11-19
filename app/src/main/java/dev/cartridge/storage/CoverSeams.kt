package dev.cartridge.storage

import dev.cartridge.domain.Cover
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a whole unfolded cover's panels really meet. A scan rarely matches the case's millimetres: its margins,
 * bleed and spine width vary, so cutting at [Cover.backEnd] and [Cover.frontStart] puts some of the back on the
 * spine and some of the spine on the front. A fold between panels is a colour change down nearly the whole height
 * of the scan at one column; the artwork inside a panel changes colour too, but never all the way down at one
 * place. So each column is scored by how many rows step sharply across it, and the best pair of columns near the
 * case's own seams, a plausible spine apart, are the fold. Where the scan shows no clear fold, the case's
 * proportions stand.
 */
object CoverSeams {
    /** A fold steps across at least this many more of the rows than the columns around it; artwork does not. */
    private const val CLEAR = .3f
    /** How far a scan's seam may sit from the case's, as a share of its width. */
    private const val REACH = .05f
    /** Red, green and blue together must change by this much for a row to step. */
    private const val STEP = 54

    /** The back's end and the front's start, as shares of the width (0..1). */
    fun find(pixels: IntArray, width: Int, height: Int, cover: Cover): Pair<Float, Float> {
        val fallback = cover.backEnd to cover.frontStart
        if (width < 32 || height < 16 || pixels.size < width * height) return fallback
        val score = folds(pixels, width, height)
        val back = cover.backEnd * width; val front = cover.frontStart * width
        val spine = front - back
        val reach = max(3f, REACH * width)
        fun window(at: Float) = (at - reach).roundToInt().coerceIn(2, width - 3)..(at + reach).roundToInt().coerceIn(2, width - 3)
        var best = -1f; var pair: Pair<Int, Int>? = null
        for (b in window(back)) for (f in window(front)) {
            val gap = f - b
            if (gap < spine * .45f || gap > spine * 1.8f) continue
            val total = score[b] + score[f]
            if (total > best) { best = total; pair = b to f }
        }
        val (b, f) = pair ?: return fallback
        val sureBack = score[b] >= CLEAR; val sureFront = score[f] >= CLEAR
        return when {
            sureBack && sureFront -> b / width.toFloat() to f / width.toFloat()
            // One clear fold: the other follows at the case's spine width from it.
            sureFront -> peak(score, window(back), CLEAR)?.let { it / width.toFloat() to f / width.toFloat() }
                ?: ((f - spine) / width).coerceAtLeast(0f) to f / width.toFloat()
            sureBack -> peak(score, window(front), CLEAR)?.let { b / width.toFloat() to it / width.toFloat() }
                ?: b / width.toFloat() to ((b + spine) / width).coerceAtMost(1f)
            else -> fallback
        }
    }

    /**
     * How far each column stands above those around it (3 to 12 columns away on either side). Busy artwork steps at
     * many columns, so a column of it stands no higher than its neighbours; a fold stands alone.
     */
    internal fun contrast(score: FloatArray): FloatArray = FloatArray(score.size) { x ->
        var sum = 0f; var n = 0
        for (o in 3..12) { if (x - o >= 0) { sum += score[x - o]; n++ }; if (x + o < score.size) { sum += score[x + o]; n++ } }
        if (n == 0) 0f else (score[x] - sum / n).coerceAtLeast(0f)
    }

    private fun peak(score: FloatArray, range: IntRange, floor: Float): Int? = range.maxByOrNull { score[it] }?.takeIf { score[it] >= floor }

    /**
     * For each column x, the share of rows whose colour steps between x - 2 and x + 1: a fold softened over a few
     * pixels by scanning and scaling still reads as one step. The top and bottom rows, where scans fray, are left out.
     */
    internal fun scores(pixels: IntArray, width: Int, height: Int): FloatArray {
        val score = FloatArray(width)
        val first = (height * .04f).toInt(); val last = height - first
        val stride = max(1, (last - first) / 180)
        var rows = 0
        var y = first
        while (y < last) {
            val row = y * width
            for (x in 2 until width - 1) if (distance(pixels[row + x - 2], pixels[row + x + 1]) >= STEP) score[x] += 1f
            rows++; y += stride
        }
        if (rows > 0) for (x in score.indices) score[x] /= rows
        return score
    }

    /** Each column's [contrast], where a fold spread over a few columns counts once, at its strongest. */
    internal fun folds(pixels: IntArray, width: Int, height: Int): FloatArray {
        val lifted = contrast(scores(pixels, width, height))
        return FloatArray(width) { x ->
            val peak = (x == 0 || lifted[x] >= lifted[x - 1]) && (x == width - 1 || lifted[x] >= lifted[x + 1])
            if (peak) lifted[x] else lifted[x] * .5f
        }
    }

    private fun distance(a: Int, b: Int) =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
}
