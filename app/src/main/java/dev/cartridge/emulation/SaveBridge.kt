package dev.cartridge.emulation

import androidx.annotation.Keep

/** GL-thread only. Uses the already loaded core's public Libretro memory/serialization ABI. */
@Keep internal object SaveBridge {
    init { System.loadLibrary("cartridge_saves") }
    external fun read(corePath: String): ByteArray
    external fun restore(corePath: String, bytes: ByteArray): Boolean
    external fun captureState(corePath: String): ByteArray
}
