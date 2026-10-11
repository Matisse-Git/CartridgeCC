package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.domain.GameSystem

/** Consoles that share one embedded core share one set of options. */
enum class CoreFamily(val title: String) {
    Handheld("Game Boy · Color · Advance"), PlayStation("PlayStation");
    companion object { fun of(system: GameSystem) = if (system == GameSystem.PS1) PlayStation else Handheld }
}

data class OptionChoice(val value: String, val title: String)

/**
 * A gameplay choice the pinned build really honours. A null [family] means every console. [variable] is the
 * core's own key; null means the frontend applies it, under [key]. [live] options take effect on a paused
 * game; the rest apply at the next start.
 */
data class GameOption(val id: String, val family: CoreFamily?, val variable: String?, val title: String, val help: String,
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
        GameOption("handheld.effect", CoreFamily.Handheld, null, "Screen effect",
            "How the picture is drawn on this screen. Handheld LCD adds the original pixel grid.", effects, "standard", live = true),
        GameOption("audioFilter", CoreFamily.Handheld, "mgba_audio_low_pass_filter", "Audio smoothing",
            "Softens the sharp high notes of the original speaker.", onOff, "disabled", live = true),
        GameOption("frameSkip", CoreFamily.Handheld, "mgba_frameskip", "Frame skipping",
            "Draws fewer frames so demanding games keep their speed.",
            listOf(OptionChoice("0", "Off"), OptionChoice("1", "Every other frame"), OptionChoice("2", "Two of every three")), "0", live = true),
        GameOption("playstation.effect", CoreFamily.PlayStation, null, "Screen effect",
            "How the picture is drawn on this screen. Television adds soft scanlines.", effects, "standard", live = true),
        GameOption("playstation.sharp3d", CoreFamily.PlayStation, "pcsx_rearmed_neon_enhancement_enable", "Sharper 3D",
            "Draws 3D at twice the original resolution. Demanding games may slow down.", onOff, "disabled", live = false),
        GameOption("playstation.dither", CoreFamily.PlayStation, "pcsx_rearmed_dithering", "Colour smoothing",
            "The console's original dither pattern. Turn it off for flatter, cleaner colours.",
            listOf(OptionChoice("enabled", "On"), OptionChoice("disabled", "Off")), "enabled", live = false))

    /** A console's own options and the ones every console shares. */
    fun forFamily(family: CoreFamily) = all.filter { it.family == null || it.family == family }
    fun forSystem(system: GameSystem) = forFamily(CoreFamily.of(system))

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
    fun console(family: CoreFamily): Map<String, String> = GameOptions.forFamily(family).mapNotNull { option ->
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
        GameOptions.resolve(system, console(CoreFamily.of(system)), gameId?.let(::game).orEmpty())
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
