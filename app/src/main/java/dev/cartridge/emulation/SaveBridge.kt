package dev.cartridge.emulation

import androidx.annotation.Keep

/** GL-thread only. Uses the already loaded core's public Libretro memory/serialization ABI. */
@Keep internal object SaveBridge {
    init { System.loadLibrary("cartridge_saves") }
    /** The core's own thread, as its session ends: unloads a core that draws for itself where it ran. */
    external fun unload(corePath: String)
    external fun read(corePath: String, memoryId: Int = 0): ByteArray
    external fun restore(corePath: String, bytes: ByteArray, memoryId: Int = 0): Boolean
    external fun captureState(corePath: String): ByteArray
    external fun frameCount(corePath: String): Long
    external fun audioFrames(corePath: String): Long
    /**
     * Diagnostic counters of the shared shim since the session began: host runs, core runs, drawn, repeated and
     * silent frames, audio frames in, audio calls, calls from another thread, the largest call, audio frames
     * out, framebuffer binds, the last picture's size as width shl 16 or height, and audio frames dropped.
     */
    external fun stats(corePath: String): LongArray
    /** Diagnostic, any thread: every thread inside a core or the host writes its native stack to the log (tag CartridgeStacks). */
    external fun dumpThreads()
}
