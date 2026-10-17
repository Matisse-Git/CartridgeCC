package dev.cartridge.input

import android.view.KeyEvent

/**
 * Buttons held together during a game to quick save or quick load (docs/SAVE_RELIABILITY). Only gamepad buttons
 * can be part of one, never Home, Back or volume, and never fewer than two, so no single button is taken from a game.
 */
object ButtonCombo {
    val QUICK_SAVE = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_Y)
    val QUICK_LOAD = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_X)
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

    /** Why [combo] cannot be used for one action while [other] is the other action's, or null when it can. */
    fun problem(combo: Set<Int>, other: Set<Int>): String? = when {
        combo.any { it !in allowed } -> "Only controller buttons can be used"
        combo.size < 2 -> "Hold at least two buttons, so no button is taken from your games"
        overlaps(combo, other) -> "That would also press the other quick button combo (${title(other)})"
        PAUSES.any { overlaps(combo, it) } -> "That would also press a pause shortcut (${title(PAUSES.first { overlaps(combo, it) })})"
        else -> null
    }
    /** Holding one would complete the other: equal, or one contains the other. */
    private fun overlaps(first: Set<Int>, second: Set<Int>) = second.isNotEmpty() && (first.containsAll(second) || second.containsAll(first))

    fun encode(combo: Set<Int>) = combo.sorted().joinToString(",")
    /** A stored combo that no longer passes the rules falls back to its default. */
    fun decode(value: String?, default: Set<Int>): Set<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.size >= 2 && it.all { code -> code in allowed } } ?: default
}

/**
 * Listens for a new combo. Every button pressed while at least one is held joins it; letting go of the first one
 * ends it. A combo the rules refuse is reported and listening starts over.
 */
class ComboListener(private val other: Set<Int>) {
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
        problem = ButtonCombo.problem(combo, other)
        if (problem != null) { captured = emptySet(); return null }
        return combo
    }
}
