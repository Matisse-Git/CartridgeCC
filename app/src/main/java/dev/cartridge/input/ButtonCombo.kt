package dev.cartridge.input

import android.view.KeyEvent
import dev.cartridge.domain.PadKey

/**
 * What a combo held during a game does (docs/SAVE_RELIABILITY, ADR 0031), with the buttons it has until the owner
 * records others. Names are stored ids: append, never rename or reorder.
 */
enum class QuickCombo(val title: String, val default: Set<Int>, val key: String) {
    Save("Quick save", setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_Y), "quickSaveCombo"),
    Load("Quick load", setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_X), "quickLoadCombo"),
    /** The game stops where it is, with no menu, and goes on when pressed again. */
    Freeze("Freeze", setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_B), "freezeCombo"),
    /** Between normal speed and the fast-forward speed of Settings. */
    FastForward("Fast-forward", setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_A), "fastForwardCombo"),
}

/**
 * Buttons held together during a game ([QuickCombo]). Only gamepad buttons can be part of one, never Home, Back or
 * volume, and never fewer than two, so no single button is taken from a game.
 */
object ButtonCombo {
    val QUICK_SAVE = QuickCombo.Save.default
    val QUICK_LOAD = QuickCombo.Load.default
    val DEFAULTS = QuickCombo.entries.associateWith { it.default }
    /** Both pause shortcuts Settings offers; a quick combo may not overlap either, whichever is chosen today. */
    val PAUSES = listOf(setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR),
        setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT))
    /** In the order a combo is written: shoulders and sticks, then face buttons, then the D-pad. */
    private val names = linkedMapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "R2", KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_SELECT to "Select", KeyEvent.KEYCODE_BUTTON_START to "Start",
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_DPAD_UP to "Up", KeyEvent.KEYCODE_DPAD_DOWN to "Down", KeyEvent.KEYCODE_DPAD_LEFT to "Left", KeyEvent.KEYCODE_DPAD_RIGHT to "Right")
    val allowed: Set<Int> = names.keys

    fun title(combo: Set<Int>): String = if (combo.isEmpty()) "None" else names.keys.filter { it in combo }.joinToString(" + ") { names.getValue(it) }

    /** Why [combo] cannot be used for one action while [others] are the other actions', or null when it can. */
    fun problem(combo: Set<Int>, others: Collection<Set<Int>>): String? = when {
        combo.any { it !in allowed } -> "Only controller buttons can be used"
        combo.size < 2 -> "Hold at least two buttons, so no button is taken from your games"
        others.any { overlaps(combo, it) } -> "That would also press another quick button combo (${title(others.first { overlaps(combo, it) })})"
        PAUSES.any { overlaps(combo, it) } -> "That would also press a pause shortcut (${title(PAUSES.first { overlaps(combo, it) })})"
        else -> null
    }
    fun problem(combo: Set<Int>, other: Set<Int>) = problem(combo, listOf(other))
    /** The first combo of [combos] that another of them, or a pause shortcut, would press. */
    fun conflict(combos: Map<QuickCombo, Set<Int>>): QuickCombo? =
        combos.keys.firstOrNull { action -> problem(combos.getValue(action), (combos - action).values) != null }
    /** Holding one would complete the other: equal, or one contains the other. */
    private fun overlaps(first: Set<Int>, second: Set<Int>) = second.isNotEmpty() && (first.containsAll(second) || second.containsAll(first))

    fun encode(combo: Set<Int>) = combo.sorted().joinToString(",")
    /** A stored combo that no longer passes the rules falls back to its default. */
    fun decode(value: String?, default: Set<Int>): Set<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.size >= 2 && it.all { code -> code in allowed } } ?: default
}

/**
 * Listens for the one physical button a console's button is to be pressed with. The first button pressed is it, once
 * it is let go; a D-pad direction is refused, since the D-pad stays the console's directions.
 */
class ButtonListener {
    private var held: Int? = null
    var captured: Int? = null; private set
    var problem: String? = null; private set
    fun key(code: Int, down: Boolean): PadKey? {
        if (down) { if (held == null) { held = code; captured = code; problem = null }; return null }
        if (code != held) return null
        held = null
        return GameButtons.key(code).also { if (it == null) { captured = null; problem = "The D-pad stays this console's directions. Press a button." } }
    }
}

/**
 * Listens for a new combo. Every button pressed while at least one is held joins it; letting go of the first one
 * ends it. A combo the rules refuse is reported and listening starts over.
 */
class ComboListener(private val others: Collection<Set<Int>>) {
    constructor(other: Set<Int>) : this(listOf(other))
    private val held = mutableSetOf<Int>()
    var captured: Set<Int> = emptySet(); private set
    var problem: String? = null; private set
    /** Returns the finished combo once the first button is released, or null while it is still being held. */
    fun key(code: Int, down: Boolean): Set<Int>? {
        if (down) {
            if (held.isEmpty()) { captured = emptySet(); problem = null }
            held.add(code); captured = captured + code
            return null
        }
        if (code !in held) return null
        held.clear()
        val combo = captured
        problem = ButtonCombo.problem(combo, others)
        if (problem != null) { captured = emptySet(); return null }
        return combo
    }
}
