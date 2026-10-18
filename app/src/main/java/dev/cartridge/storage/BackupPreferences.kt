package dev.cartridge.storage

import android.content.SharedPreferences
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.environment.ConsolePreferences
import dev.cartridge.environment.ConsoleSettings
import dev.cartridge.shaders.ShaderChoices
import org.json.JSONObject

/**
 * Explicit whitelist: no Android roles, grants, credentials or setup flags are portable. Shader choices (ADR 0027)
 * and each console's game buttons travel as optional `shaders` and `buttons` objects, so a backup made before them
 * still restores. A backup's every-console A/B and L/R swaps, which the console buttons replaced, are accepted and ignored.
 */
object BackupPreferences {
    private val booleans = setOf("swapMenuButtons", "audioFilter", "reducedMotion", "mediaSound", "alternateShortcut", "autoScan")
    private val keys = booleans + setOf("deadZone", "frameSkip", "layout")
    private val retired = setOf("swapGameButtons", "swapShoulders")
    fun capture(preferences: SharedPreferences): JSONObject = JSONObject().apply {
        booleans.forEach { put(it, preferences.getBoolean(it, it == "autoScan")) }
        put("deadZone", preferences.getFloat("deadZone", .35f)); put("frameSkip", preferences.getInt("frameSkip", 0))
        put("layout", preferences.getString("layout", "Horizontal"))
        put(SHADERS, ShaderChoices(preferences).capture())
        put(BUTTONS, JSONObject().apply {
            ConsolePreferences(preferences).read().buttons.forEach { (system, map) -> put(system.name, ButtonMap.encode(map)) }
        })
    }.also(::validate)
    fun validate(json: JSONObject) {
        require(json.keys().asSequence().toSet().minus(setOf(SHADERS, BUTTONS) + retired) == keys) { "Unsupported backup settings" }
        if (json.has(SHADERS)) ShaderChoices.validate(json.getJSONObject(SHADERS))
        if (json.has(BUTTONS)) json.getJSONObject(BUTTONS).let { buttons ->
            buttons.keys().forEach { name ->
                val system = GameSystem.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unsupported console")
                require(ButtonMap.decode(system, buttons.get(name) as? String) != null) { "Unsupported buttons for ${system.title}" }
            }
        }
        (booleans + retired.filter(json::has)).forEach { require(json.get(it) is Boolean) }
        ConsoleSettings(deadZone = json.getDouble("deadZone").toFloat(), frameSkip = json.getInt("frameSkip"))
        require(json.getDouble("frameSkip") == json.getInt("frameSkip").toDouble())
        CollectionLayout.valueOf(json.getString("layout"))
    }
    fun restore(preferences: SharedPreferences, json: JSONObject) {
        validate(json)
        val editor = preferences.edit()
        booleans.forEach { editor.putBoolean(it, json.getBoolean(it)) }
        editor.putFloat("deadZone", json.getDouble("deadZone").toFloat()).putInt("frameSkip", json.getInt("frameSkip"))
            .putString("layout", json.getString("layout"))
        // A console the backup names takes its buttons; every other console keeps the ones it has.
        json.optJSONObject(BUTTONS)?.let { buttons -> buttons.keys().forEach { editor.putString(ConsolePreferences.buttonsKey(GameSystem.valueOf(it)), buttons.getString(it)) } }
        check(editor.commit()) { "Settings could not be stored" }
        if (json.has(SHADERS)) ShaderChoices(preferences).restore(json.getJSONObject(SHADERS))
    }
    private const val SHADERS = "shaders"
    private const val BUTTONS = "buttons"
}
