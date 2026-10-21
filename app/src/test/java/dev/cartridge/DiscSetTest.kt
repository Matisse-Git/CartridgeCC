package dev.cartridge

import dev.cartridge.domain.DiscFile
import dev.cartridge.domain.DiscNames
import dev.cartridge.domain.DiscSets
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Playlist
import org.junit.Assert.*
import org.junit.Test

/** ADR 0032: which files are the discs of one game, and what the game is called. */
class DiscSetTest {
    private fun id(n: Int) = n.toString().padStart(64, '0')
    private fun disc(n: Int, name: String, system: GameSystem = GameSystem.PS1) = DiscFile(id(n), name, system)

    @Test fun discNumbersAndTitlesComeFromTheName() {
        assertEquals(2, DiscNames.number("Orbit (Japan, Asia) (Disc 2).cue"))
        assertEquals(3, DiscNames.number("Orbit (USA) (Disc 3 of 4) (Rev 1).chd"))
        assertEquals(1, DiscNames.number("Orbit (CD1).cue"))
        assertNull(DiscNames.number("Orbit (USA).cue"))
        assertNull("Eight discs at most", DiscNames.number("Orbit (Disc 12).cue"))
        assertEquals("Orbit (USA) (Rev 1)", DiscNames.base("Orbit (USA) (Disc 3 of 4) (Rev 1).chd"))
        assertEquals("Orbit (Japan, Asia)", DiscNames.title("Orbit (Japan, Asia) (Disc 1)"))
        assertEquals("A title without a tag is kept", "Orbit", DiscNames.title("Orbit"))
    }

    @Test fun playlistsReadAnyLineEndingAndOnlyNamesInTheirOwnFolder() {
        val text = "Orbit (Disc 1).cue\r\r\nOrbit (Disc 2).cue\r\n# comment\n\n../elsewhere/Orbit (Disc 3).cue\nsub\\Orbit (Disc 4).cue\nC:Orbit.cue\n  Orbit (Disc 5).cue  "
        assertEquals(listOf("Orbit (Disc 1).cue", "Orbit (Disc 2).cue", "Orbit (Disc 5).cue"), Playlist.entries(text))
        assertEquals("/a/1.cue\n/a/2.cue\n", Playlist.write(listOf("/a/1.cue", "/a/2.cue")))
        assertThrows(IllegalArgumentException::class.java) { Playlist.write(listOf("relative.cue")) }
        assertThrows(IllegalArgumentException::class.java) { Playlist.write(listOf("/a\n/b.cue")) }
    }

    @Test fun aPlaylistNamedOtherwiseGroupsTheDiscsItNamesInItsOrder() {
        val files = (1..4).map { disc(it, "Orbit (Japan, Asia) (Disc $it).cue") } + disc(9, "Other.cue")
        val text = (1..4).joinToString("\r\r\n") { "Orbit (Japan, Asia) (Disc $it).cue" }
        val group = DiscSets.group(files, mapOf("Orbit.m3u" to text)).single()
        assertEquals((1..4).map(::id), group.ids); assertEquals(listOf(1, 2, 3, 4), group.numbers); assertEquals("Orbit.m3u", group.playlist)
    }

    @Test fun withoutAPlaylistDiscsAreGroupedByNameOnly() {
        val files = listOf(disc(2, "Gears (Japan) (Disc 2).cue"), disc(1, "Gears (Japan) (Disc 1).cue"), disc(3, "Gears (USA) (Disc 1).cue"),
            disc(4, "Gears (USA) (Disc 2).cue"), disc(5, "Lonely (Disc 1).cue"), disc(6, "Plain.cue"))
        val groups = DiscSets.group(files, emptyMap()).sortedBy { it.ids.first() }
        assertEquals(listOf(listOf(id(1), id(2)), listOf(id(3), id(4))), groups.map { it.ids })
        assertTrue(groups.all { it.playlist == null })
    }

    @Test fun aSetThatSkipsADiscStillGroupsAndSaysWhichIsMissing() {
        val group = DiscSets.group(listOf(disc(1, "Saga (Disc 1).cue"), disc(3, "Saga (Disc 3).cue")), emptyMap()).single()
        assertEquals(listOf(1, 3), group.numbers)
        assertEquals(listOf(2), DiscSets.missing(group.numbers))
    }

    @Test fun ambiguousMixedOrDuplicateSetsStaySeparate() {
        // Two files claim disc 1: nothing says which is the game's.
        assertTrue(DiscSets.group(listOf(disc(1, "Saga (Disc 1).cue"), disc(2, "Saga (Disc 1).chd"), disc(3, "Saga (Disc 2).cue")), emptyMap()).isEmpty())
        // Discs of two consoles are never one game.
        assertTrue(DiscSets.group(listOf(disc(1, "Saga (Disc 1).cue"), disc(2, "Saga (Disc 2).cue", GameSystem.SATURN)), emptyMap()).isEmpty())
        // Identical content under two names is one disc, not two.
        assertTrue(DiscSets.group(listOf(disc(1, "Saga (Disc 1).cue"), DiscFile(id(1), "Saga (Disc 2).cue", GameSystem.PS1)), emptyMap()).isEmpty())
        // A playlist that finds fewer than two of its discs groups nothing; the names still can.
        val names = DiscSets.group(listOf(disc(1, "Saga (Disc 1).cue"), disc(2, "Saga (Disc 2).cue")), mapOf("Saga.m3u" to "Missing (Disc 1).cue\nSaga (Disc 1).cue"))
        assertEquals(null, names.single().playlist)
    }

    @Test fun aPlaylistClaimsItsDiscsBeforeTheNamesDo() {
        val files = listOf(disc(1, "a.cue"), disc(2, "b.cue"), disc(3, "Saga (Disc 1).cue"), disc(4, "Saga (Disc 2).cue"))
        val groups = DiscSets.group(files, mapOf("Saga.m3u" to "b.cue\na.cue"))
        assertEquals(listOf(id(2), id(1)), groups.single { it.playlist != null }.ids)
        assertEquals(listOf(id(3), id(4)), groups.single { it.playlist == null }.ids)
    }
}
