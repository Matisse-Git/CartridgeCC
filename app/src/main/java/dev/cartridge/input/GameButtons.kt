package dev.cartridge.input

import android.view.KeyEvent
import dev.cartridge.domain.PadKey

/** [PadKey] as Android's key codes: what a controller sends, and what LibretroDroid hands a core as that pad button. */
object GameButtons {
    val codes: Map<PadKey, Int> = mapOf(
        PadKey.A to KeyEvent.KEYCODE_BUTTON_A, PadKey.B to KeyEvent.KEYCODE_BUTTON_B, PadKey.X to KeyEvent.KEYCODE_BUTTON_X,
        PadKey.Y to KeyEvent.KEYCODE_BUTTON_Y, PadKey.L1 to KeyEvent.KEYCODE_BUTTON_L1, PadKey.R1 to KeyEvent.KEYCODE_BUTTON_R1,
        PadKey.L2 to KeyEvent.KEYCODE_BUTTON_L2, PadKey.R2 to KeyEvent.KEYCODE_BUTTON_R2, PadKey.L3 to KeyEvent.KEYCODE_BUTTON_THUMBL,
        PadKey.R3 to KeyEvent.KEYCODE_BUTTON_THUMBR, PadKey.Start to KeyEvent.KEYCODE_BUTTON_START, PadKey.Select to KeyEvent.KEYCODE_BUTTON_SELECT)
    fun key(code: Int): PadKey? = codes.entries.firstOrNull { it.value == code }?.key
    /** Every button sends its own key: what a game hears before a console's buttons are known. */
    val IDENTITY: Map<Int, Int> = codes.values.associateWith { it }
    /** A console's map (button key to physical button) as the router uses it: physical key code to the code sent. */
    fun routes(map: Map<PadKey, PadKey>): Map<Int, Int> = map.entries.associate { (button, physical) -> codes.getValue(physical) to codes.getValue(button) }
}
