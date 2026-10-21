package dev.cartridge.storage

import dev.cartridge.domain.GameSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The memory card of a game made of several discs (ADR 0032). A game has one card, the game's own: its battery memory
 * (`<id>.sram`) and, for a core that writes files of its own, its folder (`<id>.files`). Every disc reads and writes it.
 * A disc's own card, from before it joined, is never changed or deleted; it can be copied over the game's, and the card it
 * replaces is kept in `<id>.discs/` so it can be put back. Only ever used with no game running.
 */
class DiscCards(private val saves: File) {
    private val store = SaveStore(saves)
    private fun ram(id: String) = File(saves, "${checked(id)}.sram")
    private fun folder(id: String) = File(saves, "${checked(id)}.files")
    private fun kept(id: String) = File(saves, "${checked(id)}.discs")
    private fun checked(id: String) = id.also { require(it.matches(HASH)) { "Invalid game identifier" } }

    /** The disc or game has a card of its own: battery memory, or files its core wrote. */
    fun has(id: String, system: GameSystem): Boolean =
        ram(id).length() > 0 || system.core.ownFiles && folder(id).listFiles().orEmpty().isNotEmpty()

    /** A game that has no card yet takes the first of [discs] that has one. Returns the disc it took, if any. */
    fun adopt(gameId: String, discs: List<String>, system: GameSystem): String? {
        if (has(gameId, system)) return null
        val source = discs.firstOrNull { it != gameId && has(it, system) } ?: return null
        copyCard(source, gameId, system)
        return source
    }

    /** The game uses [discId]'s card from now on; the card it had is kept to be put back. */
    fun use(gameId: String, discId: String, system: GameSystem) {
        require(gameId != discId && has(discId, system)) { "That disc has no memory card" }
        keep(gameId, system)
        copyCard(discId, gameId, system)
    }

    fun hasPrevious(gameId: String): Boolean = kept(gameId).let { File(it, "previous.sram").isFile || File(it, "previous.files").isDirectory }

    /** The card the game had before goes back in, and the one it replaces is kept in its place: this is its own Undo. */
    fun restorePrevious(gameId: String, system: GameSystem) {
        val keep = kept(gameId)
        val ram = File(keep, "previous.sram"); val files = File(keep, "previous.files")
        check(ram.isFile || files.isDirectory) { "There is no earlier memory card for this game" }
        val swap = File(keep, "swap-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            if (ram(gameId).isFile) Files.copy(ram(gameId).toPath(), File(swap, "previous.sram").toPath())
            if (system.core.ownFiles && folder(gameId).isDirectory) folder(gameId).copyRecursively(File(swap, "previous.files"))
            if (ram.isFile) store.write(gameId, "sram", ram.readBytes()) else ram(gameId).delete()
            if (system.core.ownFiles) replaceFolder(files.takeIf { it.isDirectory }, folder(gameId))
            ram.delete(); files.deleteRecursively()
            File(swap, "previous.sram").takeIf { it.isFile }?.renameTo(ram)
            File(swap, "previous.files").takeIf { it.isDirectory }?.renameTo(files)
        } finally { swap.deleteRecursively() }
    }

    /** The game's current card, kept before another takes its place. An empty game has nothing to keep. */
    private fun keep(gameId: String, system: GameSystem) {
        val keep = kept(gameId).apply { check(isDirectory || mkdirs()) { "Cannot create the save folder" } }
        val ram = File(keep, "previous.sram"); val files = File(keep, "previous.files")
        ram.delete(); files.deleteRecursively()
        if (ram(gameId).isFile) Files.copy(ram(gameId).toPath(), ram.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        if (system.core.ownFiles && folder(gameId).isDirectory) folder(gameId).copyRecursively(files)
    }
    private fun copyCard(from: String, to: String, system: GameSystem) {
        if (ram(from).length() > 0) store.write(to, "sram", checkNotNull(store.read(from, "sram")))
        if (system.core.ownFiles && folder(from).listFiles().orEmpty().isNotEmpty()) replaceFolder(folder(from), folder(to))
    }
    /** [target] becomes a copy of [source], or empty; the copy is made beside it first, so a failure leaves [target] as it was. */
    private fun replaceFolder(source: File?, target: File) {
        val staged = File(saves, "${target.name}.incoming-${System.nanoTime()}")
        try {
            if (source != null) source.copyRecursively(staged) else check(staged.mkdirs())
            val old = File(saves, "${target.name}.outgoing-${System.nanoTime()}")
            if (target.exists()) check(target.renameTo(old)) { "Cannot replace the memory card" }
            if (!staged.renameTo(target)) { old.renameTo(target); error("Cannot replace the memory card") }
            old.deleteRecursively()
        } finally { staged.deleteRecursively() }
    }

    private companion object { val HASH = Regex("[a-f0-9]{64}") }
}
