package dev.cartridge.library

data class AssetProvenance(val provider: String, val recordId: String, val type: String,
    val region: String, val url: String, val license: String, val acceptedAt: Long)
data class MetadataValue(val value: String, val provider: String, val recordId: String,
    val url: String, val license: String, val acceptedAt: Long)
data class MediaSelection(val image: LabelRef, val type: String, val region: String)
