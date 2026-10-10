package dev.cartridge.domain

/**
 * How the screen closes when a game starts and opens again on the game, or on the library afterwards.
 * Always black, so the stage can change hands behind it unseen.
 */
enum class LaunchTransition(val title: String) {
    /** The whole picture dims to black and returns. */
    Fade("Fade"),
    /** A circle closes on the centre of the screen and opens from it. */
    Iris("Iris"),
    /** Two edges meet across the middle and part again. */
    Shutter("Shutter");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Fade }
}
