package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.PadKey
import dev.cartridge.input.ButtonCombo

/** Small supported choices, independent from canonical games and Android's system roles. */
data class ConsoleSettings(
    /** Menus only: B confirms and A goes back. Never what a game is sent. */
    val swapMenuButtons: Boolean = false,
    val deadZone: Float = .35f,
    val audioFilter: Boolean = false,
    val frameSkip: Int = 0,
    /** Held together during a game (docs/SAVE_RELIABILITY). Android key codes, as the controller sends them. */
    val quickSave: Set<Int> = ButtonCombo.QUICK_SAVE,
    val quickLoad: Set<Int> = ButtonCombo.QUICK_LOAD,
    /** Quick Save, Quick Load and Load in the pause menu go straight back to the game, or leave the menu open. */
    val returnToGame: Boolean = true,
    /** The consoles whose buttons the owner changed (ButtonMap); every other console has its default. */
    val buttons: Map<GameSystem, Map<PadKey, PadKey>> = emptyMap(),
) {
    init { require(deadZone in .15f.. .65f && frameSkip in 0..2) }
    fun coreOptions() = mapOf("mgba_audio_low_pass_filter" to if (audioFilter) "enabled" else "disabled",
        "mgba_frameskip" to frameSkip.toString())
    fun buttons(system: GameSystem): Map<PadKey, PadKey> = buttons[system] ?: ButtonMap.default(system)
    /** A map equal to the default is not kept, so the console follows its default from then on. */
    fun withButtons(system: GameSystem, map: Map<PadKey, PadKey>) =
        copy(buttons = if (map == ButtonMap.default(system)) buttons - system else buttons + (system to map))
}

class ConsolePreferences(private val preferences: SharedPreferences) {
    fun read() = ConsoleSettings(
        swapMenuButtons = preferences.getBoolean("swapMenuButtons", false), deadZone = preferences.getFloat("deadZone", .35f).coerceIn(.15f, .65f),
        audioFilter = preferences.getBoolean("audioFilter", false), frameSkip = preferences.getInt("frameSkip", 0).coerceIn(0, 2),
        quickSave = ButtonCombo.decode(preferences.getString("quickSaveCombo", null), ButtonCombo.QUICK_SAVE),
        quickLoad = ButtonCombo.decode(preferences.getString("quickLoadCombo", null), ButtonCombo.QUICK_LOAD),
        returnToGame = preferences.getBoolean("returnToGame", true),
        // A stored map that no longer fits its console (an older build, a hand-edited file) gives way to the default.
        buttons = GameSystem.entries.mapNotNull { system -> ButtonMap.decode(system, preferences.getString(buttonsKey(system), null))?.let { system to it } }.toMap()).let {
            // Two stored combos that would press each other (an older build, a hand-edited file) give way to the defaults.
            if (ButtonCombo.problem(it.quickSave, it.quickLoad) != null) it.copy(quickSave = ButtonCombo.QUICK_SAVE, quickLoad = ButtonCombo.QUICK_LOAD) else it
        }
    fun write(value: ConsoleSettings) = preferences.edit {
        putBoolean("swapMenuButtons", value.swapMenuButtons); putFloat("deadZone", value.deadZone)
        putBoolean("audioFilter", value.audioFilter); putInt("frameSkip", value.frameSkip)
        putString("quickSaveCombo", ButtonCombo.encode(value.quickSave)); putString("quickLoadCombo", ButtonCombo.encode(value.quickLoad))
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
