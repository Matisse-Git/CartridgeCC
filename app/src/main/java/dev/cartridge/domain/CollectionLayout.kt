package dev.cartridge.domain

/**
 * [visibleCount] objects rest on screen. [windowCount] objects are kept ready: the visible ones and the
 * neighbours a single step brings in, so an arriving object already carries its label.
 */
enum class CollectionLayout(val title: String, val visibleCount: Int, val windowCount: Int) {
    Horizontal("Horizontal", 3, 5), Vertical("Vertical", 3, 5), Grid("Grid", 9, 15);
    fun move(index: Int, count: Int, x: Int, y: Int): Int {
        require(count > 0 && index in 0 until count)
        return when (this) {
            Horizontal -> (index + x).coerceIn(0, count - 1)
            Vertical -> (index + y).coerceIn(0, count - 1)
            Grid -> {
                val row = (index / 3 + y).coerceIn(0, (count - 1) / 3)
                val column = (index % 3 + x).coerceIn(0, 2)
                (row * 3 + column).coerceAtMost(count - 1)
            }
        }
    }
    /** First shelf index of the window: two objects, or two grid rows, before the focus where the shelf allows. */
    fun windowStart(index: Int, count: Int): Int = if (this != Grid)
        (index - 2).coerceIn(0, (count - windowCount).coerceAtLeast(0))
    else ((index / 3 - 2).coerceIn(0, ((count + 2) / 3 - windowCount / 3).coerceAtLeast(0))) * 3
    fun gridCenter(index: Int, count: Int): Float {
        val rows = (count + 2) / 3
        return if (rows <= 3) (rows - 1) / 2f else (index / 3).coerceIn(1, rows - 2).toFloat()
    }
    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** GPU objects per media template. No window is larger. */
        const val POOL = 15
        /**
         * The pooled object that shows a shelf index. It depends on the index alone, so moving the window
         * re-labels only the objects that entered it and never the ones already on screen.
         */
        fun slot(index: Int) = Math.floorMod(index, POOL)
    }
}
