package dev.cartridge.domain

/**
 * What a file in a game folder is, before any bytes are read. Content checks in the importers stay
 * authoritative; this only lets a scan explain what it skipped instead of failing file by file.
 */
sealed interface FoundFile {
    data class Game(val system: GameSystem) : FoundFile
    /** Belongs to a console Cartridge recognises but cannot play. */
    data class OtherConsole(val console: String) : FoundFile
    /** .zip/.7z/.rar: the importers need the uncompressed game. */
    data object Archive : FoundFile
    /** .m3u: a list of one game's discs, read when its folder is scanned to put them together (ADR 0032). Never a game itself. */
    data object Playlist : FoundFile
    data object Ignored : FoundFile
}

sealed interface FolderHint {
    data class Supported(val system: GameSystem) : FolderHint
    data class Other(val console: String) : FolderHint
}

object ConsoleCatalog {
    /** Every playable console's own folder names; the registry is the only place a console is declared. */
    private val supportedFolders = GameSystem.entries.flatMap { system -> system.folders.map { it to system } }.toMap()
    private val otherFolders = mapOf(
        "psp" to "PlayStation Portable", "playstationportable" to "PlayStation Portable",
        "ps3" to "PlayStation 3", "psvita" to "PlayStation Vita", "vita" to "PlayStation Vita",
        "segacd" to "Sega CD", "megacd" to "Sega CD", "pcenginecd" to "PC Engine CD", "pcecd" to "PC Engine CD", "tgcd" to "PC Engine CD",
        "turbografxcd" to "PC Engine CD", "pcengine" to "PC Engine", "pce" to "PC Engine", "tg16" to "PC Engine", "turbografx16" to "PC Engine",
        "wii" to "Wii", "wiiu" to "Wii U", "switch" to "Nintendo Switch",
        "3do" to "3DO", "neogeocd" to "Neo Geo CD", "neogeo" to "Neo Geo", "amigacd32" to "Amiga CD32", "cdi" to "CD-i", "xbox" to "Xbox",
        "n64" to "Nintendo 64", "nintendo64" to "Nintendo 64", "fds" to "Famicom Disk System",
        "nds" to "Nintendo DS", "ds" to "Nintendo DS", "3ds" to "Nintendo 3DS", "n3ds" to "Nintendo 3DS",
        "sega32x" to "Sega 32X", "32x" to "Sega 32X", "sg1000" to "SG-1000",
        "arcade" to "Arcade", "mame" to "Arcade", "fbneo" to "Arcade", "fba" to "Arcade", "atari2600" to "Atari 2600",
        "lynx" to "Atari Lynx", "ngp" to "Neo Geo Pocket", "ngpc" to "Neo Geo Pocket", "wonderswan" to "WonderSwan", "wswan" to "WonderSwan",
        "virtualboy" to "Virtual Boy", "pokemini" to "Pokémon Mini", "dos" to "DOS", "scummvm" to "ScummVM")
    private val otherExtensions = mapOf(
        "fds" to "Famicom Disk System",
        "n64" to "Nintendo 64", "z64" to "Nintendo 64", "v64" to "Nintendo 64", "nds" to "Nintendo DS", "3ds" to "Nintendo 3DS", "cia" to "Nintendo 3DS",
        "32x" to "Sega 32X", "sg" to "SG-1000",
        "pce" to "PC Engine", "wbfs" to "Wii", "wad" to "Wii", "wia" to "Wii",
        "cso" to "PlayStation Portable", "xci" to "Nintendo Switch", "nsp" to "Nintendo Switch",
        "ngp" to "Neo Geo Pocket", "ngc" to "Neo Geo Pocket", "ws" to "WonderSwan", "wsc" to "WonderSwan", "lnx" to "Atari Lynx",
        "a26" to "Atari 2600", "vb" to "Virtual Boy", "min" to "Pokémon Mini")
    private val archives = setOf("zip", "7z", "rar")

    /** Folder names are matched whole, ignoring case and punctuation: "Game Boy Advance" and "gba" agree. */
    fun folderHint(name: String): FolderHint? {
        val token = name.lowercase().filter(Char::isLetterOrDigit)
        return supportedFolders[token]?.let(FolderHint::Supported) ?: otherFolders[token]?.let(FolderHint::Other)
    }

    /** The playable console a folder is named for, if any. */
    fun console(hint: FolderHint?) = (hint as? FolderHint.Supported)?.system

    /**
     * Whether a name is put forward as a game here, rather than merely tried. A Windows program or a text
     * file beside the games is passed over without a word unless its folder is named for the console.
     */
    fun claims(fileName: String, hint: FolderHint? = null): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val system = (classify(fileName, hint) as? FoundFile.Game)?.system ?: return false
        return system.format(extension)!!.claim == Claim.Name || system == console(hint)
    }

    fun classify(fileName: String, hint: FolderHint? = null): FoundFile {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val folder = console(hint)
        val systems = GameSystem.candidates(fileName).filter { it.format(extension)!!.claim != Claim.Folder || it == folder }
        // The console whose own extension this is, then the one the folder names, then the first that accepts it.
        val system = systems.firstOrNull { it.extension == extension } ?: systems.firstOrNull { it == folder } ?: systems.firstOrNull()
        return when {
            // Containers are shared by many consoles. A folder named for another one wins.
            system != null && system.format(extension)!!.shared && hint is FolderHint.Other -> FoundFile.OtherConsole(hint.console)
            system != null -> FoundFile.Game(system)
            extension == "m3u" -> FoundFile.Playlist
            extension in archives -> FoundFile.Archive
            extension in otherExtensions -> FoundFile.OtherConsole(otherExtensions.getValue(extension))
            else -> FoundFile.Ignored
        }
    }
}
