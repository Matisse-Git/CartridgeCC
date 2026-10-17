package dev.cartridge.domain

/**
 * The part of a copied screen that is the game's picture, as left, top, right and bottom (right and bottom exclusive).
 * A 4:3 or 3:2 picture on a 16:9 screen has black bars beside it, which a save's thumbnail should not keep. Only
 * bars that are completely black and equal on both sides are taken away: a dark scene, or one dark only at one
 * edge, keeps the whole screen rather than being cut where the game drew nothing.
 */
fun pictureBounds(argb: IntArray, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0 && argb.size >= width * height)
    fun black(pixel: Int) = (pixel shr 16 and 0xFF) <= DARK && (pixel shr 8 and 0xFF) <= DARK && (pixel and 0xFF) <= DARK
    fun columnBlack(x: Int) = (0 until height).all { black(argb[it * width + x]) }
    fun rowBlack(y: Int) = (0 until width).all { black(argb[y * width + it]) }
    var left = 0
    while (left < width && columnBlack(left)) left++
    if (left == width) return intArrayOf(0, 0, width, height)
    var right = width
    while (right > left && columnBlack(right - 1)) right--
    var top = 0
    while (top < height && rowBlack(top)) top++
    var bottom = height
    while (bottom > top && rowBlack(bottom - 1)) bottom--
    // Bars are as wide on one side as the other, give or take the rounding of a scaled picture.
    fun even(start: Int, end: Int, total: Int) = kotlin.math.abs(start - (total - end)) <= maxOf(2, total / 50)
    val keepX = even(left, right, width) && right - left >= width / 3
    val keepY = even(top, bottom, height) && bottom - top >= height / 3
    return intArrayOf(if (keepX) left else 0, if (keepY) top else 0, if (keepX) right else width, if (keepY) bottom else height)
}

private const val DARK = 6
