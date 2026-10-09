package dev.cartridge.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cartridge.domain.AxisRepeater
import kotlin.math.abs

enum class InputMode { Library, Gameplay, Menu, Text, Locked }

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
    private val chooseDirectory: () -> Unit = {},
    private val alternate: () -> Unit = {},
    private val cancelOperation: () -> Unit = {},
    private val details: () -> Unit = {},
    private val favorite: () -> Unit = {},
    private val navigateLibrary: ((Int, Int) -> Unit)? = null,
) {
    var mode = InputMode.Library
        set(value) { if (field != value) reset(); field = value }
    var pauseShortcut = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
    var swapMenuButtons = false
    var swapGameButtons = false
    var swapShoulders = false
    var deadZone = .35f
        set(value) { require(value in .15f.. .65f); reset(); field = value }
    private val held = mutableSetOf<Int>()
    private val horizontalRepeat = AxisRepeater()
    private val verticalRepeat = AxisRepeater()
    private var axisX = 0f
    private var axisY = 0f
    private var shortcutLatched = false
    private val directions = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val gameplayKeys = directions + setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)

    fun key(event: KeyEvent): Boolean {
        val physical = event.keyCode
        val code = if (mode != InputMode.Gameplay && swapMenuButtons) when (physical) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            else -> physical
        } else physical
        if (mode == InputMode.Text) {
            if (code in setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) back()
                return true
            }
            return false // Text entry/focus goes to ordinary Android/Compose controls.
        }
        if (code !in gameplayKeys && code !in setOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) held.add(code) else held.remove(code)
        if (!down) {
            if (code in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) &&
                held.none { it == KeyEvent.KEYCODE_DPAD_LEFT || it == KeyEvent.KEYCODE_DPAD_RIGHT } && abs(axisX) < deadZone) horizontalRepeat.reset()
            if (code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) &&
                held.none { it == KeyEvent.KEYCODE_DPAD_UP || it == KeyEvent.KEYCODE_DPAD_DOWN } && abs(axisY) < deadZone) verticalRepeat.reset()
        }
        if (held.intersect(pauseShortcut).isEmpty()) shortcutLatched = false
        if (mode == InputMode.Gameplay) {
            if (!shortcutLatched && held.containsAll(pauseShortcut)) {
                shortcutLatched = true
                pause()
            } else if (!shortcutLatched && code in gameplayKeys) gameKey(event.action, when {
                swapGameButtons && code == KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                swapGameButtons && code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                swapShoulders && code == KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_BUTTON_R1
                swapShoulders && code == KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_BUTTON_L1
                else -> code
            })
            else if (code == KeyEvent.KEYCODE_BACK && down && event.repeatCount == 0) pause()
            return true
        }
        if (mode == InputMode.Locked) {
            if (down && event.repeatCount == 0 && code in setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) cancelOperation()
            return true
        }
        if (down && event.repeatCount == 0) when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> select()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_BUTTON_X -> if (mode == InputMode.Library) chooseGame() else if (mode == InputMode.Menu) alternate()
            KeyEvent.KEYCODE_BUTTON_Y -> if (mode == InputMode.Library) chooseScrape()
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (mode == InputMode.Library) chooseLabel()
            KeyEvent.KEYCODE_BUTTON_START -> if (mode == InputMode.Library) chooseDirectory()
            KeyEvent.KEYCODE_BUTTON_R1 -> if (mode == InputMode.Library) details()
            KeyEvent.KEYCODE_BUTTON_L1 -> if (mode == InputMode.Library) favorite()
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
            fun digital(value: Float) = if (abs(value) < deadZone) 0f else if (value > 0) 1f else -1f
            gameMotion(digital(if (hatX != 0f) hatX else x), digital(if (hatY != 0f) hatY else y))
        } else {
            axisX = if (hatX != 0f) hatX else x; axisY = if (hatY != 0f) hatY else y
            // A neutral event can arrive between frame polls. Release immediately so the next
            // stick/key press starts a fresh repeat, even when it follows in the same frame.
            if (abs(axisX) < deadZone && held.none { it == KeyEvent.KEYCODE_DPAD_LEFT || it == KeyEvent.KEYCODE_DPAD_RIGHT }) horizontalRepeat.reset()
            if (abs(axisY) < deadZone && held.none { it == KeyEvent.KEYCODE_DPAD_UP || it == KeyEvent.KEYCODE_DPAD_DOWN }) verticalRepeat.reset()
        }
        return true
    }
    fun tick(nowMillis: Long) {
        if (mode != InputMode.Library && mode != InputMode.Menu) return
        fun axis(negative: Int, positive: Int, analog: Float) = when {
            positive in held && negative !in held -> 1f
            negative in held && positive !in held -> -1f
            else -> analog
        }
        val y = axis(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, axisY)
        val dy = verticalRepeat.poll(y, nowMillis)
        if (mode == InputMode.Menu) { if (dy != 0) navigate(dy); return }
        val x = axis(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, axisX)
        val dx = horizontalRepeat.poll(x, nowMillis)
        if (dx != 0 || dy != 0) {
            if (navigateLibrary != null) navigateLibrary.invoke(dx, dy) else if (dx != 0) navigate(dx)
        }
    }
    fun reset() { held.clear(); axisX = 0f; axisY = 0f; horizontalRepeat.reset(); verticalRepeat.reset(); shortcutLatched = false }
}
