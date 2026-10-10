package dev.cartridge.storage

/** Restricted, portable CUE import: only BINARY tracks in the granted folder, never paths. */
data class CueSheet(val text: String, val files: List<String>) {
    companion object {
        fun parse(input: String): CueSheet {
            require(input.length in 1..262144 && '\u0000' !in input) { "Invalid CUE sheet" }
            val files = mutableListOf<String>()
            val lines = input.removePrefix("\uFEFF").lineSequence().map { line ->
                val command = line.trim().substringBefore(' ').substringBefore('\t').uppercase()
                require(command in setOf("", "REM", "FILE", "TRACK", "INDEX", "PREGAP", "POSTGAP", "FLAGS", "TITLE", "PERFORMER", "SONGWRITER", "CATALOG", "ISRC")) { "Unsupported CUE directive" }
                if (command != "FILE") line.trimEnd() else {
                    val match = Regex("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))\\s+BINARY\\s*$", RegexOption.IGNORE_CASE).matchEntire(line)
                        ?: error("CUE supports BINARY tracks only")
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    require(name.length in 1..200 && name !in setOf(".", "..") && name.none { it in "/\\:\r\n" }) { "CUE tracks must be in the same folder" }
                    var index = files.indexOf(name)
                    if (index < 0) { files += name; index = files.lastIndex }
                    "FILE \"track-${index.toString().padStart(2, '0')}.bin\" BINARY"
                }
            }.toList()
            require(files.isNotEmpty() && files.size <= 99 && lines.any { Regex("^\\s*TRACK\\s+",RegexOption.IGNORE_CASE).containsMatchIn(it) }) { "The CUE has no valid tracks" }
            return CueSheet(lines.joinToString("\n") + "\n", files)
        }
    }
}
