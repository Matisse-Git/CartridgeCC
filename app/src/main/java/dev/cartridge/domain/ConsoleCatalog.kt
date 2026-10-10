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
    /** .m3u: multi-disc playlists are not supported; each CUE is still imported as its own disc. */
    data object Playlist : FoundFile
    data object Ignored : FoundFile
}

sealed interface FolderHint {
    data class Supported(val system: GameSystem) : FolderHint
    data class Other(val console: String) : FolderHint
}

object ConsoleCatalog {
    private val supportedFolders = mapOf(
        "gba" to GameSystem.GBA, "gameboyadvance" to GameSystem.GBA, "nintendogameboyadvance" to GameSystem.GBA,
        "gb" to GameSystem.GB, "gameboy" to GameSystem.GB, "nintendogameboy" to GameSystem.GB,
        "gbc" to GameSystem.GBC, "gameboycolor" to GameSystem.GBC, "gameboycolour" to GameSystem.GBC, "nintendogameboycolor" to GameSystem.GBC,
        "psx" to GameSystem.PS1, "ps1" to GameSystem.PS1, "psone" to GameSystem.PS1, "playstation" to GameSystem.PS1,
        "playstation1" to GameSystem.PS1, "sonyplaystation" to GameSystem.PS1)
    private val otherFolders = mapOf(
        "ps2" to "PlayStation 2", "playstation2" to "PlayStation 2", "sonyplaystation2" to "PlayStation 2",
        "psp" to "PlayStation Portable", "playstationportable" to "PlayStation Portable",
        "ps3" to "PlayStation 3", "psvita" to "PlayStation Vita", "vita" to "PlayStation Vita",
        "saturn" to "Sega Saturn", "segasaturn" to "Sega Saturn", "dreamcast" to "Dreamcast", "segadreamcast" to "Dreamcast", "dc" to "Dreamcast",
        "segacd" to "Sega CD", "megacd" to "Sega CD", "pcenginecd" to "PC Engine CD", "pcecd" to "PC Engine CD", "tgcd" to "PC Engine CD",
        "turbografxcd" to "PC Engine CD", "pcengine" to "PC Engine", "pce" to "PC Engine", "tg16" to "PC Engine", "turbografx16" to "PC Engine",
        "gamecube" to "GameCube", "gc" to "GameCube", "ngc" to "GameCube", "wii" to "Wii", "wiiu" to "Wii U", "switch" to "Nintendo Switch",
        "3do" to "3DO", "neogeocd" to "Neo Geo CD", "neogeo" to "Neo Geo", "amigacd32" to "Amiga CD32", "cdi" to "CD-i", "xbox" to "Xbox",
        "n64" to "Nintendo 64", "nintendo64" to "Nintendo 64", "nes" to "NES", "famicom" to "NES", "fds" to "Famicom Disk System",
        "snes" to "Super Nintendo", "sfc" to "Super Nintendo", "superfamicom" to "Super Nintendo", "supernintendo" to "Super Nintendo",
        "nds" to "Nintendo DS", "ds" to "Nintendo DS", "3ds" to "Nintendo 3DS", "n3ds" to "Nintendo 3DS",
        "genesis" to "Sega Genesis", "megadrive" to "Sega Genesis", "md" to "Sega Genesis", "sega32x" to "Sega 32X", "32x" to "Sega 32X",
        "mastersystem" to "Master System", "sms" to "Master System", "gamegear" to "Game Gear", "gg" to "Game Gear",
        "arcade" to "Arcade", "mame" to "Arcade", "fbneo" to "Arcade", "fba" to "Arcade", "atari2600" to "Atari 2600",
        "lynx" to "Atari Lynx", "ngp" to "Neo Geo Pocket", "ngpc" to "Neo Geo Pocket", "wonderswan" to "WonderSwan", "wswan" to "WonderSwan",
        "virtualboy" to "Virtual Boy", "pokemini" to "Pokémon Mini", "dos" to "DOS", "scummvm" to "ScummVM")
    private val otherExtensions = mapOf(
        "nes" to "NES", "fds" to "Famicom Disk System", "sfc" to "Super Nintendo", "smc" to "Super Nintendo",
        "n64" to "Nintendo 64", "z64" to "Nintendo 64", "v64" to "Nintendo 64", "nds" to "Nintendo DS", "3ds" to "Nintendo 3DS", "cia" to "Nintendo 3DS",
        "md" to "Sega Genesis", "gen" to "Sega Genesis", "smd" to "Sega Genesis", "32x" to "Sega 32X", "sms" to "Master System", "gg" to "Game Gear",
        "pce" to "PC Engine", "gcm" to "GameCube", "gcz" to "GameCube", "rvz" to "GameCube", "wbfs" to "Wii", "wad" to "Wii",
        "cso" to "PlayStation Portable", "xci" to "Nintendo Switch", "nsp" to "Nintendo Switch", "gdi" to "Dreamcast", "cdi" to "Dreamcast",
        "ngp" to "Neo Geo Pocket", "ngc" to "Neo Geo Pocket", "ws" to "WonderSwan", "wsc" to "WonderSwan", "lnx" to "Atari Lynx",
        "a26" to "Atari 2600", "vb" to "Virtual Boy", "min" to "Pokémon Mini")
    private val archives = setOf("zip", "7z", "rar")

    /** Folder names are matched whole, ignoring case and punctuation: "Game Boy Advance" and "gba" agree. */
    fun folderHint(name: String): FolderHint? {
        val token = name.lowercase().filter(Char::isLetterOrDigit)
        return supportedFolders[token]?.let(FolderHint::Supported) ?: otherFolders[token]?.let(FolderHint::Other)
    }

    fun classify(fileName: String, hint: FolderHint? = null): FoundFile {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            extension == "gba" -> FoundFile.Game(GameSystem.GBA)
            extension == "gbc" -> FoundFile.Game(GameSystem.GBC)
            extension == "gb" -> FoundFile.Game(GameSystem.GB)
            // Disc containers are shared by many consoles. A folder named for another one wins.
            extension in GameSystem.discExtensions -> if (hint is FolderHint.Other) FoundFile.OtherConsole(hint.console) else FoundFile.Game(GameSystem.PS1)
            extension == "m3u" -> FoundFile.Playlist
            extension in archives -> FoundFile.Archive
            extension in otherExtensions -> FoundFile.OtherConsole(otherExtensions.getValue(extension))
            else -> FoundFile.Ignored
        }
    }
}
