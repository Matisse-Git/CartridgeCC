package dev.cartridge.domain

/** Normalized clock; each physical phase has its own zero-velocity endpoints. */
object MediaMotion {
    const val INSERT_MS = 540
    const val EJECT_MS = 420
    /** The curtain closes after the object has gone, before the stage changes hands. */
    const val CURTAIN_MS = 260
    data class Pose(val height: Float, val alignment: Float)
    /** Lift, turn to face the viewer at the centre, then leave through the bottom of the screen. */
    fun pose(progress: Float): Pose {
        val p = progress.coerceIn(0f, 1f)
        // 2.13 world units carries the tallest object, the disc, wholly below the view.
        return Pose(.18f * ease(p / .24f) - 2.13f * ease((p - .42f) / .58f), ease((p - .12f) / .34f))
    }
    private fun ease(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }
}
