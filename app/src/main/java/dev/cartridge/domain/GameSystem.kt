package dev.cartridge.domain

/**
 * How a case's cover unfolds onto its label texture, left to right: back, spine, front (ScreenScraper's `box-texture`
 * layout, ADR 0029). Widths and the height are the cover panels' in millimetres, as `tools/generate_cases.py` builds them.
 */
class Cover(back: Float, spine: Float, front: Float, val tall: Float) {
    private val total = back + spine + front
    /** Where the back ends and the front begins across the texture, 0..1. */
    val backEnd = back / total
    val frontStart = (back + spine) / total
    val aspect = total / tall
}

/**
 * The case a game is shown in (ADR 0029). It decides the 3D template and the cover's proportions, nothing about how
 * the game runs. The label is the whole unfolded [cover], [labelWidth] by [labelHeight] pixels; [model] is the
 * packaged template from `tools/generate_cases.py`, in metres at its real size, and [scale] brings it to about
 * 38 mm tall on the stage (56 mm at most across), so three rows of the grid stand apart. Templates are pooled in
 * this order; names are not stored.
 */
enum class Media(val labelHeight: Int, val noun: String, val model: String, val scale: Float, val cover: Cover) {
    JewelCase(458, "case", "jewel_case", .30f, Cover(138f, 10.4f, 120f, 120f)),
    /** Game Boy, Game Boy Color and Game Boy Advance in North America and Europe. */
    HandheldBox(471, "box", "gba_box", .30f, Cover(125f, 22f, 125f, 125f)),
    GbaBoxJapan(703, "box", "gba_box_jp", .28f, Cover(88f, 22f, 88f, 136f)),
    GbBoxJapan(608, "box", "gb_box_jp", .32f, Cover(90f, 22f, 90f, 120f)),
    GameGearBox(583, "box", "game_gear_box", .23f, Cover(130f, 30f, 130f, 165f)),
    NesBox(659, "box", "nes_box", .21f, Cover(127f, 32f, 127f, 184f)),
    FamicomBox(334, "box", "famicom_box", .40f, Cover(133f, 25f, 133f, 95f)),
    SnesBox(320, "box", "snes_box", .30f, Cover(184f, 38f, 184f, 127f)),
    SuperFamicomBox(267, "box", "super_famicom_box", .29f, Cover(190f, 31f, 190f, 107f)),
    /** Genesis, Mega Drive and Master System. */
    Clamshell(663, "case", "clamshell_case", .20f, Cover(132f, 25f, 132f, 187f)),
    SaturnCase(709, "case", "saturn_case", .18f, Cover(142f, 22f, 142f, 212f)),
    /** PlayStation 2, and Dreamcast in Europe. */
    DvdCase(684, "case", "dvd_case", .20f, Cover(130f, 14f, 130f, 183f)),
    GameCubeCase(673, "case", "gamecube_case", .20f, Cover(130f, 14f, 130f, 180f));
    val labelWidth get() = 1024
}

/**
 * Whether a dump is named as a North American release: "(USA)", "(USA, Europe)", "(U)", "[U]", "(JU)". The name is
 * all that says so, and all this decides is the shape of the object: a game named any other way has the console's own.
 */
fun releasedInNorthAmerica(title: String) = NORTH_AMERICA.containsMatchIn(title)
private val NORTH_AMERICA = Regex("""[(\[](?:[^)\]]*\b(?:USA|US|NTSC-U|North America)\b[^)\]]*|[JEB]*U[JEB]*)[)\]]""")

/**
 * The market a dump was released in, from its name alone: North America first ("(USA, Europe)" is a US release),
 * then Europe, then Japan. "(World)" and a name without a tag are US. It decides the proportions of the case a game
 * is shown in and which region's art is offered first (ADR 0029); nothing else may ask for it.
 */
enum class Market(val code: String, val artRegions: List<String>) {
    US("us", listOf("us", "wor", "ss")), EU("eu", listOf("uk", "eu", "wor", "ss")), JP("jp", listOf("jp", "wor", "ss"));
    companion object {
        fun of(title: String) = when {
            releasedInNorthAmerica(title) -> US
            EUROPE.containsMatchIn(title) -> EU
            JAPAN.containsMatchIn(title) -> JP
            else -> US
        }
    }
}
private val EUROPE = Regex("""[(\[](?:[^)\]]*\b(?:Europe|EUR|EU|UK|PAL|Germany|France|Spain|Italy|Netherlands|Sweden|Australia|Scandinavia|Nordic)\b[^)\]]*|[JB]*E[JB]*)[)\]]""", RegexOption.IGNORE_CASE)
private val JAPAN = Regex("""[(\[](?:[^)\]]*\b(?:Japan|JPN|JP|NTSC-J)\b[^)\]]*|J)[)\]]""", RegexOption.IGNORE_CASE)

/**
 * A console file only its owner can supply; never bundled or downloaded. [file] is the private name the pinned
 * core looks for, and [md5] lists the dumps it is known to accept.
 */
class Firmware(val file: String, val title: String, val bytes: Int, val md5: Set<String> = emptySet(),
    /** For a console with too many genuine dumps to list: what the file's own contents have to show instead. */
    val proves: ((ByteArray) -> Boolean)? = null)

/** When the whole machine can be captured: for Save State, and for the checkpoint a session is resumed from. */
enum class Snapshots {
    /** Small and quick: when asked, when paused, and every few seconds of play. */
    Always,
    /** Tens of megabytes, or only consistent while the engine is at rest: when asked and when paused, never during play. */
    Paused,
    /** Too large to hold in memory twice. The game's own saves are all there is, and no state is offered. */
    Never,
}

/**
 * An embedded Libretro core built from pinned source. Consoles that share a core share its options and its
 * firmware. [revision] binds save states, session checkpoints and backups to the build that wrote them.
 */
enum class Core(
    val file: String,
    val revision: String,
    /** The consoles it runs, as the heading of their shared options. */
    val title: String,
    /** The engine and its pinned version, for About. */
    val engine: String,
    val firmware: List<Firmware> = emptyList(),
    /** False when the core has a built-in replacement, so its consoles start without the owner's file. */
    val firmwareRequired: Boolean = false,
    /** What happens without the firmware, for the row that offers to add it. */
    val firmwareHelp: String = "",
    /** The dumps this core accepts, in words: completes "Choose …" when a picked file is refused. */
    val firmwareAccepts: String = "",
    /** Variables the save design fixes. They are applied last and no option can replace them. */
    val fixed: Map<String, String> = emptyMap(),
    /** The first mGBA states were written bare and stay byte compatible; every later core's states name their build. */
    val stateEnvelope: Boolean = true,
    /** The core writes files of its own beside its battery memory, so each game is given a folder to itself. */
    val ownFiles: Boolean = false,
    /** Its consoles' pads have sticks: a game reads both sticks themselves instead of the left one as a D-pad. */
    val analog: Boolean = false,
    /** The core starts with nothing in its controller ports: before a frame runs the pad is plugged into the first and the rest are named empty. */
    val emptyPorts: Boolean = false,
    /** The engine's own data files: the packaged archive, and the folder under the system directory it is unpacked into. */
    val data: Pair<String, String>? = null,
    val snapshots: Snapshots = Snapshots.Always,
    /** The engine draws for itself in the host's OpenGL ES context, on a thread it may keep things for: it is unloaded on that thread. */
    val draws: Boolean = false,
    /** False for an engine that takes a disc as one image and cannot read a CUE sheet: it is started from the sheet's first track. */
    val sheets: Boolean = true,
    /**
     * The host may run it several times per frame shown (ADR 0031). An engine that draws for itself is timed by its own
     * sound in the shim and has not been run faster on the device: it is offered only once a game shows it works.
     */
    val fastForward: Boolean = !draws,
) {
    Mgba("libmgba_libretro.so", "mgba-0.10.5-cartridge-memory-2", "Game Boy · Color · Advance", "mGBA 0.10.5", stateEnvelope = false),
    Pcsx("libpcsx_rearmed_libretro.so", "pcsx-c8816799-cartridge-1", "PlayStation", "PCSX ReARMed c8816799",
        firmware = listOf(Firmware("scph5501.bin", "BIOS file", 512 * 1024, setOf("8dd7d5296a650fac7319bce665a6a53c", "490f666e1afb15b7362b406ed1cea246",
            "32736f17079d0b2b7024407c39bd3050", "924e392ed05558ffdb115408c263dccf", "1e68c231d0896b7eadcad1d7d8e76129", "6e3735ff4c7dc899ee98981385f6f3d0",
            "c53ca5908936d412331790f4426c6c33"))),
        firmwareHelp = "Most games need the console's own BIOS. Without it only some will start.",
        // One configured private BIOS. PCSX identifies the region from the bytes, not from this name.
        firmwareAccepts = "a supported retail PS1 BIOS dump (SCPH-5500/5501/5502/1001/7001/101 or PSXONPSP660)",
        fixed = mapOf("pcsx_rearmed_bios" to "auto", "pcsx_rearmed_memcard1" to "libretro", "pcsx_rearmed_memcard2" to "disabled")),
    Snes9x("libsnes9x_libretro.so", "snes9x-1.63-cartridge-1", "Super Nintendo", "Snes9x 1.63"),
    Fceumm("libfceumm_libretro.so", "fceumm-7a542dab-cartridge-1", "Nintendo Entertainment System", "FCEUmm 7a542dab"),
    GenesisPlusGx("libgenesis_plus_gx_libretro.so", "gpgx-393f70cd-cartridge-1", "Genesis · Master System · Game Gear", "Genesis Plus GX 393f70cd"),
    BeetleSaturn("libmednafen_saturn_libretro.so", "beetle-saturn-ec20ea05-cartridge-1", "Saturn", "Beetle Saturn ec20ea05",
        // The core chooses by the disc's region, so the two files are separate rows and either one is a start.
        firmware = listOf(Firmware("mpr-17933.bin", "BIOS file · America and Europe", 512 * 1024, setOf("3240872c70984b6cbfda1586cab68dbe")),
            Firmware("sega_101.bin", "BIOS file · Japan", 512 * 1024, setOf("85ec9ca47d8f6807718151cbcca8b964"))),
        firmwareRequired = true,
        firmwareHelp = "Saturn games cannot start without the console's own BIOS. American and European games use one file, Japanese games another.",
        firmwareAccepts = "a Saturn BIOS dump: mpr-17933.bin for America and Europe, or sega_101.bin for Japan",
        // The console's internal memory goes through the same battery path as every cartridge. The pinned fork's
        // month-old binary translators for the two sound and maths processors stay off until they are measured here.
        fixed = mapOf("beetle_saturn_save_method" to "libretro", "beetle_saturn_jit_scu" to "disabled", "beetle_saturn_jit_scsp" to "disabled"),
        ownFiles = true),
    Flycast("libflycast_libretro.so", "flycast-2.7-cartridge-1", "Dreamcast", "Flycast 2.7",
        firmware = listOf(Firmware("dc/dc_boot.bin", "BIOS file", 2 * 1024 * 1024, setOf("e10c53c2f8b90bab96ead2d368858623"))),
        firmwareHelp = "Optional. Without it games start on a built-in replacement, which runs most of them.",
        firmwareAccepts = "a Dreamcast BIOS dump (dc_boot.bin)",
        // Memory units are files the core writes: one set per game, in that game's own folder, never the shared
        // system one. The host has no channel for a trigger's travel, so the triggers are buttons.
        fixed = mapOf("reicast_per_content_vmus" to "All VMUs", "reicast_digital_triggers" to "enabled"),
        ownFiles = true, analog = true, emptyPorts = true, snapshots = Snapshots.Paused, draws = true),
    Dolphin("libdolphin_libretro.so", "dolphin-114977f1-cartridge-1", "GameCube", "Dolphin 114977f1",
        // A game starts directly, not through the console's own menu, which would need the owner's file; and the
        // engine writes none of its messages over the picture. Its memory card is a folder of the game's own.
        // One emulation thread: with two, this frontend's hand-over between them can lose the signal that ends a
        // frame (it is dropped when it arrives before the frame's thread is listening) and both wait for ever.
        fixed = mapOf("dolphin_renderer" to "Hardware", "dolphin_skip_gc_bios" to "enabled", "dolphin_osd_enabled" to "disabled",
            "dolphin_main_cpu_thread" to "disabled"),
        // A state of this console is 87 MiB (measured on the RP5), more than the app may hold twice.
        ownFiles = true, analog = true, emptyPorts = true, data = "cores/dolphin-sys.zip" to "dolphin-emu/Sys", snapshots = Snapshots.Never,
        draws = true),
    // PCSX2 with ARM64 translators. It runs the console's own BIOS and has no replacement for it. Every model and
    // region of the console had its own BIOS, so a dump is accepted by what its file table says rather than from a
    // list. The core keeps everything it writes (memory cards, settings, shader cache) under the system folder, as
    // one console's: its two memory cards are shared by all of this console's games, as they were.
    // No state yet: the core names 68 MiB as the size of one, above what this app holds, and makes one on its own
    // emulation thread while the thread that asked waits.
    Armsx2("libpcsx2-libretro.so", "armsx2-0076b5c3-cartridge-1", "PlayStation 2", "ARMSX2 0076b5c3",
        firmware = listOf(Firmware("pcsx2/bios/ps2-bios.bin", "BIOS file", 4 * 1024 * 1024, proves = ::playStation2Bios)),
        firmwareRequired = true,
        firmwareHelp = "PlayStation 2 games cannot start without the console's own BIOS. Any region's will start any region's games.",
        firmwareAccepts = "a PlayStation 2 BIOS dump (a 4 MiB file from the console)",
        // The one renderer this host can give a surface to, and the game without the console's start-up screens.
        fixed = mapOf("armsx2_renderer" to "OpenGL", "armsx2_fast_boot" to "enabled", "armsx2_bios" to "auto"),
        analog = true, snapshots = Snapshots.Never, draws = true, sheets = false);

    /** The firmware a picked file is, by content. Null when it is not a dump this core is known to accept. */
    fun firmware(content: ByteArray, md5: String) =
        firmware.firstOrNull { it.bytes == content.size && (md5 in it.md5 || it.proves?.invoke(content) == true) }
}

/** How much a file's extension says on its own. */
enum class Claim {
    /** The extension is this console's game wherever it is found. */
    Name,
    /** Common outside games too (.exe, .md): tried quietly, and announced only in the console's own folder. */
    Content,
    /** Meaningless without context (.bin is a disc track nearly everywhere): only in the console's own folder. */
    Folder,
}

/**
 * One way a console's game arrives as a file. [verify] returns why these first bytes cannot be that console's
 * game, or null. A [shared] extension is a container several consoles use, so the folder's name or the content
 * decides which one.
 */
class Format(val extension: String, val shared: Boolean = false, val claim: Claim = Claim.Name,
    /**
     * True when these first bytes, with the file's length where it is known (-1 where it is not), can only be this
     * console's game. That outranks the name of the folder the file was found in.
     */
    val proves: (ByteArray, Long) -> Boolean = { _, _ -> false },
    val verify: (ByteArray) -> String? = { null })

// Content checks for the registry below. They are file-level because an enum's entries are built before its companion.
private fun ascii(bytes: ByteArray, from: Int, length: Int) =
    if (bytes.size < from + length) "" else String(bytes, from, length, Charsets.US_ASCII)
private fun disc(format: Format) = Format(format.extension, format.shared, format.claim) { bytes ->
    if (bytes.size < 32) "The PlayStation file is incomplete" else format.verify(bytes) }
/** Four bytes as the big-endian number they spell, or -1 past the end. */
private fun word(bytes: ByteArray, at: Int): Long =
    if (bytes.size < at + 4) -1 else (0..3).fold(0L) { value, n -> value shl 8 or (bytes[at + n].toLong() and 0xFF) }
/** More than any CD holds. */
private const val CD_BYTES = 900L shl 20
/**
 * A PlayStation 2 BIOS carries a table of the files in it, sixteen bytes a row, that begins with RESET. One of
 * those files, ROMVER, is fourteen characters: version, region, console or development kit, and date. This is
 * how the engine itself decides what it was given.
 */
private fun playStation2Bios(bytes: ByteArray): Boolean {
    fun name(row: Int) = ascii(bytes, row, 10).substringBefore('\u0000')
    fun size(row: Int) = (0..3).fold(0L) { value, n -> value or ((bytes[row + 12 + n].toLong() and 0xFF) shl (8 * n)) }
    var row = (0 until minOf(bytes.size - 16, 512 * 1024) step 16).firstOrNull { name(it) == "RESET" } ?: return false
    var file = 0L
    while (row + 16 <= bytes.size && bytes[row].toInt() != 0) {
        if (name(row) == "ROMVER") return file + 14 <= bytes.size && Regex("\\d{4}[A-Z][CD]\\d{8}").matches(ascii(bytes, file.toInt(), 14))
        file += size(row).let { if (it % 16 == 0L) it else (it + 16) and 0xFFFFFFF0L }
        row += 16
    }
    return false
}
private fun notChd(bytes: ByteArray) = if (ascii(bytes, 0, 8) != "MComprHD") "Not a CHD disc" else null
/** A version 5 CHD states the unit it packs: 2048 bytes for a DVD, 2448 for a CD or GD-ROM sector with its subcode. */
private fun chdUnit(bytes: ByteArray): Long = if (notChd(bytes) == null && word(bytes, 12) == 5L) word(bytes, 60) else -1
private fun chdLength(bytes: ByteArray): Long = if (chdUnit(bytes) < 0) -1 else word(bytes, 32) shl 32 or word(bytes, 36)
/** Nintendo marked every disc of these two consoles at a fixed place in its header. */
private fun gameCubeDisc(bytes: ByteArray) = word(bytes, 0x1C) == 0xC2339F3DL
private fun wiiDisc(bytes: ByteArray) = word(bytes, 0x18) == 0x5D1C9EA3L
private fun rvz(bytes: ByteArray) = ascii(bytes, 0, 3) == "RVZ" && bytes[3].toInt() == 1 && bytes.size >= 0x4C
private fun gcz(bytes: ByteArray) = word(bytes, 0) == 0x01C00BB1L && bytes.size >= 8
/** A bare program for one processor: homebrew, and Cartridge's own diagnostics. The header says which in either byte order. */
private fun program(bytes: ByteArray, machine: Int) = bytes.size >= 20 && bytes[0].toInt() == 0x7F && ascii(bytes, 1, 3) == "ELF" &&
    (if (bytes[5].toInt() == 2) bytes[19] else bytes[18]).toInt() == machine
/** The console a disc image is for, when it is one Cartridge recognises but cannot play. */
private fun foreign(bytes: ByteArray): String? = when {
    wiiDisc(bytes) || (rvz(bytes) && word(bytes, 0x48) == 2L) || (gcz(bytes) && bytes[4].toInt() == 1) -> "Wii"
    else -> null
}
/** The cartridge's own colour flag decides between the two consoles, whichever extension the file carries. */
private fun handheld(color: Boolean) = listOf("gb", "gbc").map { Format(it) { bytes -> when {
    bytes.size < 512 -> "The GB/GBC header is incomplete"
    ((bytes[0x143].toInt() and 0x80) != 0) != color -> "Made for the other Game Boy"
    else -> null
} } }
private fun cartridgeSized(bytes: ByteArray): String? = if (bytes.size < 512) "The Genesis file is too small" else null
/** A cartridge header names its console at 0x100; a copier's interleaved dump carries a 512-byte header instead. */
private fun megaDrive(bytes: ByteArray): String? = when {
    bytes.size < 512 -> "The Genesis file is too small"
    ascii(bytes, 0x100, 4) == "SEGA" || ascii(bytes, 0x101, 4) == "SEGA" -> null
    bytes[8] == 0xAA.toByte() && bytes[9] == 0xBB.toByte() -> null
    else -> "Not a Genesis or Mega Drive game"
}

/** What a disc's own first sector says about its console, when it says anything. */
sealed interface DiscOwner {
    data class Playable(val system: GameSystem) : DiscOwner
    /** A console Cartridge recognises but cannot play. */
    data class Other(val console: String) : DiscOwner
}

/**
 * Everything the app needs to know about a console. ROM identity determines the system; scraped metadata
 * cannot change the emulator or the media template. [name] is the stable identifier stored with every game.
 */
enum class GameSystem(
    val title: String,
    val maker: String,
    val generation: Int,
    val media: Media,
    val core: Core,
    /** The private working copy's extension, or the bundle directory's for games made of several files. */
    val extension: String,
    val formats: List<Format>,
    val maxBytes: Long,
    /** Folder names that mean this console, compared without case or punctuation. */
    val folders: Set<String>,
    /** A disc's files stay together under generated names; a single file is kept as one working copy. */
    val bundle: Boolean = false,
    /** The private copy is the dump an artwork provider fingerprints. A CUE or compressed container is not. */
    val fingerprint: Boolean = true,
    /** The platform name the artwork provider files this console under, where it differs from [title]. */
    provider: String? = null,
    /** The case this console's games came in in Europe and in Japan, where it was not [media] (ADR 0029). */
    val europe: Media? = null,
    val japan: Media? = null,
) {
    GBA("Game Boy Advance", "Nintendo", 6, Media.HandheldBox, Core.Mgba, "gba",
        listOf(Format("gba") { if (it.size < 192) "The GBA file is too small" else null }), 32L shl 20,
        setOf("gba", "gameboyadvance", "nintendogameboyadvance"), japan = Media.GbaBoxJapan),
    GB("Game Boy", "Nintendo", 4, Media.HandheldBox, Core.Mgba, "gb", handheld(color = false), (8L shl 20) + 64,
        setOf("gb", "gameboy", "nintendogameboy"), japan = Media.GbBoxJapan),
    GBC("Game Boy Color", "Nintendo", 5, Media.HandheldBox, Core.Mgba, "gbc", handheld(color = true), (8L shl 20) + 64,
        setOf("gbc", "gameboycolor", "gameboycolour", "nintendogameboycolor"), japan = Media.GbBoxJapan),
    PS1("PlayStation", "Sony", 5, Media.JewelCase, Core.Pcsx, "ps1", listOf(
        Format("chd", shared = true) { if (ascii(it, 0, 8) != "MComprHD") "Not a CHD disc" else null },
        Format("pbp") { if (it.size < 4 || it.take(4) != listOf<Byte>(0, 80, 66, 80)) "Not a PBP disc" else null },
        Format("iso", shared = true), Format("cue", shared = true),
        Format("exe", claim = Claim.Content) { if (ascii(it, 0, 8) != "PS-X EXE") "Not a PS-X executable" else null }).map(::disc), 2L shl 30,
        setOf("psx", "ps1", "psone", "playstation", "playstation1", "sonyplaystation"), bundle = true, fingerprint = false),
    SNES("Super Nintendo", "Nintendo", 4, Media.SnesBox, Core.Snes9x, "sfc",
        listOf("sfc", "smc", "swc", "fig").map { Format(it) { bytes -> if (bytes.size < 512) "The Super Nintendo file is too small" else null } }, 16L shl 20,
        setOf("snes", "sfc", "superfamicom", "supernintendo", "supernes", "nintendosnes", "supernintendoentertainmentsystem"),
        japan = Media.SuperFamicomBox),
    NES("Nintendo Entertainment System", "Nintendo", 3, Media.NesBox, Core.Fceumm, "nes", listOf(
        Format("nes") { if (it.size < 16 || ascii(it, 0, 3) != "NES" || it[3].toInt() != 0x1A) "Not an iNES game" else null },
        Format("unf") { if (ascii(it, 0, 4) != "UNIF") "Not a UNIF game" else null },
        Format("unif") { if (ascii(it, 0, 4) != "UNIF") "Not a UNIF game" else null }), 16L shl 20,
        setOf("nes", "famicom", "fc", "nintendoentertainmentsystem", "nintendones"), japan = Media.FamicomBox),
    GENESIS("Genesis", "Sega", 4, Media.Clamshell, Core.GenesisPlusGx, "md", listOf(
        // .gen and .smd can only be this console's, so an unlicensed cartridge without Sega's name in its header still
        // counts; .md and .bin are shared with text files and disc tracks and must show a header.
        Format("md", claim = Claim.Content, verify = ::megaDrive), Format("gen", verify = ::cartridgeSized), Format("smd", verify = ::cartridgeSized),
        Format("bin", claim = Claim.Folder, verify = ::megaDrive)), 16L shl 20,
        setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive", "segamegadrivegenesis"), provider = "Mega Drive"),
    SMS("Master System", "Sega", 3, Media.Clamshell, Core.GenesisPlusGx, "sms",
        listOf(Format("sms") { if (it.size < 512) "The Master System file is too small" else null }), 4L shl 20,
        setOf("mastersystem", "sms", "segamastersystem", "markiii")),
    GG("Game Gear", "Sega", 4, Media.GameGearBox, Core.GenesisPlusGx, "gg",
        listOf(Format("gg") { if (it.size < 512) "The Game Gear file is too small" else null }), 4L shl 20,
        setOf("gamegear", "gg", "segagamegear")),
    SATURN("Saturn", "Sega", 5, Media.SaturnCase, Core.BeetleSaturn, "saturn", listOf(
        Format("cue", shared = true), Format("chd", shared = true) { if (ascii(it, 0, 8) != "MComprHD") "Not a CHD disc" else null }), 1L shl 30,
        setOf("saturn", "segasaturn", "ss"), bundle = true, fingerprint = false, japan = Media.JewelCase),
    DREAMCAST("Dreamcast", "Sega", 6, Media.JewelCase, Core.Flycast, "dreamcast", listOf(
        Format("gdi"), Format("cdi"),
        // A GD-ROM holds more than any CD and is packed sector by sector like one.
        Format("chd", shared = true, proves = { bytes, _ -> chdUnit(bytes) == 2448L && chdLength(bytes) > CD_BYTES }, verify = ::notChd),
        Format("cue", shared = true),
        Format("elf", claim = Claim.Folder) { if (!program(it, 42)) "Not a Dreamcast program" else null }),
        2L shl 30, setOf("dreamcast", "segadreamcast", "dc"), bundle = true, fingerprint = false, europe = Media.DvdCase),
    PS2("PlayStation 2", "Sony", 6, Media.DvdCase, Core.Armsx2, "ps2", listOf(
        // A DVD is larger than any CD, and a CHD packs one in 2048-byte units. A game pressed on a CD looks like a
        // PlayStation one from its first bytes, so for those only the folder's name can say.
        Format("iso", shared = true, proves = { bytes, size -> size > CD_BYTES && !gameCubeDisc(bytes) }) {
            if (gameCubeDisc(it)) "Not a PlayStation 2 disc" else null },
        Format("chd", shared = true, proves = { bytes, _ -> chdUnit(bytes) == 2048L }, verify = ::notChd),
        Format("cue", shared = true),
        // A compressed image another handheld console also uses: taken as this console's only in its own folder.
        Format("cso", claim = Claim.Folder) { if (ascii(it, 0, 4) != "CISO") "Not a compressed PlayStation 2 disc" else null },
        Format("elf", claim = Claim.Folder) { if (!program(it, 8)) "Not a PlayStation 2 program" else null }),
        9L shl 30, setOf("ps2", "playstation2", "sonyplaystation2"), bundle = true, fingerprint = false),
    GAMECUBE("GameCube", "Nintendo", 6, Media.GameCubeCase, Core.Dolphin, "gamecube", listOf(
        Format("iso", shared = true, proves = { bytes, _ -> gameCubeDisc(bytes) }) { if (!gameCubeDisc(it)) "Not a GameCube disc" else null },
        Format("gcm") { if (!gameCubeDisc(it)) "Not a GameCube disc" else null },
        Format("rvz") { if (!rvz(it) || word(it, 0x48) != 1L) "Not a GameCube disc" else null },
        Format("gcz") { if (!gcz(it) || it[4].toInt() != 0) "Not a GameCube disc" else null },
        Format("ciso", claim = Claim.Folder) { if (ascii(it, 0, 4) != "CISO") "Not a compressed GameCube disc" else null },
        // A program without a marker of its own: it names where it starts, which has to be in the console's memory.
        Format("dol", claim = Claim.Folder) { if (word(it, 0xE0) !in 0x80000000L..0x817FFFFFL) "Not a GameCube program" else null },
        Format("elf", claim = Claim.Folder) { if (!program(it, 20)) "Not a GameCube program" else null }),
        2L shl 30, setOf("gamecube", "gc", "ngc", "nintendogamecube"), bundle = true, fingerprint = false);

    val provider = provider ?: title
    /** The console's number at ScreenScraper (`systemeid`), the default artwork source. Every console needs one. */
    val screenScraper: Int get() = when (this) {
        GBA -> 12; GB -> 9; GBC -> 10; PS1 -> 57; SNES -> 4; NES -> 3; GENESIS -> 1; SMS -> 2; GG -> 21
        SATURN -> 22; DREAMCAST -> 23; PS2 -> 58; GAMECUBE -> 13
    }
    /** The buttons of the console's own pad, in the order its Controls page lists them (ButtonMap). */
    val pad: List<PadButton> get() = when (this) {
        GB, GBC -> Pads.GAME_BOY; GBA -> Pads.GAME_BOY_ADVANCE; PS1 -> Pads.PLAYSTATION; SNES -> Pads.SUPER_NINTENDO; NES -> Pads.NES
        GENESIS -> Pads.GENESIS; SMS -> Pads.MASTER_SYSTEM; GG -> Pads.GAME_GEAR; SATURN -> Pads.SATURN; DREAMCAST -> Pads.DREAMCAST
        PS2 -> Pads.PLAYSTATION_2; GAMECUBE -> Pads.GAMECUBE
    }
    /**
     * The case one game is shown in, from the [Market] its file is named for. Presentation only.
     */
    fun media(title: String): Media = when (Market.of(title)) { Market.EU -> europe; Market.JP -> japan; Market.US -> null } ?: media
    val coreFile get() = core.file
    val coreRevision get() = core.revision
    fun format(extension: String) = formats.firstOrNull { it.extension == extension }

    companion object {
        const val UNSUPPORTED = "Choose a game file for one of Cartridge's consoles"
        private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

        /** Every disc container PlayStation accepts; the importer keeps the original one inside its bundle. */
        val discExtensions get() = PS1.formats.map { it.extension }.toSet()
        /** A launcher that names other files: its size and date say nothing about whether its tracks changed. */
        fun sheet(name: String) = extension(name) in setOf("cue", "gdi")

        /**
         * Sega wrote the console's name at the start of every disc's data. [sector] is the start of the first
         * data track, raw (sync and header first) or cooked; [sheet] is the cue text that listed it.
         */
        fun discOwner(sector: ByteArray, sheet: String = ""): DiscOwner? {
            fun marked(text: String) = ascii(sector, 0, text.length) == text || ascii(sector, 16, text.length) == text
            return when {
                marked("SEGA SEGASATURN ") -> DiscOwner.Playable(SATURN)
                marked("SEGA SEGAKATANA ") || "HIGH-DENSITY AREA" in sheet.uppercase() -> DiscOwner.Playable(DREAMCAST)
                marked("SEGADISCSYSTEM  ") -> DiscOwner.Other("Sega CD")
                else -> null
            }
        }
        /** The consoles a file with this name could belong to, most specific first. */
        fun candidates(name: String): List<GameSystem> = extension(name).let { wanted -> entries.filter { it.format(wanted) != null } }
        fun supports(name: String) = candidates(name).isNotEmpty()
        /** The largest file any console could accept under this name, so a copy can be bounded before it is identified. */
        fun limit(name: String) = candidates(name).maxOfOrNull { it.maxBytes } ?: 0L

        /**
         * The console a file belongs to, from its name and first bytes. [folder] is the console its folder is
         * named for: it settles a container several consoles share, and never overrides what the bytes say.
         * [size] is the file's length where the importer knows it.
         */
        fun detect(name: String, header: ByteArray, folder: GameSystem? = null, size: Long = -1): GameSystem {
            val wanted = extension(name)
            val systems = candidates(name)
            require(systems.isNotEmpty()) { UNSUPPORTED }
            if (systems.any { it.bundle }) foreign(header)?.let { throw IllegalArgumentException("This is a $it disc, which Cartridge can't play yet") }
            systems.firstOrNull { it.format(wanted)!!.proves(header, size) }?.let { return it }
            val ordered = if (folder in systems) listOf(folder!!) + (systems - folder) else systems
            var reason: String? = null
            for (system in ordered) {
                val problem = system.format(wanted)!!.verify(header) ?: return system
                // The console the file was named for explains the refusal; a sibling's reason would mislead.
                if (reason == null || system.extension == wanted) reason = problem
            }
            throw IllegalArgumentException(reason)
        }
    }
}
