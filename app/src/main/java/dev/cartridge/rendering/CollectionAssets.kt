package dev.cartridge.rendering

import android.content.res.AssetManager

/** Loaded on IO once per Activity and reused across renderer recreation. No GPU objects here. */
data class CollectionAssets(val cartridge: ByteArray, val receiver: ByteArray, val studio: ByteArray) {
    companion object {
        fun read(assets: AssetManager) = CollectionAssets(
            assets.open("models/gba_cartridge.glb").use { it.readBytes() },
            assets.open("models/gba_receiver.glb").use { it.readBytes() },
            assets.open("lighting/studio_ibl.ktx").use { it.readBytes() })
    }
}
