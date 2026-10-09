package dev.cartridge.domain

/** ROM identity determines the system; scraped metadata cannot change the emulator/media template. */
enum class GameSystem(val title: String, val extension: String, val labelHeight: Int) {
    GBA("Game Boy Advance", "gba", 256), GB("Game Boy", "gb", 448), GBC("Game Boy Color", "gbc", 448);
    val tallCartridge get() = this != GBA
    companion object {
        fun supports(name: String) = name.substringAfterLast('.', "").lowercase() in entries.map { it.extension }
        fun detect(name: String, header: ByteArray): GameSystem {
            require(supports(name)) { "Choose an uncompressed .gba, .gb or .gbc file" }
            if (name.endsWith(".gba", true)) { require(header.size >= 192) { "The GBA file is too small" }; return GBA }
            require(header.size >= 512) { "The GB/GBC header is incomplete" }
            return if ((header[0x143].toInt() and 0x80) != 0) GBC else GB
        }
    }
}
