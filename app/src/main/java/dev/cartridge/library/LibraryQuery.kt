package dev.cartridge.library

import java.util.Locale

enum class LibrarySort(val title: String) { Added("Import order"), Title("Title A–Z"), Recent("Recently played"), Playtime("Most played") }

/** A view of the canonical collection: sorting/filtering never changes ROM identity or stored order. */
data class LibraryQuery(val sort: LibrarySort = LibrarySort.Added, val favoritesOnly: Boolean = false, val search: String = "") {
    init { require(search.length <= 160) }
    fun apply(games: List<LibraryGame>): List<LibraryGame> {
        val words = search.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        val filtered = games.filter { game ->
            (!favoritesOnly || game.favorite) && words.all { word ->
                listOf(game.title, game.metadata["developer"]?.value.orEmpty(), game.metadata["genre"]?.value.orEmpty())
                    .any { it.lowercase(Locale.ROOT).contains(word) }
            }
        }
        // Stable ties retain canonical import order.
        return when (sort) {
            LibrarySort.Added -> filtered
            LibrarySort.Title -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            LibrarySort.Recent -> filtered.sortedByDescending { it.lastPlayed }
            LibrarySort.Playtime -> filtered.sortedByDescending { it.playMs }
        }
    }
}

/** Monotonic running time only. Paused/preparing/background time is excluded. */
class PlayClock {
    private var started: Long? = null
    fun running(now: Long) { if (started == null) started = now }
    fun checkpoint(now: Long, keepRunning: Boolean): Long {
        val elapsed = started?.let { (now - it).coerceAtLeast(0) } ?: 0
        started = if (keepRunning && started != null) now else null
        return elapsed
    }
}
