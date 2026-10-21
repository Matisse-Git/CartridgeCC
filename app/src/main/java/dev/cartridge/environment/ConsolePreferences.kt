package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.PadKey
import dev.cartridge.input.ButtonCombo
import dev.cartridge.input.QuickCombo

/** Small supported choices, independent from canonical games and Android's system roles. */
data class ConsoleSettings(
    /** Menus only: B confirms and A goes back. Never what a game is sent. */
    val swapMenuButtons: Boolean = false,
    val deadZone: Float = .35f,
    val audioFilter: Boolean = false,
    val frameSkip: Int = 0,
    /** Held together during a game (docs/SAVE_RELIABILITY, ADR 0031). Android key codes, as the controller sends them. */
    val combos: Map<QuickCombo, Set<Int>> = ButtonCombo.DEFAULTS,
    /** Frames run for each one shown while fast-forward is on: one speed for every console (ADR 0031). */
    val fastForwardSpeed: Int = DEFAULT_SPEED,
    /** Quick Save, Quick Load and Load in the pause menu go straight back to the game, or leave the menu open. */
    val returnToGame: Boolean = true,
    /** The consoles whose buttons the user changed (ButtonMap); every other console has its default. */
    val buttons: Map<GameSystem, Map<PadKey, PadKey>> = emptyMap(),
) {
    init { require(deadZone in .15f.. .65f && frameSkip in 0..2 && fastForwardSpeed in SPEEDS) }
    fun combo(action: QuickCombo) = combos[action] ?: action.default
    fun withCombo(action: QuickCombo, combo: Set<Int>) = copy(combos = combos + (action to combo))
    val quickSave get() = combo(QuickCombo.Save)
    val quickLoad get() = combo(QuickCombo.Load)
    fun coreOptions() = mapOf("mgba_audio_low_pass_filter" to if (audioFilter) "enabled" else "disabled",
        "mgba_frameskip" to frameSkip.toString())
    fun buttons(system: GameSystem): Map<PadKey, PadKey> = buttons[system] ?: ButtonMap.default(system)
    /** A map equal to the default is not kept, so the console follows its default from then on. */
    fun withButtons(system: GameSystem, map: Map<PadKey, PadKey>) =
        copy(buttons = if (map == ButtonMap.default(system)) buttons - system else buttons + (system to map))
    companion object {
        const val DEFAULT_SPEED = 2
        /** Whole steps: the host runs a core a whole number of times per frame shown. */
        val SPEEDS = 2..8
    }
}

class ConsolePreferences(private val preferences: SharedPreferences) {
    fun read() = ConsoleSettings(
        swapMenuButtons = preferences.getBoolean("swapMenuButtons", false), deadZone = preferences.getFloat("deadZone", .35f).coerceIn(.15f, .65f),
        audioFilter = preferences.getBoolean("audioFilter", false), frameSkip = preferences.getInt("frameSkip", 0).coerceIn(0, 2),
        combos = QuickCombo.entries.associateWith { ButtonCombo.decode(preferences.getString(it.key, null), it.default) },
        fastForwardSpeed = preferences.getInt("fastForwardSpeed", ConsoleSettings.DEFAULT_SPEED).coerceIn(ConsoleSettings.SPEEDS),
        returnToGame = preferences.getBoolean("returnToGame", true),
        // A stored map that no longer fits its console (an older build, a hand-edited file) gives way to the default.
        buttons = GameSystem.entries.mapNotNull { system -> ButtonMap.decode(system, preferences.getString(buttonsKey(system), null))?.let { system to it } }.toMap()).let {
            // Stored combos that would press each other (an older build, a hand-edited file) give way to the defaults.
            if (ButtonCombo.conflict(it.combos) != null) it.copy(combos = ButtonCombo.DEFAULTS) else it
        }
    fun write(value: ConsoleSettings) = preferences.edit {
        putBoolean("swapMenuButtons", value.swapMenuButtons); putFloat("deadZone", value.deadZone)
        putBoolean("audioFilter", value.audioFilter); putInt("frameSkip", value.frameSkip)
        QuickCombo.entries.forEach { putString(it.key, ButtonCombo.encode(value.combo(it))) }
        putInt("fastForwardSpeed", value.fastForwardSpeed)
        putBoolean("returnToGame", value.returnToGame)
        GameSystem.entries.forEach { system ->
            value.buttons[system]?.let { putString(buttonsKey(system), ButtonMap.encode(it)) } ?: remove(buttonsKey(system))
        }
        // The every-console swaps the console maps replaced; their values are not carried over.
        remove("swapGameButtons"); remove("swapShoulders")
    }
    companion object {
        fun buttonsKey(system: GameSystem) = "buttons.${system.name}"
    }
}
