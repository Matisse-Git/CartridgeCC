package dev.cartridge.domain

enum class CollectionLayout(val title: String, val visibleCount: Int) {
    Horizontal("Horizontal", 3), Vertical("Vertical", 3), Grid("Grid", 9);
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
    fun windowStart(index: Int, count: Int): Int = if (this != Grid)
        (index - 1).coerceIn(0, (count - 3).coerceAtLeast(0))
    else ((index / 3 - 1).coerceIn(0, ((count + 2) / 3 - 3).coerceAtLeast(0))) * 3
    fun gridCenter(index: Int, count: Int): Float {
        val rows = (count + 2) / 3
        return if (rows <= 3) (rows - 1) / 2f else (index / 3).coerceIn(1, rows - 2).toFloat()
    }
    fun next() = entries[(ordinal + 1) % entries.size]
}
