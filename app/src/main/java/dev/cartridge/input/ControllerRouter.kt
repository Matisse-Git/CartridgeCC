package dev.cartridge.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cartridge.domain.AxisRepeater
import kotlin.math.abs

enum class InputMode { Library, Gameplay, Menu, Locked }

/** One route per event. Physical A/B are separate from the collection's action mapping. */
class ControllerRouter(
    private val navigate: (Int) -> Unit,
    private val select: () -> Unit,
    private val back: () -> Unit,
    private val pause: () -> Unit,
    private val gameKey: (Int, Int) -> Unit,
    private val gameMotion: (Float, Float) -> Unit,
    private val chooseGame: () -> Unit = {},
    private val chooseLabel: () -> Unit = {},
    private val chooseScrape: () -> Unit = {},
    private val alternate: () -> Unit = {},
) {
    var mode = InputMode.Library
        set(value) { if (field != value) reset(); field = value }
    var pauseShortcut = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
    private val held = mutableSetOf<Int>()
    private val repeat = AxisRepeater()
    private var axis = 0f
    private var shortcutLatched = false
    private val directions = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val gameplayKeys = directions + setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)

    fun key(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in gameplayKeys && code !in setOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) held.add(code) else held.remove(code)
        if (held.intersect(pauseShortcut).isEmpty()) shortcutLatched = false
        if (mode == InputMode.Gameplay) {
            if (!shortcutLatched && held.containsAll(pauseShortcut)) {
                shortcutLatched = true
                pause()
            } else if (!shortcutLatched && code in gameplayKeys) gameKey(event.action, code)
            else if (code == KeyEvent.KEYCODE_BACK && down && event.repeatCount == 0) pause()
            return true
        }
        if (mode == InputMode.Locked) return true
        if (down && event.repeatCount == 0) when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> select()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_BUTTON_X -> if (mode == InputMode.Library) chooseGame() else if (mode == InputMode.Menu) alternate()
            KeyEvent.KEYCODE_BUTTON_Y -> if (mode == InputMode.Library) chooseScrape()
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (mode == InputMode.Library) chooseLabel()
        }
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (mode == InputMode.Gameplay) {
            fun digital(value: Float) = if (abs(value) < .35f) 0f else if (value > 0) 1f else -1f
            gameMotion(digital(if (hatX != 0f) hatX else x), digital(if (hatY != 0f) hatY else y))
        } else axis = if (mode == InputMode.Menu) (if (hatY != 0f) hatY else y)
            else (if (hatX != 0f) hatX else x)
        return true
    }
    fun tick(nowMillis: Long) {
        if (mode != InputMode.Library && mode != InputMode.Menu) return
        val negative = if (mode == InputMode.Library) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
        val positive = if (mode == InputMode.Library) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
        val value = when {
            positive in held && negative !in held -> 1f
            negative in held && positive !in held -> -1f
            else -> axis
        }
        val delta = repeat.poll(value, nowMillis)
        if (delta != 0) navigate(delta)
    }
    fun reset() { held.clear(); axis = 0f; repeat.reset(); shortcutLatched = false }
}
