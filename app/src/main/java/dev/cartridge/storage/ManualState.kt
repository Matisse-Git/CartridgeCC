package dev.cartridge.storage

import dev.cartridge.domain.GameSystem

/** Existing mGBA states stay byte compatible; every later core's states are explicitly revision bound. */
object ManualState {
    private fun prefix(system: GameSystem) = "Cartridge state 1\n${system.coreRevision}\n".toByteArray(Charsets.US_ASCII)
    fun encode(system: GameSystem, bytes: ByteArray): ByteArray {
        if (!system.core.stateEnvelope) return bytes
        require(bytes.isNotEmpty() && bytes.size + prefix(system).size <= SessionStore.MAX_STATE)
        return prefix(system) + bytes
    }
    /** Whether a state that begins with [header] was made by this console's current core build, as far as its bytes can say. */
    fun compatible(system: GameSystem, header: ByteArray): Boolean {
        if (!system.core.stateEnvelope) return true
        val tag = prefix(system)
        return header.size > tag.size && header.copyOfRange(0, tag.size).contentEquals(tag)
    }
    fun decode(system: GameSystem, bytes: ByteArray): ByteArray {
        if (!system.core.stateEnvelope) return bytes
        val tag=prefix(system)
        require(bytes.size > tag.size && bytes.copyOfRange(0,tag.size).contentEquals(tag)) { "This ${if (system == GameSystem.PS1) "PS1" else system.title} state uses a different core build. The original file was kept." }
        return bytes.copyOfRange(tag.size,bytes.size)
    }
}
