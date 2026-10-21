package dev.cartridge.library

/** [discs] and [separated] are the discs of games made of several, and discs separated by hand (ADR 0032; backup version 2). */
data class LibraryBackup(val games: List<GameRecord>, val order: List<String>, val removed: Set<String>, val state: CollectionRecord,
    val discs: List<DiscRecord> = emptyList(), val separated: Set<String> = emptySet())
interface BackupRepository {
    suspend fun exportLibrary(): LibraryBackup
    /** Existing membership, source, artwork and metadata win. History uses maxima, never addition. */
    suspend fun mergeLibrary(backup: LibraryBackup): Int
}
