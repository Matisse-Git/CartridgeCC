package dev.cartridge.domain

/**
 * The discs of one game (ADR 0032). Discs are found to belong together by a playlist beside them or by their file names,
 * never by a scraped title: two different games can share a title, and one game's discs never share a content hash.
 */
object DiscNames {
    /** At most this many discs make one game; PCSX ReARMed holds eight. */
    const val MAX = 8
    private val TAG = Regex("""\s*\((?:Disc|Disk|CD)\s*(\d{1,2})(?:\s*of\s*\d{1,2})?\)""", RegexOption.IGNORE_CASE)

    /** The disc number a file is named for: `Game (Disc 2).cue` is 2. */
    fun number(name: String): Int? = TAG.find(stem(name))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX }
    /** What two discs of one game have in common: the name without its extension and without its disc tag. */
    fun base(name: String): String = TAG.replace(stem(name), "").trim()
    /** A title without its disc tag, for the game the discs make: `Game (Japan) (Disc 1)` is `Game (Japan)`. */
    fun title(title: String): String = TAG.replace(title, "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { title }
    private fun stem(name: String) = if ('.' in name && name.substringAfterLast('.').length in 1..4) name.substringBeforeLast('.') else name
}

/** An `.m3u` list of discs. Only names in the playlist's own folder are followed; a path elsewhere is ignored. */
object Playlist {
    const val MAX_BYTES = 64 * 1024

    /** Each disc the list names, in order. Line ends may be `\n`, `\r\n` or the `\r\r\n` some tools write. */
    fun entries(text: String): List<String> = text.split('\n').asSequence()
        .map { it.replace("\r", "").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { entry -> entry.takeIf { '/' !in it && '\\' !in it && ':' !in it && it != "." && it != ".." } }
        .take(DiscNames.MAX * 4).toList()

    /** The playlist the engine is started from: one absolute path per disc, in the game's order. */
    fun write(paths: List<String>): String {
        require(paths.size in 1..DiscNames.MAX && paths.all { it.startsWith("/") && '\n' !in it && '\r' !in it }) { "Invalid disc list" }
        return paths.joinToString("\n", postfix = "\n")
    }
}

/** A game file in one folder: its content id, the name it has there and its console. */
data class DiscFile(val id: String, val name: String, val system: GameSystem)

/** Discs found to be one game, in disc order. [playlist] names the list that said so, if one did. */
data class DiscGroup(val ids: List<String>, val numbers: List<Int>, val playlist: String?) {
    init { require(ids.size >= 2 && ids.size == numbers.size && ids.distinct().size == ids.size) }
}

object DiscSets {
    /**
     * The games made of several discs among one folder's [files]. A playlist ([playlists], name to text) is read first;
     * the files it does not claim are grouped by name, `(Disc N)` being the only difference. A group never mixes consoles,
     * and a disc number that two files share is ambiguous: such a set is left as separate games.
     */
    fun group(files: List<DiscFile>, playlists: Map<String, String>): List<DiscGroup> {
        val byName = files.groupBy { it.name.lowercase() }
        val claimed = mutableSetOf<String>()
        val groups = mutableListOf<DiscGroup>()
        for ((name, text) in playlists.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            val found = Playlist.entries(text).mapIndexedNotNull { index, entry ->
                byName[entry.lowercase()]?.singleOrNull()?.let { file -> Triple(file, DiscNames.number(entry) ?: (index + 1), entry) }
            }.distinctBy { it.first.id }.filter { it.first.id !in claimed }
            if (found.size < 2 || found.map { it.first.system }.distinct().size != 1 || found.map { it.second }.distinct().size != found.size) continue
            val ordered = found.sortedBy { it.second }.take(DiscNames.MAX)
            groups += DiscGroup(ordered.map { it.first.id }, ordered.map { it.second }, name)
            claimed += ordered.map { it.first.id }
        }
        files.filter { it.id !in claimed && DiscNames.number(it.name) != null }
            .groupBy { it.system to DiscNames.base(it.name).lowercase() }
            .values.forEach { set ->
                val discs = set.distinctBy { it.id }
                val numbers = discs.map { DiscNames.number(it.name)!! }
                if (discs.size < 2 || numbers.distinct().size != numbers.size) return@forEach
                val ordered = discs.zip(numbers).sortedBy { it.second }.take(DiscNames.MAX)
                groups += DiscGroup(ordered.map { it.first.id }, ordered.map { it.second }, null)
                claimed += ordered.map { it.first.id }
            }
        return groups
    }

    /** Disc numbers a set skips: discs 1 and 3 of a game leave 2 not found. */
    fun missing(numbers: List<Int>): List<Int> = if (numbers.isEmpty()) emptyList() else (1..numbers.max()).filter { it !in numbers }
}
