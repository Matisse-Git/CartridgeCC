package dev.cartridge.domain

/** Normalized clock; each physical phase has its own zero-velocity endpoints. */
object MediaMotion {
    const val INSERT_MS = 540
    const val EJECT_MS = 420
    data class Pose(val height: Float, val alignment: Float, val slot: Float)
    fun pose(progress: Float): Pose {
        val p = progress.coerceIn(0f, 1f)
        return Pose(.18f * ease(p / .24f) - 1.50f * ease((p - .42f) / .58f),
            ease((p - .12f) / .34f), ease(p / .28f))
    }
    private fun ease(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }
}
