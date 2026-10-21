package dev.cartridge.shaders

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cartridge.domain.GameSystem
import org.json.JSONObject

/** What one level says (ADR 0027): nothing, so the level above decides; no shader; or a preset of the library. */
sealed interface ShaderChoice {
    data object Inherit : ShaderChoice
    data object Off : ShaderChoice
    /** [path] is relative to the installed library, as `crt/crt-royale.glslp`. */
    data class Preset(val path: String) : ShaderChoice

    companion object {
        fun decode(value: String?): ShaderChoice = when {
            value == null -> Inherit
            value == OFF -> Off
            value.startsWith(PRESET) && ShaderLibraryUpdater.wanted(value.removePrefix(PRESET)) && value.endsWith(".glslp") -> Preset(value.removePrefix(PRESET))
            else -> Inherit
        }
        fun encode(choice: ShaderChoice): String? = when (choice) {
            Inherit -> null
            Off -> OFF
            is Preset -> PRESET + choice.path
        }
        private const val OFF = "off"
        private const val PRESET = "preset:"
    }
}

/** Where a game's shader came from. */
enum class ShaderLevel { Game, Console, Global, None }

/** A game's shader after its own, its console's and every console's choices: the preset, or none. */
data class ResolvedShader(val path: String?, val level: ShaderLevel)

/** Whose choice a picker sets. */
sealed interface ShaderTarget {
    val key: String
    data object Global : ShaderTarget { override val key = "global" }
    data class Console(val system: GameSystem) : ShaderTarget { override val key = "console.${system.name}" }
    data class Game(val id: String, val system: GameSystem, val title: String) : ShaderTarget { override val key = "game.$id" }
}

object ShaderResolution {
    /** The game's own choice, then its console's, then every console's; Off at any level stops there. */
    fun resolve(game: ShaderChoice, console: ShaderChoice, global: ShaderChoice): ResolvedShader {
        for ((choice, level) in listOf(game to ShaderLevel.Game, console to ShaderLevel.Console, global to ShaderLevel.Global)) {
            when (choice) {
                ShaderChoice.Inherit -> continue
                ShaderChoice.Off -> return ResolvedShader(null, level)
                is ShaderChoice.Preset -> return ResolvedShader(choice.path, level)
            }
        }
        return ResolvedShader(null, ShaderLevel.None)
    }

    /** A preset's name as the picker and settings show it: its file name without `.glslp`. */
    fun title(path: String) = path.substringAfterLast('/').removeSuffix(".glslp")
}

/**
 * The user's shader choices, in the same preferences as the other settings: `shader.global`,
 * `shader.console.<GameSystem>` and `shader.game.<id>`. Presets that failed the check on this device are kept
 * per library commit, so a new library gives each one another chance.
 */
class ShaderChoices(private val preferences: SharedPreferences) {
    fun get(target: ShaderTarget): ShaderChoice = ShaderChoice.decode(preferences.getString(key(target), null))
    fun set(target: ShaderTarget, choice: ShaderChoice) = preferences.edit {
        ShaderChoice.encode(choice)?.let { putString(key(target), it) } ?: remove(key(target))
    }
    fun resolve(system: GameSystem, gameId: String?): ResolvedShader = ShaderResolution.resolve(
        gameId?.let { get(ShaderTarget.Game(it, system, "")) } ?: ShaderChoice.Inherit,
        get(ShaderTarget.Console(system)), get(ShaderTarget.Global))

    fun failed(commit: String?): Set<String> = preferences.getStringSet(FAILED, emptySet()).orEmpty()
        .filter { commit != null && it.startsWith("$commit/") }.map { it.removePrefix("$commit/") }.toSet()
    fun markFailed(commit: String?, path: String, failed: Boolean) = preferences.edit {
        if (commit == null) return@edit
        val kept = preferences.getStringSet(FAILED, emptySet()).orEmpty().filter { it.startsWith("$commit/") }.toMutableSet()
        if (failed) kept.add("$commit/$path") else kept.remove("$commit/$path")
        putStringSet(FAILED, kept)
    }

    /** Every console's and each console's choices go back to none; games keep theirs, as with other options. */
    fun resetConsoles() = preferences.edit {
        remove(key(ShaderTarget.Global))
        GameSystem.entries.forEach { remove(key(ShaderTarget.Console(it))) }
    }

    /** The choices for a backup: no file, only names. */
    fun capture(): JSONObject = JSONObject().apply {
        ShaderChoice.encode(get(ShaderTarget.Global))?.let { put("global", it) }
        put("consoles", JSONObject().apply {
            GameSystem.entries.forEach { system -> ShaderChoice.encode(get(ShaderTarget.Console(system)))?.let { put(system.name, it) } }
        })
        put("games", JSONObject().apply {
            preferences.all.keys.filter { it.startsWith(GAME) }.sorted().forEach { key ->
                val id = key.removePrefix(GAME)
                val value = preferences.getString(key, null)
                if (GAME_ID.matches(id) && ShaderChoice.decode(value) != ShaderChoice.Inherit) put(id, value)
            }
        })
    }

    fun restore(json: JSONObject) {
        validate(json)
        preferences.edit(commit = true) {
            json.optString("global").takeIf { json.has("global") }?.let { putString(key(ShaderTarget.Global), it) }
            json.getJSONObject("consoles").let { consoles -> consoles.keys().forEach { putString("$CONSOLE$it", consoles.getString(it)) } }
            json.getJSONObject("games").let { games -> games.keys().forEach { putString("$GAME$it", games.getString(it)) } }
        }
    }

    companion object {
        private const val PREFIX = "shader."
        private const val CONSOLE = "shader.console."
        private const val GAME = "shader.game."
        private const val FAILED = "shader.failed"
        private val GAME_ID = Regex("[a-f0-9]{64}")
        private fun key(target: ShaderTarget) = PREFIX + target.key

        /** A backup's shader section: known consoles, game ids, and values that are Off or a plain preset path. */
        fun validate(json: JSONObject) {
            require(json.keys().asSequence().toSet().minus("global") == setOf("consoles", "games")) { "Unsupported shader choices" }
            fun choice(value: Any?) = require(value is String && ShaderChoice.decode(value) != ShaderChoice.Inherit) { "Unsupported shader choice" }
            if (json.has("global")) choice(json.get("global"))
            val consoles = json.getJSONObject("consoles")
            consoles.keys().forEach { name -> require(GameSystem.entries.any { it.name == name }) { "Unsupported console" }; choice(consoles.get(name)) }
            val games = json.getJSONObject("games")
            require(games.length() <= 10_000) { "Too many shader choices" }
            games.keys().forEach { id -> require(GAME_ID.matches(id)) { "Unsupported game" }; choice(games.get(id)) }
        }
    }
}
