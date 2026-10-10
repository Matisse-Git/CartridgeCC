package dev.cartridge.domain

/**
 * True when sampled ARGB pixels are one flat white or one flat black: a display that is not on yet.
 * A handheld shows white until its game enables the screen, and a core may present black before its first
 * drawn frame. One flat colour of any other kind is already the game's own picture.
 */
fun blankDisplay(argb: IntArray): Boolean {
    if (argb.isEmpty()) return true
    var low = 255; var high = 0
    for (pixel in argb) {
        var shift = 16
        while (shift >= 0) {
            val channel = pixel shr shift and 0xFF
            if (channel < low) low = channel
            if (channel > high) high = channel
            shift -= 8
        }
    }
    return high - low <= 24 && (low >= 224 || high <= 24)
}
