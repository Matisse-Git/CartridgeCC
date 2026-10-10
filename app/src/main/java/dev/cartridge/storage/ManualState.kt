package dev.cartridge.storage

import dev.cartridge.domain.GameSystem

/** Existing mGBA states stay byte compatible; the first PS1 states are explicitly revision bound. */
object ManualState {
    private fun prefix(system: GameSystem) = "Cartridge state 1\n${system.coreRevision}\n".toByteArray(Charsets.US_ASCII)
    fun encode(system: GameSystem, bytes: ByteArray): ByteArray {
        if (system != GameSystem.PS1) return bytes
        require(bytes.isNotEmpty() && bytes.size + prefix(system).size <= SessionStore.MAX_STATE)
        return prefix(system) + bytes
    }
    fun decode(system: GameSystem, bytes: ByteArray): ByteArray {
        if (system != GameSystem.PS1) return bytes
        val tag=prefix(system)
        require(bytes.size > tag.size && bytes.copyOfRange(0,tag.size).contentEquals(tag)) { "This PS1 state uses a different core build. The original file was kept." }
        return bytes.copyOfRange(tag.size,bytes.size)
    }
}
