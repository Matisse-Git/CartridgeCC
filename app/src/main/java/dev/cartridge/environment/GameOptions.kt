package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.domain.Core
import dev.cartridge.domain.GameSystem

data class OptionChoice(val value: String, val title: String)

/**
 * A gameplay choice the pinned build really honours. Consoles that share one embedded core share its options;
 * a null [family] means every console. [variable] is the core's own key; null means the frontend applies it,
 * under [key]. [live] options take effect on a paused game; the rest apply at the next start.
 */
data class GameOption(val id: String, val family: Core?, val variable: String?, val title: String, val help: String,
    val choices: List<OptionChoice>, val default: String, val live: Boolean, val key: String = variable ?: GameOptions.SCREEN_EFFECT) {
    init { require(choices.any { it.value == default }) }
    fun title(value: String) = choices.firstOrNull { it.value == value }?.title ?: choices.first { it.value == default }.title
    fun next(value: String, delta: Int = 1): String {
        val index = choices.indexOfFirst { it.value == value }.coerceAtLeast(0)
        return choices[Math.floorMod(index + delta, choices.size)].value
    }
}

object GameOptions {
    /** Session keys consumed by the emulator adapter, never forwarded to a core. */
    const val SCREEN_EFFECT = "cartridge_screen_effect"
    const val START_FROM_STATE = "cartridge_start_from_state"
    val adapterKeys = setOf(SCREEN_EFFECT, START_FROM_STATE)
    private val effects = listOf(OptionChoice("standard", "Standard"), OptionChoice("sharp", "Sharp pixels"),
        OptionChoice("lcd", "Handheld LCD"), OptionChoice("crt", "Television"))
    private val onOff = listOf(OptionChoice("disabled", "Off"), OptionChoice("enabled", "On"))

    val all = listOf(
        GameOption("startFromState", null, null, "Start from save state",
            "Starts a game from its last Save State instead of from the beginning. A game without one starts as usual, and in-game saves are kept as they are.",
            onOff, "disabled", live = false, key = START_FROM_STATE),
        GameOption("handheld.effect", Core.Mgba, null, "Screen effect",
            "How the picture is drawn on this screen. Handheld LCD adds the original pixel grid.", effects, "standard", live = true),
        GameOption("audioFilter", Core.Mgba, "mgba_audio_low_pass_filter", "Audio smoothing",
            "Softens the sharp high notes of the original speaker.", onOff, "disabled", live = true),
        GameOption("frameSkip", Core.Mgba, "mgba_frameskip", "Frame skipping",
            "Draws fewer frames so demanding games keep their speed.",
            listOf(OptionChoice("0", "Off"), OptionChoice("1", "Every other frame"), OptionChoice("2", "Two of every three")), "0", live = true),
        GameOption("playstation.effect", Core.Pcsx, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("playstation.sharp3d", Core.Pcsx, "pcsx_rearmed_neon_enhancement_enable", "Sharper 3D",
            "Draws 3D at twice the original resolution. Demanding games may slow down.", onOff, "disabled", live = false),
        GameOption("playstation.dither", Core.Pcsx, "pcsx_rearmed_dithering", "Colour smoothing",
            "The console's original dither pattern. Turn it off for flatter, cleaner colours.",
            listOf(OptionChoice("enabled", "On"), OptionChoice("disabled", "Off")), "enabled", live = false),
        GameOption("snes.effect", Core.Snes9x, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("snes.sprites", Core.Snes9x, "snes9x_reduce_sprite_flicker", "Reduce sprite flicker",
            "Draws more sprites on a line than the console could. A few games rely on the limit and may show glitches.", onOff, "disabled", live = true),
        GameOption("nes.effect", Core.Fceumm, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("nes.sprites", Core.Fceumm, "fceumm_nospritelimit", "Reduce sprite flicker",
            "Draws more than eight sprites on a line. A few games rely on the limit and may show glitches.", onOff, "disabled", live = true),
        GameOption("sega.effect", Core.GenesisPlusGx, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("sega.sprites", Core.GenesisPlusGx, "genesis_plus_gx_no_sprite_limit", "Reduce sprite flicker",
            "Draws more sprites on a line than the console could. A few games rely on the limit and may show glitches.", onOff, "disabled", live = true),
        GameOption("sega.lowpass", Core.GenesisPlusGx, "genesis_plus_gx_audio_filter", "Audio smoothing",
            "Softens the sound the way the first Genesis model's own output did.",
            listOf(OptionChoice("disabled", "Off"), OptionChoice("low-pass", "On")), "disabled", live = true),
        GameOption("saturn.effect", Core.BeetleSaturn, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("dreamcast.effect", Core.Flycast, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("dreamcast.sharp3d", Core.Flycast, "reicast_internal_resolution", "Sharper 3D",
            "Draws 3D at twice the original resolution. Demanding games may slow down.",
            listOf(OptionChoice("640x480", "Off"), OptionChoice("1280x960", "On")), "640x480", live = false),
        GameOption("ps2.effect", Core.Armsx2, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("ps2.sharp3d", Core.Armsx2, "armsx2_upscale", "Sharper 3D",
            "Draws 3D at twice the original resolution. Demanding games may slow down.",
            listOf(OptionChoice("1x", "Off"), OptionChoice("2x", "On")), "1x", live = false),
        GameOption("gamecube.effect", Core.Dolphin, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("gamecube.sharp3d", Core.Dolphin, "dolphin_efb_scale", "Sharper 3D",
            "Draws 3D at twice the original resolution. Demanding games may slow down.",
            listOf(OptionChoice("1", "Off"), OptionChoice("2", "On")), "1", live = false),
        // Off, the engine shows what the game's graphics processor copied out and never reads the picture back from
        // the console's memory, which is all a program that paints that memory itself has.
        GameOption("gamecube.exactVideo", Core.Dolphin, "dolphin_xfb_to_texture_enable", "Exact video output",
            "Shows the picture as the console's video chip reads it from memory. Homebrew and a few games need it. It is slower.",
            listOf(OptionChoice("enabled", "Off"), OptionChoice("disabled", "On")), "enabled", live = false))

    /** A console's own options and the ones every console shares. */
    fun forFamily(family: Core) = all.filter { (it.family == null || it.family == family) &&
        // Starting from a state is offered only where a state can be made.
        (it.key != START_FROM_STATE || family.snapshots != dev.cartridge.domain.Snapshots.Never) }
    fun forSystem(system: GameSystem) = forFamily(system.core)

    /** Game overrides win over console values, which win over defaults. Unknown stored values fall back. */
    fun resolve(system: GameSystem, console: Map<String, String>, game: Map<String, String> = emptyMap()): Map<GameOption, String> =
        forSystem(system).associateWith { option ->
            listOf(game[option.id], console[option.id]).firstOrNull { value -> value != null && option.choices.any { it.value == value } } ?: option.default
        }

    /** The map handed to the emulator adapter: core variables plus the adapter's own reserved keys. */
    fun session(resolved: Map<GameOption, String>, liveOnly: Boolean = false): Map<String, String> =
        resolved.filterKeys { !liveOnly || it.live }.entries.associate { (option, value) -> option.key to value }
}

/** Console defaults and per-game overrides. The two original mGBA keys keep their backed-up names and types. */
class OptionStore(private val preferences: SharedPreferences) {
    fun console(family: Core): Map<String, String> = GameOptions.forFamily(family).mapNotNull { option ->
        when (option.id) {
            "audioFilter" -> if (preferences.contains(option.id)) option.id to (if (preferences.getBoolean(option.id, false)) "enabled" else "disabled") else null
            "frameSkip" -> if (preferences.contains(option.id)) option.id to preferences.getInt(option.id, 0).coerceIn(0, 2).toString() else null
            else -> preferences.getString("option.${option.id}", null)?.let { option.id to it }
        }
    }.toMap()
    fun game(gameId: String): Map<String, String> = GameOptions.all.mapNotNull { option ->
        preferences.getString(overrideKey(gameId, option.id), null)?.let { option.id to it }
    }.toMap()
    fun resolve(system: GameSystem, gameId: String?) =
        GameOptions.resolve(system, console(system.core), gameId?.let(::game).orEmpty())
    fun setConsole(option: GameOption, value: String) = preferences.edit {
        require(option.choices.any { it.value == value })
        when (option.id) {
            "audioFilter" -> putBoolean(option.id, value == "enabled")
            "frameSkip" -> putInt(option.id, value.toInt())
            else -> putString("option.${option.id}", value)
        }
    }
    /** A null value returns the game to its console's setting. */
    fun setGame(gameId: String, option: GameOption, value: String?) = preferences.edit {
        require(value == null || option.choices.any { it.value == value })
        if (value == null) remove(overrideKey(gameId, option.id)) else putString(overrideKey(gameId, option.id), value)
    }
    fun clearGame(gameId: String) = preferences.edit { GameOptions.all.forEach { remove(overrideKey(gameId, it.id)) } }
    fun resetConsoles() = preferences.edit {
        GameOptions.all.forEach { remove(if (it.id in setOf("audioFilter", "frameSkip")) it.id else "option.${it.id}") }
    }
    private fun overrideKey(gameId: String, optionId: String): String {
        require(gameId.matches(Regex("[a-f0-9]{64}")))
        return "game.$gameId.$optionId"
    }
}
