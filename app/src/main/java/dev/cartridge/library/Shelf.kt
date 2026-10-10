package dev.cartridge.library

import dev.cartridge.domain.GameSystem

/** A browsing view over the canonical collection. Shelves never rewrite membership, order or ROM identity. */
sealed class Shelf(val key: String, val title: String) {
    data class Console(val system: GameSystem) : Shelf(system.name, system.title)
    data object All : Shelf("all", "All Games")
    data object Favorites : Shelf("favorites", "Favorites")
    data object Recent : Shelf("recent", "Recently Played")

    fun apply(games: List<LibraryGame>): List<LibraryGame> = when (this) {
        is Console -> games.filter { it.system == system }
        All -> games
        Favorites -> games.filter { it.favorite }
        Recent -> games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(RECENT_LIMIT)
    }

    companion object {
        const val RECENT_LIMIT = 24
        /** Consoles that hold games, then the unified views that would show something different. */
        fun available(games: List<LibraryGame>): List<Shelf> {
            val consoles = GameSystem.entries.filter { system -> games.any { it.system == system } }.map(::Console)
            return buildList {
                addAll(consoles)
                if (consoles.size != 1) add(All)
                if (games.any { it.favorite }) add(Favorites)
                if (games.any { it.lastPlayed > 0 }) add(Recent)
            }
        }
        fun parse(key: String?): Shelf? = when (key) {
            null -> null
            All.key -> All
            Favorites.key -> Favorites
            Recent.key -> Recent
            else -> GameSystem.entries.firstOrNull { it.name == key }?.let(::Console)
        }
    }
}
