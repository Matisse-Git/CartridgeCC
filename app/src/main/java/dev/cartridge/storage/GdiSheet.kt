package dev.cartridge.storage

/**
 * Restricted GD-ROM sheet: a track count, then one line per track naming a file in the same granted folder.
 * As with a CUE, names never become paths: they are replaced by generated ones and returned for lookup.
 */
data class GdiSheet(val text: String, val files: List<String>) {
    companion object {
        private val TRACK = Regex("^(\\d{1,2})\\s+(\\d{1,7})\\s+([04])\\s+(2048|2336|2352)\\s+(?:\"([^\"]+)\"|(\\S+))\\s+(\\d{1,9})$")
        fun parse(input: String): GdiSheet {
            require(input.length in 1..65536 && '\u0000' !in input) { "Invalid GDI sheet" }
            val lines = input.removePrefix("\uFEFF").lines().map(String::trim).filter(String::isNotEmpty)
            val count = lines.firstOrNull()?.toIntOrNull()
            require(count != null && count in 1..99 && lines.size == count + 1) { "The GDI track list is incomplete" }
            val files = mutableListOf<String>()
            val tracks = lines.drop(1).map { line ->
                val match = requireNotNull(TRACK.matchEntire(line)) { "Unsupported GDI track line" }
                val name = match.groupValues[5].ifEmpty { match.groupValues[6] }
                require(name.length in 1..200 && name !in setOf(".", "..") && name.none { it in "/\\:\r\n" }) { "GDI tracks must be in the same folder" }
                var index = files.indexOf(name)
                if (index < 0) { files += name; index = files.lastIndex }
                "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]} ${match.groupValues[4]} track-${index.toString().padStart(2, '0')}.bin ${match.groupValues[7]}"
            }
            return GdiSheet((listOf(count.toString()) + tracks).joinToString("\n") + "\n", files)
        }
    }
}
