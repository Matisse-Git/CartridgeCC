package dev.cartridge.domain

/** ROM identity determines the system; scraped metadata cannot change the emulator/media template. */
enum class GameSystem(val title: String, val extension: String, val labelHeight: Int) {
    GBA("Game Boy Advance", "gba", 256), GB("Game Boy", "gb", 448), GBC("Game Boy Color", "gbc", 448),
    PS1("PlayStation", "ps1", 512);
    val tallCartridge get() = this == GB || this == GBC
    val coreFile get() = if (this == PS1) "libpcsx_rearmed_libretro.so" else "libmgba_libretro.so"
    val coreRevision get() = if (this == PS1) "pcsx-c8816799-cartridge-1" else "mgba-0.10.5-cartridge-memory-2"
    companion object {
        val discExtensions = setOf("chd", "pbp", "iso", "cue", "exe")
        fun supports(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("gba", "gb", "gbc") + discExtensions
        fun detect(name: String, header: ByteArray): GameSystem {
            require(supports(name)) { "Choose GBA, GB/GBC or PS1 CHD, PBP, ISO, CUE or PS-X EXE content" }
            val extension = name.substringAfterLast('.').lowercase()
            if (extension in discExtensions) {
                require(header.size >= 32) { "The PS1 file is incomplete" }
                when (extension) {
                    "chd" -> require(header.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "MComprHD") { "Not a CHD disc" }
                    "pbp" -> require(header.take(4) == listOf<Byte>(0, 80, 66, 80)) { "Not a PBP disc" }
                    "exe" -> require(header.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "PS-X EXE") { "Not a PS-X executable" }
                }
                return PS1
            }
            if (name.endsWith(".gba", true)) { require(header.size >= 192) { "The GBA file is too small" }; return GBA }
            require(header.size >= 512) { "The GB/GBC header is incomplete" }
            return if ((header[0x143].toInt() and 0x80) != 0) GBC else GB
        }
    }
}
