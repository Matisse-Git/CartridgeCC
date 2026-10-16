package dev.cartridge.storage

/**
 * The files a disc's sheet names are that disc's tracks wherever they lie, never games of their own. A `.bin`
 * was always passed over by its extension; an older GD-ROM dump keeps its data tracks as `.iso`, which on its
 * own would be a PlayStation disc.
 */
object SheetTracks {
    /** Lower-cased names from a CUE or GDI's text. A sheet that cannot be read names nothing; importing it says why. */
    fun named(sheet: String, text: String): Set<String> = try {
        (if (sheet.endsWith(".gdi", ignoreCase = true)) GdiSheet.parse(text).files else CueSheet.parse(text).files).map { it.lowercase() }.toSet()
    } catch (failure: RuntimeException) { emptySet() }

    private val numbered = Regex("track\\d+\\.(iso|bin|raw)", RegexOption.IGNORE_CASE)
    /** For a count made from names alone: beside a GDI, a file numbered as a track is one. */
    fun likely(name: String, beside: Collection<String>) = numbered.matches(name) && beside.any { it.endsWith(".gdi", ignoreCase = true) }
}
