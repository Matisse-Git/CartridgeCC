package dev.cartridge.scraping

data class MetadataTags(val region: String = "", val language: String = "")
data class ScrapeChoice(val code: String, val title: String)

/** Preferences rank alternatives; they never remove a region/language or accept a choice. */
data class ScrapePreferences(val region: String = "us", val language: String = "en") {
    init { require(regions.any { it.code == region }); require(languages.any { it.code == language }) }
    fun rank(candidate: GameCandidate): GameCandidate {
        val metadata = linkedMapOf<String, List<String>>()
        val tags = linkedMapOf<String, List<MetadataTags>>()
        candidate.metadata.forEach { (field, values) ->
            val sourceTags = candidate.metadataTags[field].orEmpty()
            val positions = values.indices.sortedBy { i ->
                val tag = sourceTags.getOrNull(i) ?: MetadataTags()
                if (tag.language.isNotEmpty()) languageRank(tag.language) else regionRank(tag.region)
            }
            metadata[field] = positions.map { values[it] }
            tags[field] = positions.map { sourceTags.getOrNull(it) ?: MetadataTags() }
        }
        return candidate.copy(metadata = metadata, metadataTags = tags, artwork = candidate.artwork.sortedWith(
            compareByDescending<ArtworkCandidate> { it.isCaseArt }.thenBy { regionRank(it.region) }))
    }
    private fun regionRank(value: String): Int = when {
        region == "any" -> 0
        value.equals(region, true) -> 0
        value.equals("wor", true) -> 1
        value.isBlank() -> 2
        else -> 3
    }
    private fun languageRank(value: String): Int = when {
        language == "any" -> 0
        value.equals(language, true) || language == "ja" && value.equals("jp", true) -> 0
        value.equals("en", true) -> 1
        value.isBlank() -> 2
        else -> 3
    }
    companion object {
        val regions = listOf(ScrapeChoice("us", "United States"), ScrapeChoice("eu", "Europe"), ScrapeChoice("jp", "Japan"), ScrapeChoice("wor", "Worldwide"), ScrapeChoice("any", "Any region"))
        val languages = listOf(ScrapeChoice("en", "English"), ScrapeChoice("fr", "French"), ScrapeChoice("de", "German"), ScrapeChoice("es", "Spanish"), ScrapeChoice("it", "Italian"), ScrapeChoice("pt", "Portuguese"), ScrapeChoice("ja", "Japanese"), ScrapeChoice("any", "Any language"))
    }
}

interface ScrapePreferenceStore {
    suspend fun load(): ScrapePreferences
    suspend fun save(preferences: ScrapePreferences)
}
class MemoryScrapePreferences : ScrapePreferenceStore {
    private var value = ScrapePreferences()
    override suspend fun load() = value
    override suspend fun save(preferences: ScrapePreferences) { value = preferences }
}
