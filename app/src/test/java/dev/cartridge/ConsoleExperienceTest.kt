package dev.cartridge

import dev.cartridge.domain.ConsoleCatalog
import dev.cartridge.domain.FolderHint
import dev.cartridge.domain.FoundFile
import dev.cartridge.domain.GameSystem
import dev.cartridge.environment.CoreFamily
import dev.cartridge.environment.GameOptions
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.Shelf
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.importHeadline
import dev.cartridge.shell.importNotes
import dev.cartridge.storage.ScanResult
import org.junit.Assert.*
import org.junit.Test

class ConsoleExperienceTest {
    private fun game(index: Int, system: GameSystem = GameSystem.GBA, favorite: Boolean = false, lastPlayed: Long = 0) =
        LibraryGame(index.toString(16).padStart(64, '0'), "Game $index", "content://$index", favorite = favorite, lastPlayed = lastPlayed, system = system)

    @Test fun shelvesListOnlyConsolesWithGamesAndUnifiedViewsThatAddSomething() {
        assertEquals(listOf(Shelf.All), Shelf.available(emptyList()))
        // One console: "All Games" would repeat it, and nothing is a favourite or played yet.
        assertEquals(listOf(Shelf.Console(GameSystem.GBA)), Shelf.available(listOf(game(1), game(2))))
        val mixed = listOf(game(1, GameSystem.PS1, lastPlayed = 30), game(2, favorite = true), game(3, GameSystem.GBC, lastPlayed = 90))
        assertEquals(listOf(Shelf.Console(GameSystem.GBA), Shelf.Console(GameSystem.GBC), Shelf.Console(GameSystem.PS1), Shelf.All, Shelf.Favorites, Shelf.Recent),
            Shelf.available(mixed))
        assertEquals(listOf(mixed[2], mixed[0]), Shelf.Recent.apply(mixed)) // Most recent first, unplayed games absent.
        assertEquals(listOf(mixed[1]), Shelf.Favorites.apply(mixed))
        assertEquals(mixed, Shelf.All.apply(mixed)) // Stored order is never rewritten.
        Shelf.available(mixed).forEach { assertEquals(it, Shelf.parse(it.key)) }
        assertNull(Shelf.parse("N64")); assertNull(Shelf.parse(null))
        assertEquals(Shelf.RECENT_LIMIT, Shelf.Recent.apply(List(40) { game(it + 10, lastPlayed = it + 1L) }).size)
    }

    @Test fun folderNamesExcludeAnotherConsolesDiscsButNeverCartridges() {
        assertEquals(FolderHint.Supported(GameSystem.GBA), ConsoleCatalog.folderHint("Game Boy Advance"))
        assertEquals(FolderHint.Supported(GameSystem.PS1), ConsoleCatalog.folderHint("psx"))
        assertEquals(FolderHint.Other("PlayStation 2"), ConsoleCatalog.folderHint("PS2"))
        assertNull(ConsoleCatalog.folderHint("My Games"))
        // Cartridge formats identify themselves; the folder cannot veto them.
        assertEquals(FoundFile.Game(GameSystem.GBA), ConsoleCatalog.classify("Orbit.GBA", FolderHint.Other("Sega Saturn")))
        assertEquals(FoundFile.Game(GameSystem.GBC), ConsoleCatalog.classify("orbit.gbc"))
        // Disc containers are shared by many consoles, so a folder named for another one wins.
        assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("orbit.chd"))
        assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("orbit.cue", FolderHint.Supported(GameSystem.PS1)))
        assertEquals(FoundFile.OtherConsole("Dreamcast"), ConsoleCatalog.classify("orbit.chd", FolderHint.Other("Dreamcast")))
        assertEquals(FoundFile.OtherConsole("Super Nintendo"), ConsoleCatalog.classify("orbit.sfc"))
        assertEquals(FoundFile.Archive, ConsoleCatalog.classify("orbit.zip"))
        assertEquals(FoundFile.Playlist, ConsoleCatalog.classify("orbit.m3u"))
        // A BIN is a track of its CUE, never a game of its own.
        assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit (track 2).bin")); assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("notes.txt"))
    }

    @Test fun startFromSaveStateIsOneChoiceForEveryConsoleThatAGameCanOverride() {
        val start = GameOptions.all.single { it.id == "startFromState" }
        assertNull(start.family); assertNull(start.variable); assertFalse(start.live)
        // Off unless asked for, on every console, under the same stored id.
        for (system in GameSystem.entries) assertEquals("disabled", GameOptions.resolve(system, emptyMap()).getValue(start))
        for (family in CoreFamily.entries) assertTrue(start in GameOptions.forFamily(family))
        assertEquals("enabled", GameOptions.resolve(GameSystem.PS1, mapOf(start.id to "enabled")).getValue(start))
        assertEquals("disabled", GameOptions.resolve(GameSystem.GBA, mapOf(start.id to "enabled"), mapOf(start.id to "disabled")).getValue(start))
        // It reaches the adapter under its own reserved key and is never offered to a core or to a paused game.
        val session = GameOptions.session(GameOptions.resolve(GameSystem.GBC, mapOf(start.id to "enabled")))
        assertEquals("enabled", session[GameOptions.START_FROM_STATE]); assertEquals("standard", session[GameOptions.SCREEN_EFFECT])
        assertEquals(setOf("mgba_audio_low_pass_filter", "mgba_frameskip"), session.keys - GameOptions.adapterKeys)
        assertFalse(GameOptions.START_FROM_STATE in GameOptions.session(GameOptions.resolve(GameSystem.GBC, mapOf(start.id to "enabled")), liveOnly = true))
    }
    @Test fun gameChoicesOverrideConsoleChoicesAndUnknownStoredValuesFallBack() {
        val sharp = GameOptions.all.single { it.id == "playstation.sharp3d" }
        val effect = GameOptions.all.single { it.id == "playstation.effect" }
        assertEquals(CoreFamily.PlayStation, CoreFamily.of(GameSystem.PS1)); assertEquals(CoreFamily.Handheld, CoreFamily.of(GameSystem.GBC))
        val defaults = GameOptions.resolve(GameSystem.PS1, emptyMap())
        assertEquals("disabled", defaults[sharp]); assertEquals("standard", defaults[effect])
        val console = mapOf(sharp.id to "enabled", effect.id to "crt")
        assertEquals("enabled", GameOptions.resolve(GameSystem.PS1, console)[sharp])
        val resolved = GameOptions.resolve(GameSystem.PS1, console, mapOf(sharp.id to "disabled", effect.id to "not-a-choice"))
        assertEquals("disabled", resolved[sharp]); assertEquals("crt", resolved[effect])
        // Core keys and the adapter's own screen-effect key; nothing from another console leaks in.
        val session = GameOptions.session(resolved)
        assertEquals("disabled", session["pcsx_rearmed_neon_enhancement_enable"]); assertEquals("crt", session[GameOptions.SCREEN_EFFECT])
        assertTrue(session.keys.none { it.startsWith("mgba_") })
        assertEquals(setOf(GameOptions.SCREEN_EFFECT), GameOptions.session(resolved, liveOnly = true).keys) // PS1 core options wait for the next start.
        assertEquals(mapOf("mgba_audio_low_pass_filter" to "disabled", "mgba_frameskip" to "0", GameOptions.SCREEN_EFFECT to "standard"),
            GameOptions.session(GameOptions.resolve(GameSystem.GB, emptyMap()), liveOnly = true))
        assertEquals("standard", effect.next("crt")); assertEquals("lcd", effect.next("crt", -1)); assertEquals("Television", effect.title("crt"))
    }

    @Test fun importSummariesAreCountedFromWhatWasReallyListed() {
        val one = ScanResult(12, 0, 0, 12, emptyList(), addedSystems = mapOf(GameSystem.PS1 to 12))
        assertEquals("12 games added to PlayStation", importHeadline(one)); assertTrue(importNotes(one).isEmpty())
        val two = one + ScanResult(1, 3, 2, 9, listOf("bad.gba: The selected ROM is incomplete"), addedSystems = mapOf(GameSystem.GBA to 1),
            knownSystems = mapOf(GameSystem.GBA to 3), otherConsoles = mapOf("Nintendo 64" to 4), archives = 1, playlists = 1, firstAdded = "f".repeat(64))
        assertEquals("13 games added across 2 consoles", importHeadline(two))
        assertEquals(3, two.duplicates); assertEquals("f".repeat(64), two.firstAdded); assertEquals(21, two.visited)
        val notes = importNotes(two)
        assertTrue(notes[0].startsWith("2 files could not be added")); assertTrue(notes[0].contains("bad.gba"))
        assertTrue(notes.any { it.contains("Nintendo 64") }); assertTrue(notes.any { it.contains(".zip") }); assertTrue(notes.any { it.contains(".m3u") })
        assertEquals("Your library is up to date", importHeadline(ScanResult(0, 7, 0, 7, emptyList())))
        assertEquals("No games found here", importHeadline(ScanResult(0, 0, 0, 3, emptyList())))
        assertEquals("1 game added to Game Boy", importHeadline(ScanResult(1, 0, 0, 1, emptyList(), addedSystems = mapOf(GameSystem.GB to 1))))
        // The original status line still reports the same totals for anything that reads it.
        assertTrue(two.message.startsWith("Scan complete: 13 added · 3 duplicates · 2 failed"))
    }

    @Test fun storageFailuresAreExplainedInWordsAPlayerCanActOn() {
        assertTrue(ConsoleShell.plain("Permission Denial: reading com.android.externalstorage requires that you obtain access").contains("Choose it again"))
        assertTrue(ConsoleShell.plain("java.io.FileNotFoundException: open failed: ENOENT (No such file or directory)").contains("SD card"))
        assertTrue(ConsoleShell.plain("write failed: ENOSPC (No space left on device)").contains("out of storage"))
        assertEquals("Choose a PNG, JPEG or WebP label image", ConsoleShell.plain("Choose a PNG, JPEG or WebP label image"))
        assertTrue(ConsoleShell.plain(null).isNotBlank())
    }
}
