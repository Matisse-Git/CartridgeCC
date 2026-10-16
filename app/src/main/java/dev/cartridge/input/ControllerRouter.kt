package dev.cartridge.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cartridge.domain.AxisRepeater
import kotlin.math.abs

enum class InputMode { Library, Gameplay, Menu, Text, Locked }

/**
 * One route per event. The router reports buttons by role and never decides what a role does: the console
 * shell gives A, B, X, Y, the shoulders and Start one meaning per screen. Physical A/B stay separate from
 * gameplay mapping.
 */
class ControllerRouter(
    private val navigate: (Int) -> Unit,
    private val select: () -> Unit,
    private val back: () -> Unit,
    private val pause: () -> Unit,
    private val gameKey: (Int, Int) -> Unit,
    private val gameMotion: (Float, Float) -> Unit,
    /** Left/right inside a menu: change a value, switch a pane, move across a key grid. */
    private val adjust: (Int) -> Unit = {},
    /** X. */
    private val alternate: () -> Unit = {},
    /** Y. */
    private val secondary: () -> Unit = {},
    /** L1 is -1, R1 is +1. */
    private val shoulder: (Int) -> Unit = {},
    /** Start. */
    private val menu: () -> Unit = {},
    private val cancelOperation: () -> Unit = {},
    private val navigateLibrary: ((Int, Int) -> Unit)? = null,
    /** Right stick outside a game, -1..1 per axis with the dead zone removed. Zero when it rests or input is reset. */
    private val inspect: (Float, Float) -> Unit = { _, _ -> },
    /** Android's own Back and Escape, outside a game. The controller's back button is [back]. */
    private val systemBack: () -> Unit = back,
    /** A stick of a game whose console has them: 0 left, 1 right, -1..1 per axis with a small dead zone removed. */
    private val gameAnalog: (Int, Float, Float) -> Unit = { _, _, _ -> },
) {
    var mode = InputMode.Library
        set(value) { if (field != value) reset(); field = value }
    var pauseShortcut = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
    var swapMenuButtons = false
    var swapGameButtons = false
    var swapShoulders = false
    /** The running game reads the sticks themselves. Otherwise the left stick is its D-pad, as on a console without one. */
    var analogGameplay = false
        set(value) { if (field != value) reset(); field = value }
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
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
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
            KeyEvent.KEYCODE_BUTTON_B -> back()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> systemBack()
            KeyEvent.KEYCODE_BUTTON_X -> alternate()
            KeyEvent.KEYCODE_BUTTON_Y -> secondary()
            KeyEvent.KEYCODE_BUTTON_START -> menu()
            KeyEvent.KEYCODE_BUTTON_L1 -> shoulder(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> shoulder(1)
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
            if (analogGameplay) {
                // The D-pad stays a D-pad; each stick goes through whole, less the slack every stick has at rest.
                fun stick(index: Int, sx: Float, sy: Float) {
                    val reach = kotlin.math.hypot(sx, sy)
                    if (reach < GAME_STICK_REST) gameAnalog(index, 0f, 0f)
                    else ((reach - GAME_STICK_REST) / (1f - GAME_STICK_REST)).coerceAtMost(1f).let { travel -> gameAnalog(index, sx / reach * travel, sy / reach * travel) }
                }
                gameMotion(digital(hatX), digital(hatY))
                stick(0, x, y); stick(1, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
            } else gameMotion(digital(if (hatX != 0f) hatX else x), digital(if (hatY != 0f) hatY else y))
        } else {
            // The right stick is AXIS_Z / AXIS_RZ on the Retroid Pocket 5 and on standard Android gamepads.
            // AXIS_RX / AXIS_RY are not read: some pads report their triggers there.
            val rightX = event.getAxisValue(MotionEvent.AXIS_Z); val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)
            val reach = kotlin.math.hypot(rightX, rightY)
            if (mode == InputMode.Library || mode == InputMode.Menu) {
                if (reach < deadZone) inspect(0f, 0f)
                else ((reach - deadZone) / (1f - deadZone)).coerceAtMost(1f).let { travel -> inspect(rightX / reach * travel, rightY / reach * travel) }
            }
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
        fun threshold(value: Float) = if (abs(value) < deadZone) 0f else if (value > 0) 1f else -1f
        val dy = verticalRepeat.poll(threshold(axis(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, axisY)), nowMillis)
        val dx = horizontalRepeat.poll(threshold(axis(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, axisX)), nowMillis)
        if (mode == InputMode.Menu) {
            if (dy != 0) navigate(dy)
            if (dx != 0) adjust(dx)
        } else if (dx != 0 || dy != 0) {
            if (navigateLibrary != null) navigateLibrary.invoke(dx, dy) else if (dx != 0) navigate(dx)
        }
    }
    fun reset() {
        held.clear(); axisX = 0f; axisY = 0f; horizontalRepeat.reset(); verticalRepeat.reset(); shortcutLatched = false; inspect(0f, 0f)
        gameAnalog(0, 0f, 0f); gameAnalog(1, 0f, 0f)
    }
    private companion object { const val GAME_STICK_REST = .12f }
}
