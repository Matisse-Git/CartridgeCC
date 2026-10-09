package dev.cartridge.environment

import android.content.SharedPreferences
import androidx.core.content.edit

/** Small supported choices, independent from canonical games and Android's system roles. */
data class ConsoleSettings(
    val swapMenuButtons: Boolean = false,
    val swapGameButtons: Boolean = false,
    val swapShoulders: Boolean = false,
    val deadZone: Float = .35f,
    val audioFilter: Boolean = false,
    val frameSkip: Int = 0,
) {
    init { require(deadZone in .15f.. .65f && frameSkip in 0..2) }
    fun coreOptions() = mapOf("mgba_audio_low_pass_filter" to if (audioFilter) "enabled" else "disabled",
        "mgba_frameskip" to frameSkip.toString())
}

class ConsolePreferences(private val preferences: SharedPreferences) {
    fun read() = ConsoleSettings(
        preferences.getBoolean("swapMenuButtons", false), preferences.getBoolean("swapGameButtons", false),
        preferences.getBoolean("swapShoulders", false), preferences.getFloat("deadZone", .35f).coerceIn(.15f, .65f),
        preferences.getBoolean("audioFilter", false), preferences.getInt("frameSkip", 0).coerceIn(0, 2))
    fun write(value: ConsoleSettings) = preferences.edit {
        putBoolean("swapMenuButtons", value.swapMenuButtons); putBoolean("swapGameButtons", value.swapGameButtons)
        putBoolean("swapShoulders", value.swapShoulders); putFloat("deadZone", value.deadZone)
        putBoolean("audioFilter", value.audioFilter); putInt("frameSkip", value.frameSkip)
    }
}
