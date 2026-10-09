package dev.cartridge.library

data class LibraryBackup(val games: List<GameRecord>, val order: List<String>, val removed: Set<String>, val state: CollectionRecord)
interface BackupRepository {
    suspend fun exportLibrary(): LibraryBackup
    /** Existing membership, source, artwork and metadata win. History uses maxima, never addition. */
    suspend fun mergeLibrary(backup: LibraryBackup): Int
}
