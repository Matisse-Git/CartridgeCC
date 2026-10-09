package dev.cartridge.emulation

import androidx.annotation.Keep

/** GL-thread only. Uses the already loaded core's public Libretro memory/serialization ABI. */
@Keep internal object SaveBridge {
    init { System.loadLibrary("cartridge_saves") }
    external fun read(corePath: String, memoryId: Int = 0): ByteArray
    external fun restore(corePath: String, bytes: ByteArray, memoryId: Int = 0): Boolean
    external fun captureState(corePath: String): ByteArray
    external fun frameCount(corePath: String): Long
}
