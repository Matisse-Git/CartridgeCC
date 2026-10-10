package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import java.io.File

/** PS1 needs its actual extension and neighbouring tracks; cartridge paths remain compatible. */
object GameFiles {
    fun resolve(directory: File, id: String, system: GameSystem): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        if (system != GameSystem.PS1) return File(directory, "$id.${system.extension}")
        val bundle = File(directory, "$id.ps1")
        return GameSystem.discExtensions.sorted().map { File(bundle, "game.$it") }.firstOrNull(File::isFile)
            ?: File(bundle, "game.chd")
    }
}
