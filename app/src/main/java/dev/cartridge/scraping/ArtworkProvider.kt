package dev.cartridge.scraping

import dev.cartridge.library.LabelRef
import java.io.File

data class ArtworkCandidate(val type: String, val region: String, val url: String, val checksum: String? = null, val originalUrl: String? = null) {
    /** Art a game's case is wrapped in (ADR 0029). Everything else is kept only as extra artwork. */
    val isCaseArt get() = type in dev.cartridge.library.CASE_ART
    val isVideo get() = type in VIDEO_TYPES
}
/** Extra images a scrape stores beside the case art: the in-game screenshot, the title screen and fan art. */
val SHOT_TYPES = setOf("ss", "sstitle", "fanart")
/** Gameplay videos, normalized (smaller, consistent) first. */
val VIDEO_TYPES = listOf("video-normalized", "video")
data class GameCandidate(val id: String, val title: String, val platform: String,
    val names: List<String>, val metadata: Map<String, List<String>>, val artwork: List<ArtworkCandidate>,
    val sourceUrl: String, val license: String, val exactHash: Boolean = false,
    val metadataTags: Map<String, List<MetadataTags>> = emptyMap(), val sourceName: String = "",
    /** Gameplay videos on offer. Kept apart from [artwork], which is reviewed and previewed as images. */
    val videos: List<ArtworkCandidate> = emptyList())

/** Providers return choices. Only explicit reviewed selections are accepted by the library. */
interface ArtworkProvider {
    val name: String
    suspend fun search(query: String, rom: File? = null, system: dev.cartridge.domain.GameSystem = dev.cartridge.domain.GameSystem.GBA): List<GameCandidate>
    suspend fun image(candidate: ArtworkCandidate): ByteArray
    /** Writes a large file, a video, to [target] without holding it in memory. Providers without videos copy [image]. */
    suspend fun download(candidate: ArtworkCandidate, target: java.io.File) { target.writeBytes(image(candidate)) }
}
