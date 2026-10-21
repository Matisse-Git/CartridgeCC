package dev.cartridge.library

/**
 * [automatic] marks a choice a scrape applied without the user picking it (ADR 0029). Fill all may replace such a
 * choice; one the user made is replaced only when they also tick "Also replace my manual choices".
 */
data class AssetProvenance(val provider: String, val recordId: String, val type: String,
    val region: String, val url: String, val license: String, val acceptedAt: Long, val automatic: Boolean = false)
data class MetadataValue(val value: String, val provider: String, val recordId: String,
    val url: String, val license: String, val acceptedAt: Long, val automatic: Boolean = false)

/** The kinds of artwork a game's case is wrapped in: the whole unfolded cover, or only its front (ADR 0029). */
val CASE_ART = setOf("box-texture", "box-2D")
/**
 * True when this image is case art: scraped box art, or an image the user imported themselves, which is their
 * front cover. A label scraped for a cartridge or disc is kept, but is not drawn on a case and never counts as one.
 */
val LabelRef.isCaseArt get() = provenance == null || provenance.type in CASE_ART

/** How much an automatic scrape may change (ADR 0029). Names are shown; append, never rename. */
enum class FillMode(val title: String) { Missing("Fill missing"), All("Fill all") }
data class FillPolicy(val mode: FillMode = FillMode.Missing, val replaceManual: Boolean = false) {
    /** Whether a value may be written over what is there: nothing ([existingAutomatic] null), an automatic choice or the user's. */
    fun replaces(existingAutomatic: Boolean?) = existingAutomatic == null || mode == FillMode.All && (existingAutomatic || replaceManual)
}

/** The details a scrape may fill, in the order the Details page shows them. */
val METADATA_FIELDS = setOf("title", "year", "developer", "publisher", "genre", "players", "description")
data class MediaSelection(val image: LabelRef, val type: String, val region: String)
