package dev.cartridge.rendering

import android.content.res.AssetManager
import dev.cartridge.domain.Media

/** Loaded on IO once per Activity and reused across renderer recreation. No GPU objects here. [models] is in [Media] order. */
class CollectionAssets(val models: List<ByteArray>, val studio: ByteArray) {
    companion object {
        fun read(assets: AssetManager) = CollectionAssets(
            Media.entries.map { media -> assets.open("models/${media.model}.glb").use { it.readBytes() } },
            assets.open("lighting/studio_ibl.ktx").use { it.readBytes() })
    }
}
