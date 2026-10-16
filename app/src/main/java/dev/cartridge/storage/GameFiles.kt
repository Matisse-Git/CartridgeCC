package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import java.io.File

/** A disc needs its actual extension and neighbouring tracks, kept in a bundle; single-file paths remain compatible. */
object GameFiles {
    fun resolve(directory: File, id: String, system: GameSystem): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        if (!system.bundle) return File(directory, "$id.${system.extension}")
        val bundle = File(directory, "$id.${system.extension}")
        val extensions = system.formats.map { it.extension }.sorted()
        return extensions.map { File(bundle, "game.$it") }.firstOrNull(File::isFile) ?: File(bundle, "game.${extensions.first()}")
    }
}
