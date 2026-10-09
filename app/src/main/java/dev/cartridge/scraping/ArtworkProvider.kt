package dev.cartridge.scraping

import dev.cartridge.library.LabelRef
import java.io.File

data class ArtworkCandidate(val type: String, val region: String, val url: String, val checksum: String? = null) {
    val isLabel get() = type == "support-texture"
}
data class GameCandidate(val id: String, val title: String, val platform: String,
    val names: List<String>, val metadata: Map<String, List<String>>, val artwork: List<ArtworkCandidate>,
    val sourceUrl: String, val license: String, val exactHash: Boolean = false)

/** Providers return choices. Only explicit reviewed selections are accepted by the library. */
interface ArtworkProvider {
    val name: String
    suspend fun search(query: String, rom: File? = null): List<GameCandidate>
    suspend fun image(candidate: ArtworkCandidate): ByteArray
}
