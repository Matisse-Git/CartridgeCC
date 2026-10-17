package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.input.ButtonCombo

/** Small supported choices, independent from canonical games and Android's system roles. */
data class ConsoleSettings(
    val swapMenuButtons: Boolean = false,
    val swapGameButtons: Boolean = false,
    val swapShoulders: Boolean = false,
    val deadZone: Float = .35f,
    val audioFilter: Boolean = false,
    val frameSkip: Int = 0,
    /** Held together during a game (docs/SAVE_RELIABILITY). Android key codes, as the controller sends them. */
    val quickSave: Set<Int> = ButtonCombo.QUICK_SAVE,
    val quickLoad: Set<Int> = ButtonCombo.QUICK_LOAD,
    /** Quick Save, Quick Load and Load in the pause menu go straight back to the game, or leave the menu open. */
    val returnToGame: Boolean = true,
) {
    init { require(deadZone in .15f.. .65f && frameSkip in 0..2) }
    fun coreOptions() = mapOf("mgba_audio_low_pass_filter" to if (audioFilter) "enabled" else "disabled",
        "mgba_frameskip" to frameSkip.toString())
}

class ConsolePreferences(private val preferences: SharedPreferences) {
    fun read() = ConsoleSettings(
        preferences.getBoolean("swapMenuButtons", false), preferences.getBoolean("swapGameButtons", false),
        preferences.getBoolean("swapShoulders", false), preferences.getFloat("deadZone", .35f).coerceIn(.15f, .65f),
        preferences.getBoolean("audioFilter", false), preferences.getInt("frameSkip", 0).coerceIn(0, 2),
        ButtonCombo.decode(preferences.getString("quickSaveCombo", null), ButtonCombo.QUICK_SAVE),
        ButtonCombo.decode(preferences.getString("quickLoadCombo", null), ButtonCombo.QUICK_LOAD),
        preferences.getBoolean("returnToGame", true)).let {
            // Two stored combos that would press each other (an older build, a hand-edited file) give way to the defaults.
            if (ButtonCombo.problem(it.quickSave, it.quickLoad) != null) it.copy(quickSave = ButtonCombo.QUICK_SAVE, quickLoad = ButtonCombo.QUICK_LOAD) else it
        }
    fun write(value: ConsoleSettings) = preferences.edit {
        putBoolean("swapMenuButtons", value.swapMenuButtons); putBoolean("swapGameButtons", value.swapGameButtons)
        putBoolean("swapShoulders", value.swapShoulders); putFloat("deadZone", value.deadZone)
        putBoolean("audioFilter", value.audioFilter); putInt("frameSkip", value.frameSkip)
        putString("quickSaveCombo", ButtonCombo.encode(value.quickSave)); putString("quickLoadCombo", ButtonCombo.encode(value.quickLoad))
        putBoolean("returnToGame", value.returnToGame)
    }
}
