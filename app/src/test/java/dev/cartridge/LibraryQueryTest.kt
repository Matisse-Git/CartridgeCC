package dev.cartridge

import dev.cartridge.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryQueryTest {
    private val games = listOf(
        LibraryGame("a".repeat(64), "Zulu", "one", favorite = true, playMs = 10, lastPlayed = 100),
        LibraryGame("b".repeat(64), "alpha", "two", favorite = true, playMs = 30, lastPlayed = 200),
        LibraryGame("c".repeat(64), "Alpha", "three", playMs = 30, lastPlayed = 0))
    @Test fun stableSortDoesNotChangeCanonicalOrderOrCollapseSameTitles() {
        assertEquals(listOf("b", "c", "a"), LibraryQuery(LibrarySort.Title).apply(games).map { it.id.take(1) })
        assertEquals(listOf("b", "c", "a"), LibraryQuery(LibrarySort.Playtime).apply(games).map { it.id.take(1) })
        assertEquals(listOf("b", "a", "c"), LibraryQuery(LibrarySort.Recent).apply(games).map { it.id.take(1) })
        assertEquals(games, LibraryQuery().apply(games))
    }
    @Test fun favoriteAndMultiwordSearchAreIntersectionAndCaseIndependent() {
        val enriched = games.map { it.copy(metadata = mapOf("genre" to MetadataValue("Puzzle", "Local", "", "", "", 0))) }
        assertEquals(listOf(games[1].id), LibraryQuery(favoritesOnly = true, search = " ALPHA   puzzle ").apply(enriched).map { it.id })
        assertTrue(LibraryQuery(search = "missing").apply(games).isEmpty())
    }
    @Test fun runningClockExcludesPauseAndRetargetsCheckpoints() {
        val clock = PlayClock()
        assertEquals(0L, clock.checkpoint(100, false))
        clock.running(1000); clock.running(1100)
        assertEquals(500L, clock.checkpoint(1500, true))
        assertEquals(200L, clock.checkpoint(1700, false))
        assertEquals(0L, clock.checkpoint(9000, false))
        clock.running(10_000)
        assertEquals(300L, clock.checkpoint(10_300, false))
    }
}
