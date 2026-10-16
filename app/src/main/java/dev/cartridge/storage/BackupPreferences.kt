package dev.cartridge.storage

import android.content.SharedPreferences
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.environment.ConsoleSettings
import dev.cartridge.shaders.ShaderChoices
import org.json.JSONObject

/**
 * Explicit whitelist: no Android roles, grants, credentials or setup flags are portable. Shader choices (ADR 0027)
 * travel as an optional `shaders` object, so a backup made before them still restores.
 */
object BackupPreferences {
    private val booleans = setOf("swapMenuButtons", "swapGameButtons", "swapShoulders", "audioFilter", "reducedMotion", "mediaSound", "alternateShortcut", "autoScan")
    private val keys = booleans + setOf("deadZone", "frameSkip", "layout")
    fun capture(preferences: SharedPreferences): JSONObject = JSONObject().apply {
        booleans.forEach { put(it, preferences.getBoolean(it, it == "autoScan")) }
        put("deadZone", preferences.getFloat("deadZone", .35f)); put("frameSkip", preferences.getInt("frameSkip", 0))
        put("layout", preferences.getString("layout", "Horizontal"))
        put(SHADERS, ShaderChoices(preferences).capture())
    }.also(::validate)
    fun validate(json: JSONObject) {
        require(json.keys().asSequence().toSet().minus(SHADERS) == keys) { "Unsupported backup settings" }
        if (json.has(SHADERS)) ShaderChoices.validate(json.getJSONObject(SHADERS))
        booleans.forEach { require(json.get(it) is Boolean) }
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
        check(editor.commit()) { "Settings could not be stored" }
        if (json.has(SHADERS)) ShaderChoices(preferences).restore(json.getJSONObject(SHADERS))
    }
    private const val SHADERS = "shaders"
}
