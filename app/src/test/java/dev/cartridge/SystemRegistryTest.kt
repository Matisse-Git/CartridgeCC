package dev.cartridge

import dev.cartridge.domain.releasedInNorthAmerica
import dev.cartridge.domain.Claim
import dev.cartridge.domain.ConsoleCatalog
import dev.cartridge.domain.Core
import dev.cartridge.domain.DiscOwner
import dev.cartridge.domain.FolderHint
import dev.cartridge.domain.FoundFile
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
import dev.cartridge.environment.GameOptions
import dev.cartridge.library.LabelRef
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.Shelf
import dev.cartridge.storage.GameFiles
import dev.cartridge.storage.ManualState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The console registry: one definition per console, consumed by scanning, storage, options, artwork and the shelf. */
class SystemRegistryTest {
    private fun header(size: Int = 512, write: ByteArray.() -> Unit = {}) = ByteArray(size).apply(write)
    private fun ByteArray.put(offset: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(this, offset)

    @Test fun storedIdentitiesAndTheirOrderNeverChangeAndEveryDefinitionIsComplete() {
        // Room, backups, journals and the stored shelf hold these names; a library made before this registry reads back unchanged.
        assertEquals(listOf("GBA", "GB", "GBC", "PS1"), GameSystem.entries.take(4).map { it.name })
        assertEquals(listOf(Media.Cartridge, Media.TallCartridge, Media.TallCartridge, Media.Disc), GameSystem.entries.take(4).map { it.media })
        assertEquals(listOf(256, 448, 448, 512), GameSystem.entries.take(4).map { it.media.labelHeight })
        assertEquals("mgba-0.10.5-cartridge-memory-2", GameSystem.GBC.coreRevision); assertEquals("pcsx-c8816799-cartridge-1", GameSystem.PS1.coreRevision)
        assertEquals("libmgba_libretro.so", GameSystem.GB.coreFile); assertEquals("libpcsx_rearmed_libretro.so", GameSystem.PS1.coreFile)
        for (system in GameSystem.entries) {
            assertTrue(system.name, system.title.isNotBlank() && system.maker.isNotBlank() && system.generation in 1..9)
            assertTrue(system.name, system.formats.isNotEmpty() && system.folders.isNotEmpty() && system.maxBytes > 0)
            assertTrue(system.name, system.folders.all { it == it.lowercase() && it.all(Char::isLetterOrDigit) })
            assertEquals(system.name, system.formats.size, system.formats.map { it.extension }.toSet().size)
            assertEquals(system.name, Shelf.Console(system), Shelf.parse(system.name))
        }
        // No folder name, private extension or library file means two things.
        assertEquals(GameSystem.entries.sumOf { it.folders.size }, GameSystem.entries.flatMap { it.folders }.toSet().size)
        assertEquals(GameSystem.entries.size, GameSystem.entries.map { it.extension }.toSet().size)
        assertEquals(Core.entries.size, Core.entries.map { it.file }.toSet().size)
        assertEquals(Core.entries.size, Core.entries.map { it.revision }.toSet().size)
        assertTrue(Core.entries.all { core -> GameSystem.entries.any { it.core == core } })
        // A console is shown as the owner's model of its cartridge where there is one, and as a disc until then; that
        // says nothing about how its games are stored.
        val shapes = mapOf(GameSystem.SNES to Media.SnesCartridge, GameSystem.NES to Media.NesCartridge, GameSystem.GENESIS to Media.GenesisCartridge)
        for (system in GameSystem.entries.drop(4)) assertEquals(system.name, shapes[system] ?: Media.Disc, system.media)
        assertFalse(GameSystem.SNES.bundle); assertTrue(GameSystem.PS1.bundle)
        // The pool, the packaged files and the on-screen sizes follow this order and these names.
        assertEquals(listOf("Cartridge", "TallCartridge", "Disc", "NesCartridge", "SnesCartridge", "SnesUsCartridge", "GenesisCartridge"), Media.entries.map { it.name })
        assertEquals(Media.entries.size, Media.entries.map { it.model }.toSet().size)
        assertTrue(Media.entries.all { it.labelHeight in 128..1024 && it.scale > 0f && it.model.matches(Regex("[a-z0-9_]+")) })
    }

    @Test fun aPlayStation2BiosIsAcceptedByItsOwnFileTableWhateverItsRegionAndNothingElseIs() {
        // An original stand-in for the shape of a dump, not a BIOS: a table that begins with RESET, sixteen bytes a
        // row, and the fourteen characters of ROMVER where the table says that file is.
        fun dump(version: String = "0200EC20040614", table: Int = 0x2000, reset: String = "RESET", size: Int = 4 * 1024 * 1024) = ByteArray(size).also { bytes ->
            fun row(index: Int, name: String, length: Int) {
                name.toByteArray(Charsets.US_ASCII).copyInto(bytes, table + index * 16)
                for (n in 0..3) bytes[table + index * 16 + 12 + n] = (length shr (8 * n)).toByte()
            }
            // RESET is 0x2740 bytes and ROMDIR 0x45 (so the next file starts on the following sixteen); ROMVER comes third.
            row(0, reset, 0x2740); row(1, "ROMDIR", 0x45); row(2, "ROMVER", 16); row(3, "SBIN", 0x1000)
            version.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x2740 + 0x50)
        }
        val any = "0".repeat(32)
        assertEquals("pcsx2/bios/ps2-bios.bin", Core.Armsx2.firmware(dump(), any)!!.file)
        for (version in listOf("0100JC20000117", "0160AC20020207", "0160EC20011004", "0230HC20080220", "0220CD20060905"))
            assertNotNull(version, Core.Armsx2.firmware(dump(version), any))
        // Not one: another size, no table, a table that names no version, a version that is not one.
        assertNull(Core.Armsx2.firmware(dump(size = 512 * 1024), any)); assertNull(Core.Armsx2.firmware(ByteArray(4 * 1024 * 1024), any))
        assertNull(Core.Armsx2.firmware(dump(reset = "RESAT"), any)); assertNull(Core.Armsx2.firmware(dump("PlayStation 2!!"), any))
        assertNull(Core.Armsx2.firmware(dump(table = 600 * 1024), any)) // The engine looks in the first 512 KiB only.
        // And it is no other console's file, nor theirs its.
        assertNull(Core.Pcsx.firmware(dump(), any)); assertNull(Core.BeetleSaturn.firmware(dump(), any))
        assertNull(Core.Armsx2.firmware(ByteArray(512 * 1024), "8dd7d5296a650fac7319bce665a6a53c"))
        assertTrue(Core.Armsx2.firmwareRequired); assertEquals(GameSystem.PS2.core, Core.Armsx2)
        // A CUE in this console's folder is still its game: the engine is started from the sheet's first track.
        assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.cue", ConsoleCatalog.folderHint("PS2")))
        assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit.isz", ConsoleCatalog.folderHint("PS2")))
    }

    @Test fun aCartridgeTakesItsMarketsShapeFromTheGamesNameAndNothingElseDoes() {
        // Only the Super Nintendo's cartridges differed, and only the name of a dump says where it was sold.
        assertEquals(listOf(GameSystem.SNES), GameSystem.entries.filter { it.northAmerican != null })
        for (title in listOf("Orbit Field (USA)", "Orbit Field (USA, Europe)", "Orbit Field (Japan, USA) (Rev 1)", "Orbit Field (U) [!]", "Orbit Field [U]",
            "Orbit Field (JU)", "Orbit Field (UE)", "Orbit Field (US)", "Orbit Field (NTSC-U)", "Orbit Field (North America)")) {
            assertTrue(title, releasedInNorthAmerica(title)); assertEquals(title, Media.SnesUsCartridge, GameSystem.SNES.media(title))
        }
        for (title in listOf("Orbit Field", "Orbit Field (Japan)", "Orbit Field (Europe) (En,Fr,De)", "Orbit Field (E)", "Orbit Field (J)", "Orbit Field (World)",
            "Orbit Field (Unl)", "USA Orbit Field", "Us and Them", "Orbit Field (Australia)", "Orbit Field (UK)", "U")) {
            assertFalse(title, releasedInNorthAmerica(title)); assertEquals(title, Media.SnesCartridge, GameSystem.SNES.media(title))
        }
        // A name moves no other console's object, and never a game to another console or core.
        for (system in GameSystem.entries.filter { it != GameSystem.SNES }) assertEquals(system.name, system.media, system.media("Orbit Field (USA)"))
        val game = LibraryGame("a".repeat(64), "Orbit Field (USA)", "content://dev.cartridge.fixture/orbit.sfc", system = GameSystem.SNES)
        assertEquals(Media.SnesUsCartridge, game.shownAs); assertEquals(Media.SnesUsCartridge, game.copy(title = "Orbit Field").shownAs); // A scraped title keeps the file's market. assertEquals(Media.SnesCartridge, game.copy(fileName = "Orbit Field").shownAs)
        assertEquals(Media.Cartridge, game.copy(system = GameSystem.GBA).shownAs)
    }

    @Test fun aFileIsItsConsolesGameByItsFirstBytesNotOnlyItsName() {
        assertEquals(GameSystem.SNES, GameSystem.detect("Orbit.SFC", header()))
        assertEquals(GameSystem.SNES, GameSystem.detect("orbit.smc", header()))
        assertEquals(GameSystem.NES, GameSystem.detect("orbit.nes", header { put(0, "NES"); this[3] = 0x1A }))
        assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.md", header { put(0x100, "SEGA GENESIS") }))
        assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.gen", header { put(0x100, "SEGA MEGA DRIVE") }))
        assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.md", header { put(0x101, "SEGA") })) // A few licensed headers start one byte late.
        assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.smd", header { this[8] = 0xAA.toByte(); this[9] = 0xBB.toByte() }))
        assertEquals(GameSystem.SMS, GameSystem.detect("orbit.sms", header())); assertEquals(GameSystem.GG, GameSystem.detect("orbit.gg", header()))
        // An extension that can only be Genesis needs no header: unlicensed cartridges do not carry Sega's name.
        assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.gen", header())); assertEquals(GameSystem.GENESIS, GameSystem.detect("orbit.smd", header()))
        for ((name, bytes) in listOf("notes.md" to "# Notes\n".toByteArray() + ByteArray(600), "orbit.nes" to header(), "orbit.sfc" to header(300),
            "orbit.gen" to header(100), "orbit.md" to header(), "orbit.unf" to header(), "orbit.sms" to header(100), "orbit.n64" to header())) {
            try { GameSystem.detect(name, bytes); fail("Accepted $name") } catch (_: IllegalArgumentException) { }
        }
        // The refusal is the named console's own, in words the import result can show.
        assertEquals("Not an iNES game", runCatching { GameSystem.detect("orbit.nes", header()) }.exceptionOrNull()!!.message)
        assertEquals("The Super Nintendo file is too small", runCatching { GameSystem.detect("orbit.sfc", header(300)) }.exceptionOrNull()!!.message)
        assertEquals("The GB/GBC header is incomplete", runCatching { GameSystem.detect("orbit.gb", header(100)) }.exceptionOrNull()!!.message)
        assertTrue(GameSystem.supports("orbit.SMC")); assertFalse(GameSystem.supports("orbit.sfc.zip")); assertFalse(GameSystem.supports("orbit"))
        // A copy is bounded before the console is known, and by the console's own limit after.
        assertEquals(32L shl 20, GameSystem.limit("orbit.gba")); assertEquals(GameSystem.SNES.maxBytes, GameSystem.limit("orbit.sfc")); assertEquals(0L, GameSystem.limit("orbit.txt"))
    }

    @Test fun commonExtensionsAreClaimedOnlyWhereTheFolderNamesTheConsole() {
        val genesis = ConsoleCatalog.folderHint("Mega Drive"); val playstation = ConsoleCatalog.folderHint("psx")
        assertEquals(FolderHint.Supported(GameSystem.GENESIS), genesis); assertEquals(FolderHint.Supported(GameSystem.SNES), ConsoleCatalog.folderHint("Super Famicom"))
        assertEquals(FolderHint.Supported(GameSystem.NES), ConsoleCatalog.folderHint("NES")); assertEquals(FolderHint.Supported(GameSystem.SMS), ConsoleCatalog.folderHint("Master System"))
        assertEquals(FolderHint.Supported(GameSystem.GG), ConsoleCatalog.folderHint("gamegear"))
        // Consoles that are recognised but cannot be played are still named, never imported.
        for (name in listOf("Wii", "PSP", "Nintendo 64", "Sega CD")) assertTrue(name, ConsoleCatalog.folderHint(name) is FolderHint.Other)
        assertEquals(FolderHint.Supported(GameSystem.DREAMCAST), ConsoleCatalog.folderHint("Sega Dreamcast"))
        assertEquals(FolderHint.Supported(GameSystem.SATURN), ConsoleCatalog.folderHint("Sega Saturn"))
        // A cartridge's own extension is its console's game anywhere.
        assertEquals(FoundFile.Game(GameSystem.SNES), ConsoleCatalog.classify("orbit.sfc")); assertTrue(ConsoleCatalog.claims("orbit.sfc"))
        assertEquals(FoundFile.Game(GameSystem.NES), ConsoleCatalog.classify("orbit.nes", FolderHint.Other("Dreamcast")))
        assertEquals(FoundFile.Game(GameSystem.GG), ConsoleCatalog.classify("orbit.gg")); assertEquals(FoundFile.Game(GameSystem.SMS), ConsoleCatalog.classify("orbit.sms", genesis))
        // .md is also a text file and .exe a Windows program: tried quietly, announced only in the console's folder.
        assertEquals(FoundFile.Game(GameSystem.GENESIS), ConsoleCatalog.classify("README.md")); assertFalse(ConsoleCatalog.claims("README.md"))
        assertTrue(ConsoleCatalog.claims("orbit.md", genesis)); assertTrue(ConsoleCatalog.claims("orbit.gen"))
        assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("setup.exe")); assertFalse(ConsoleCatalog.claims("setup.exe")); assertTrue(ConsoleCatalog.claims("orbit.exe", playstation))
        // .bin is a disc track nearly everywhere: a Genesis game only in a Genesis folder, and never in a PlayStation one.
        assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit.bin")); assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit (track 2).bin", playstation))
        assertEquals(FoundFile.Game(GameSystem.GENESIS), ConsoleCatalog.classify("orbit.bin", genesis)); assertTrue(ConsoleCatalog.claims("orbit.bin", genesis))
        assertFalse(ConsoleCatalog.claims("orbit.bin")); assertFalse(ConsoleCatalog.claims("notes.txt")); assertFalse(ConsoleCatalog.claims("orbit.zip"))
        assertEquals(Claim.Folder, GameSystem.GENESIS.format("bin")!!.claim); assertEquals(Claim.Content, GameSystem.PS1.format("exe")!!.claim)
        // A disc container still belongs to the console its folder names, and to PlayStation where nothing says otherwise.
        assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("orbit.chd")); assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("orbit.cue", genesis))
        assertEquals(FoundFile.OtherConsole("Wii"), ConsoleCatalog.classify("orbit.iso", ConsoleCatalog.folderHint("wii")))
        val saturn = ConsoleCatalog.folderHint("saturn")
        assertEquals(FoundFile.Game(GameSystem.SATURN), ConsoleCatalog.classify("orbit.cue", saturn)); assertEquals(FoundFile.Game(GameSystem.SATURN), ConsoleCatalog.classify("orbit.chd", saturn))
        // Saturn has no single-file image of its own, so an ISO there is still not a Saturn game.
        assertEquals(FoundFile.Game(GameSystem.PS1), ConsoleCatalog.classify("orbit.iso", saturn))
        val chd = header { put(0, "MComprHD") }
        assertEquals(GameSystem.PS1, GameSystem.detect("orbit.chd", chd)); assertEquals(GameSystem.SATURN, GameSystem.detect("orbit.chd", chd, GameSystem.SATURN))
        assertEquals(GameSystem.PS1, GameSystem.detect("orbit.chd", chd, GameSystem.GENESIS)) // A folder for a console that has no such format settles nothing.
        assertTrue(GameSystem.sheet("Orbit.CUE")); assertTrue(GameSystem.sheet("orbit.gdi")); assertFalse(GameSystem.sheet("orbit.chd"))
        // Dreamcast: its own sheet and image extensions anywhere, shared containers and bare programs only in its folder.
        val dreamcast = ConsoleCatalog.folderHint("dreamcast")
        assertEquals(FoundFile.Game(GameSystem.DREAMCAST), ConsoleCatalog.classify("orbit.gdi")); assertEquals(FoundFile.Game(GameSystem.DREAMCAST), ConsoleCatalog.classify("orbit.cdi"))
        assertEquals(FoundFile.Game(GameSystem.DREAMCAST), ConsoleCatalog.classify("orbit.chd", dreamcast)); assertEquals(FoundFile.Game(GameSystem.DREAMCAST), ConsoleCatalog.classify("orbit.cue", dreamcast))
        assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit.elf")); assertEquals(FoundFile.Game(GameSystem.DREAMCAST), ConsoleCatalog.classify("orbit.elf", dreamcast))
        assertEquals(GameSystem.DREAMCAST, GameSystem.detect("orbit.chd", chd, GameSystem.DREAMCAST))
        val program = header { put(1, "ELF"); this[0] = 0x7F; this[18] = 42 }
        assertEquals(GameSystem.DREAMCAST, GameSystem.detect("orbit.elf", program))
        assertThrows(IllegalArgumentException::class.java) { GameSystem.detect("orbit.elf", header { put(1, "ELF"); this[0] = 0x7F; this[18] = 62 }) } // Another processor's program.
    }

    @Test fun aLargeDiscIsItsConsolesByWhatItSaysAndHowMuchItHolds() {
        fun ByteArray.word(at: Int, value: Long) { for (n in 0..3) this[at + n] = (value shr (24 - 8 * n)).toByte() }
        val plain = header(); val cube = header { word(0x1C, 0xC2339F3D) }; val wii = header { word(0x18, 0x5D1C9EA3) }
        fun chd(unit: Long, length: Long) = header { put(0, "MComprHD"); word(12, 5); word(32, length shr 32); word(36, length and 0xFFFFFFFF); word(60, unit) }
        val dvd = 4L shl 30; val cd = 600L shl 20
        // A GameCube disc says so, whatever its name or folder.
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.iso", cube)); assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.iso", cube, GameSystem.PS1, 1_459_978_240))
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.gcm", cube)); assertThrows(IllegalArgumentException::class.java) { GameSystem.detect("orbit.gcm", plain) }
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.rvz", header { put(0, "RVZ"); this[3] = 1; word(0x48, 1) }))
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.gcz", header { word(0, 0x01C00BB1) }))
        // A PlayStation 2 disc says nothing in its first bytes: a DVD is one by its size, a CD only by its folder.
        assertEquals(GameSystem.PS2, GameSystem.detect("orbit.iso", plain, size = dvd)); assertEquals(GameSystem.PS2, GameSystem.detect("orbit.iso", plain, GameSystem.PS1, dvd))
        assertEquals(GameSystem.PS1, GameSystem.detect("orbit.iso", plain, size = cd)); assertEquals(GameSystem.PS1, GameSystem.detect("orbit.iso", plain))
        assertEquals(GameSystem.PS2, GameSystem.detect("orbit.iso", plain, GameSystem.PS2, cd)); assertEquals(GameSystem.PS2, GameSystem.detect("orbit.iso", plain, GameSystem.PS2))
        // A CHD names the unit it packs: a DVD's, or a CD's that is longer than any CD.
        assertEquals(GameSystem.PS2, GameSystem.detect("orbit.chd", chd(2048, dvd))); assertEquals(GameSystem.PS2, GameSystem.detect("orbit.chd", chd(2048, dvd), GameSystem.SATURN))
        assertEquals(GameSystem.DREAMCAST, GameSystem.detect("orbit.chd", chd(2448, 1200L shl 20))); assertEquals(GameSystem.PS1, GameSystem.detect("orbit.chd", chd(2448, cd)))
        assertEquals(GameSystem.SATURN, GameSystem.detect("orbit.chd", chd(2448, cd), GameSystem.SATURN)); assertEquals(GameSystem.PS2, GameSystem.detect("orbit.chd", chd(2448, cd), GameSystem.PS2))
        // The sibling console that cannot be played is named, never listed as something else.
        for ((name, bytes) in listOf("orbit.iso" to wii, "orbit.rvz" to header { put(0, "RVZ"); this[3] = 1; word(0x48, 2) }, "orbit.gcz" to header { word(0, 0x01C00BB1); this[4] = 1 }))
            assertEquals(name, "This is a Wii disc, which Cartridge can't play yet", runCatching { GameSystem.detect(name, bytes, size = dvd) }.exceptionOrNull()!!.message)
        // Bare programs name their processor, in either byte order; a GameCube one may only name where it starts.
        assertEquals(GameSystem.PS2, GameSystem.detect("orbit.elf", header { put(1, "ELF"); this[0] = 0x7F; this[5] = 1; this[18] = 8 }))
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.elf", header { put(1, "ELF"); this[0] = 0x7F; this[5] = 2; this[19] = 20 }))
        assertEquals(GameSystem.GAMECUBE, GameSystem.detect("orbit.dol", header { word(0xE0, 0x80003100) })); assertThrows(IllegalArgumentException::class.java) { GameSystem.detect("orbit.dol", plain) }
        // Folders and names.
        val ps2 = ConsoleCatalog.folderHint("PS2"); val gamecube = ConsoleCatalog.folderHint("GameCube")
        assertEquals(FolderHint.Supported(GameSystem.PS2), ps2); assertEquals(FolderHint.Supported(GameSystem.GAMECUBE), gamecube); assertEquals(gamecube, ConsoleCatalog.folderHint("gc"))
        assertEquals(FoundFile.Game(GameSystem.GAMECUBE), ConsoleCatalog.classify("orbit.rvz")); assertEquals(FoundFile.Game(GameSystem.GAMECUBE), ConsoleCatalog.classify("orbit.gcm", ps2))
        assertEquals(FoundFile.Game(GameSystem.GAMECUBE), ConsoleCatalog.classify("orbit.iso", gamecube)); assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.iso", ps2))
        assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.chd", ps2)); assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.cue", ps2))
        // A compressed image two consoles use, and programs without a name of their own: only in the console's folder.
        assertEquals(FoundFile.OtherConsole("PlayStation Portable"), ConsoleCatalog.classify("orbit.cso")); assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.cso", ps2))
        assertEquals(FoundFile.Ignored, ConsoleCatalog.classify("orbit.dol")); assertEquals(FoundFile.Game(GameSystem.GAMECUBE), ConsoleCatalog.classify("orbit.dol", gamecube))
        assertEquals(FoundFile.Game(GameSystem.PS2), ConsoleCatalog.classify("orbit.elf", ps2)); assertEquals(FoundFile.Game(GameSystem.GAMECUBE), ConsoleCatalog.classify("orbit.elf", gamecube))
        // A DVD is bounded like everything else, by its own console.
        assertEquals(9L shl 30, GameSystem.limit("orbit.iso")); assertEquals(2L shl 30, GameSystem.limit("orbit.gcm")); assertEquals(9L shl 30, GameSystem.PS2.maxBytes)
    }

    @Test fun aGdRomSheetKeepsItsLayoutAndNeverItsFileNames() {
        val sheet = dev.cartridge.storage.GdiSheet.parse("3\r\n1 0 4 2352 track01.bin 0\r\n2 756 0 2352 \"track 02.raw\" 0\r\n3 45000 4 2352 track03.bin 0\r\n")
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), sheet.files)
        assertEquals("3\n1 0 4 2352 track-00.bin 0\n2 756 0 2352 track-01.bin 0\n3 45000 4 2352 track-02.bin 0\n", sheet.text)
        for (bad in listOf("", "3\n1 0 4 2352 a.bin 0\n", "1\n1 0 4 2352 ../a.bin 0\n", "1\n1 0 4 2352 dir/a.bin 0\n", "1\n1 0 9 2352 a.bin 0\n", "1\n1 0 4 2352 a.bin\n", "0\n", "x\n"))
            assertThrows(bad, IllegalArgumentException::class.java) { dev.cartridge.storage.GdiSheet.parse(bad) }
    }

    @Test fun whatASheetNamesIsThatDiscsTrackAndNeverAGameOfItsOwn() {
        // An older GD-ROM dump keeps its data tracks as .iso, which alone would be a PlayStation disc.
        val gdi = "3\n1 0 4 2048 Track01.iso 0\n2 450 0 2352 track02.raw 0\n3 45000 4 2048 track03.iso 0\n"
        assertEquals(setOf("track01.iso", "track02.raw", "track03.iso"), dev.cartridge.storage.SheetTracks.named("Orbit.GDI", gdi))
        val cue = "FILE \"Orbit (Track 1).iso\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n"
        assertEquals(setOf("orbit (track 1).iso"), dev.cartridge.storage.SheetTracks.named("orbit.cue", cue))
        // A sheet that cannot be read names nothing; importing it is what reports why.
        assertTrue(dev.cartridge.storage.SheetTracks.named("orbit.gdi", "not a sheet").isEmpty()); assertTrue(dev.cartridge.storage.SheetTracks.named("orbit.cue", "").isEmpty())
        // The names-only count does without reading: beside a GDI, numbered tracks are tracks.
        val folder = listOf("disc.gdi", "track01.iso", "track02.raw", "track03.iso", "other.iso")
        assertEquals(listOf("track01.iso", "track02.raw", "track03.iso"), folder.filter { dev.cartridge.storage.SheetTracks.likely(it, folder) })
        assertFalse(dev.cartridge.storage.SheetTracks.likely("track01.iso", listOf("track01.iso", "orbit.cue")))
    }

    @Test fun aDiscNamesItsOwnConsoleInItsFirstSector() {
        val cooked = header(64) { put(0, "SEGA SEGASATURN SEGA TP T-0     ") }
        val raw = header(64) { this[0] = 0; for (index in 1..10) this[index] = 0xFF.toByte(); put(16, "SEGA SEGASATURN ") }
        assertEquals(DiscOwner.Playable(GameSystem.SATURN), GameSystem.discOwner(cooked)); assertEquals(DiscOwner.Playable(GameSystem.SATURN), GameSystem.discOwner(raw))
        // A console Cartridge knows but cannot play is named, so its disc is refused instead of being listed under PlayStation.
        assertEquals(DiscOwner.Playable(GameSystem.DREAMCAST), GameSystem.discOwner(header(64) { put(16, "SEGA SEGAKATANA ") }))
        assertEquals(DiscOwner.Playable(GameSystem.DREAMCAST), GameSystem.discOwner(header(64), "REM SINGLE-DENSITY AREA\nFILE \"a.bin\" BINARY\nREM HIGH-DENSITY AREA\n"))
        assertEquals(DiscOwner.Other("Sega CD"), GameSystem.discOwner(header(64) { put(0, "SEGADISCSYSTEM  ") }))
        // A PlayStation disc says nothing here; neither does an empty or short read.
        assertNull(GameSystem.discOwner(header(64))); assertNull(GameSystem.discOwner(ByteArray(0))); assertNull(GameSystem.discOwner(header(20) { put(0, "SEGA SEGASAT") }))
    }

    @Test fun storageStatesOptionsAndArtworkFollowTheDefinition() {
        val id = "a".repeat(64); val roms = File("roms")
        assertEquals(File(roms, "$id.sfc"), GameFiles.resolve(roms, id, GameSystem.SNES)); assertEquals(File(roms, "$id.md"), GameFiles.resolve(roms, id, GameSystem.GENESIS))
        assertEquals(File(roms, "$id.gba"), GameFiles.resolve(roms, id, GameSystem.GBA)); assertEquals(File(File(roms, "$id.ps1"), "game.chd"), GameFiles.resolve(roms, id, GameSystem.PS1))
        assertEquals(File(File(roms, "$id.saturn"), "game.chd"), GameFiles.resolve(roms, id, GameSystem.SATURN))
        assertEquals(File(File(roms, "$id.dreamcast"), "game.cdi"), GameFiles.resolve(roms, id, GameSystem.DREAMCAST))
        assertEquals(File(File(roms, "$id.ps2"), "game.chd"), GameFiles.resolve(roms, id, GameSystem.PS2))
        assertEquals(File(File(roms, "$id.gamecube"), "game.ciso"), GameFiles.resolve(roms, id, GameSystem.GAMECUBE))
        // mGBA's states stay bare and byte compatible; every other core's name the build that wrote them.
        val bytes = byteArrayOf(1, 2, 3)
        for (system in GameSystem.entries) {
            val stored = ManualState.encode(system, bytes)
            assertArrayEquals(system.name, bytes, ManualState.decode(system, stored))
            if (system.core == Core.Mgba) assertArrayEquals(bytes, stored) else assertTrue(String(stored, Charsets.US_ASCII).contains(system.coreRevision))
        }
        // A state from one core is refused by another, and by a later build of the same one.
        assertThrows(IllegalArgumentException::class.java) { ManualState.decode(GameSystem.GENESIS, ManualState.encode(GameSystem.SNES, bytes)) }
        assertArrayEquals(bytes, ManualState.decode(GameSystem.GG, ManualState.encode(GameSystem.SMS, bytes))) // One core, one state format.
        // Consoles that share a core share its options; nothing from another core reaches a session.
        assertEquals(GameOptions.forSystem(GameSystem.GENESIS), GameOptions.forSystem(GameSystem.GG))
        assertEquals(GameOptions.all.size, GameOptions.all.map { it.id }.toSet().size)
        for (core in Core.entries) {
            val system = GameSystem.entries.first { it.core == core }
            val keys = GameOptions.session(GameOptions.resolve(system, emptyMap())).keys - GameOptions.adapterKeys
            assertTrue(core.name, core.fixed.keys.none { it in keys })
            assertTrue(core.name, GameOptions.forSystem(system).any { it.variable == null && it.family == core }) // Its own screen effect.
            for (other in Core.entries - core) assertTrue("${core.name} takes nothing of ${other.name}", GameOptions.all.filter { it.family == other }.none { it.variable in keys })
        }
        assertEquals(setOf("snes9x_reduce_sprite_flicker"), GameOptions.session(GameOptions.resolve(GameSystem.SNES, emptyMap())).keys - GameOptions.adapterKeys)
        assertEquals("enabled", GameOptions.session(GameOptions.resolve(GameSystem.NES, mapOf("nes.sprites" to "enabled")))["fceumm_nospritelimit"])
        // PlayStation and Dreamcast can start without their owner's file; Saturn and PlayStation 2 cannot. Saturn takes
        // one file per region, PlayStation 2 any one.
        assertEquals(listOf(Core.Pcsx, Core.BeetleSaturn, Core.Flycast, Core.Armsx2), Core.entries.filter { it.firmware.isNotEmpty() }); assertFalse(Core.Pcsx.firmwareRequired)
        assertFalse(Core.Flycast.firmwareRequired); assertTrue(Core.Flycast.ownFiles); assertTrue(Core.Flycast.analog)
        assertEquals(listOf(Core.BeetleSaturn, Core.Armsx2), Core.entries.filter { it.firmwareRequired })
        assertEquals(listOf(Core.Flycast, Core.Dolphin, Core.Armsx2), Core.entries.filter { it.analog })
        // Two of the three that draw for themselves keep their own files in the game's folder and start with empty
        // controller ports, and one of those brings data files of its own. The PlayStation 2 engine keeps one
        // console's files, memory cards included, under the system folder, and is the one that cannot read a CUE sheet.
        assertTrue(Core.Dolphin.ownFiles && !Core.Armsx2.ownFiles); assertEquals(listOf(Core.Flycast, Core.Dolphin), Core.entries.filter { it.emptyPorts })
        assertEquals(listOf(Core.Dolphin), Core.entries.filter { it.data != null }); assertTrue(Core.Dolphin.firmware.isEmpty())
        assertEquals(listOf(Core.Armsx2), Core.entries.filter { !it.sheets })
        // The whole machine is captured during play only where that is small; where it cannot be held, no state is
        // offered and neither is starting from one.
        assertEquals(listOf(Core.Flycast), Core.entries.filter { it.snapshots == dev.cartridge.domain.Snapshots.Paused })
        assertEquals(listOf(Core.Dolphin, Core.Armsx2), Core.entries.filter { it.snapshots == dev.cartridge.domain.Snapshots.Never })
        assertTrue(GameOptions.forFamily(Core.Dolphin).none { it.id == "startFromState" }); assertTrue(GameOptions.forFamily(Core.Armsx2).none { it.id == "startFromState" })
        assertTrue(GameOptions.forFamily(Core.Flycast).any { it.id == "startFromState" })
        // An engine that draws for itself is unloaded on the thread it ran on.
        assertEquals(listOf(Core.Flycast, Core.Dolphin, Core.Armsx2), Core.entries.filter { it.draws })
        assertEquals("dc/dc_boot.bin", Core.Flycast.firmware(ByteArray(2 * 1024 * 1024), "e10c53c2f8b90bab96ead2d368858623")!!.file)
        assertTrue(Core.BeetleSaturn.firmwareRequired); assertTrue(Core.BeetleSaturn.ownFiles); assertFalse(Core.Pcsx.ownFiles)
        assertEquals("mpr-17933.bin", Core.BeetleSaturn.firmware(ByteArray(512 * 1024), "3240872c70984b6cbfda1586cab68dbe")!!.file)
        assertEquals("sega_101.bin", Core.BeetleSaturn.firmware(ByteArray(512 * 1024), "85ec9ca47d8f6807718151cbcca8b964")!!.file)
        assertNull(Core.BeetleSaturn.firmware(ByteArray(512 * 1024), "8dd7d5296a650fac7319bce665a6a53c")) // A PlayStation BIOS is not a Saturn one.
        assertEquals(Core.entries.sumOf { it.firmware.size }, Core.entries.flatMap { core -> core.firmware.map { it.file } }.toSet().size)
        assertTrue(Core.entries.filter { it.firmware.isNotEmpty() }.all { it.firmwareHelp.isNotBlank() && it.firmwareAccepts.isNotBlank() })
        assertEquals("scph5501.bin", Core.Pcsx.firmware(ByteArray(512 * 1024), "8dd7d5296a650fac7319bce665a6a53c")!!.file)
        assertNull(Core.Pcsx.firmware(ByteArray(512 * 1024), "0".repeat(32))); assertNull(Core.Pcsx.firmware(ByteArray(1024), "8dd7d5296a650fac7319bce665a6a53c"))
        // The artwork provider's own platform names, and a fingerprint only where the private copy is the dump it knows.
        assertEquals("Mega Drive", GameSystem.GENESIS.provider); assertEquals("Super Nintendo", GameSystem.SNES.provider)
        assertEquals("Nintendo Entertainment System", GameSystem.NES.provider); assertEquals("PlayStation", GameSystem.PS1.provider)
        assertTrue(GameSystem.SNES.fingerprint); assertFalse(GameSystem.PS1.fingerprint)
        // A label is decoded for the object that carries it; the first cartridge's key stays as it was stored.
        val label = LabelRef("b".repeat(64))
        assertEquals(label.textureKey, label.textureKey(Media.Cartridge)); assertEquals("${label.textureKey}:512", label.textureKey(Media.Disc))
        assertEquals("${label.textureKey}:448", label.textureKey(Media.TallCartridge)); assertEquals("${label.textureKey}:184", label.textureKey(GameSystem.SNES.media))
        assertEquals("${label.textureKey}:228", label.textureKey(GameSystem.SNES.media("Orbit Field (USA)")))
        // No two shapes share a label size, so a key never names the wrong decode.
        assertEquals(Media.entries.size, Media.entries.map { it.labelHeight }.toSet().size)
    }

    @Test fun everyConsoleWithGamesIsAShelfInRegistryOrder() {
        fun game(index: Int, system: GameSystem) = LibraryGame(index.toString(16).padStart(64, '0'), "Game $index", "content://$index", system = system)
        val games = listOf(game(1, GameSystem.GG), game(2, GameSystem.SNES), game(3, GameSystem.GBA), game(4, GameSystem.GENESIS), game(5, GameSystem.NES))
        assertEquals(listOf("Game Boy Advance", "Super Nintendo", "Nintendo Entertainment System", "Genesis", "Game Gear", "All Games"), Shelf.available(games).map { it.title })
        assertEquals(listOf(games[1]), Shelf.Console(GameSystem.SNES).apply(games))
    }
}
