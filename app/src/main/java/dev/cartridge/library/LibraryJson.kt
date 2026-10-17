package dev.cartridge.library

import org.json.JSONObject

/** Version-2 accepted selections only; candidate API responses never enter the ROM database. */
internal object LibraryJson {
    fun provenance(value: AssetProvenance?): String? = value?.let { JSONObject()
        .put("provider", it.provider).put("recordId", it.recordId).put("type", it.type).put("region", it.region)
        .put("url", it.url).put("license", it.license).put("acceptedAt", it.acceptedAt).apply { if (it.automatic) put("automatic", true) }.toString() }
    fun provenance(json: String?): AssetProvenance? = json?.let { JSONObject(it) }?.let {
        AssetProvenance(it.getString("provider"), it.getString("recordId"), it.getString("type"), it.getString("region"),
            it.getString("url"), it.getString("license"), it.getLong("acceptedAt"), it.optBoolean("automatic", false)) }
    fun metadata(values: Map<String, MetadataValue>): String = JSONObject().apply {
        values.forEach { (field, value) -> put(field, JSONObject().put("value", value.value).put("provider", value.provider)
            .put("recordId", value.recordId).put("url", value.url).put("license", value.license).put("acceptedAt", value.acceptedAt)
            .apply { if (value.automatic) put("automatic", true) }) }
    }.toString()
    fun metadata(json: String?): Map<String, MetadataValue> = if (json == null) emptyMap() else JSONObject(json).let { root ->
        root.keys().asSequence().associateWith { field -> root.getJSONObject(field).let {
            MetadataValue(it.getString("value"), it.getString("provider"), it.getString("recordId"), it.getString("url"),
                it.getString("license"), it.getLong("acceptedAt"), it.optBoolean("automatic", false)) } }
    }
    fun media(values: Map<String, MediaSelection>): String = JSONObject().apply {
        values.forEach { (type, media) -> put(type, JSONObject().put("asset", media.image.assetId).put("region", media.region)
            .put("source", media.image.sourceUri).put("provenance", provenance(media.image.provenance))) }
    }.toString()
    fun media(json: String?): Map<String, MediaSelection> = if (json == null) emptyMap() else JSONObject(json).let { root ->
        root.keys().asSequence().associateWith { type -> root.getJSONObject(type).let {
            MediaSelection(LabelRef(it.getString("asset"), sourceUri = it.optString("source").takeIf(String::isNotEmpty),
                provenance = provenance(it.optString("provenance").takeIf(String::isNotEmpty))), type, it.getString("region")) } }
    }
}
