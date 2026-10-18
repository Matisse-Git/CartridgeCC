package dev.cartridge

import android.view.KeyEvent
import dev.cartridge.domain.pictureBounds
import dev.cartridge.input.ButtonCombo
import dev.cartridge.input.ComboListener
import dev.cartridge.input.QuickCombo
import org.junit.Assert.*
import org.junit.Test

class ButtonComboTest {
    @Test fun theDefaultsAreWrittenAsThePlayerReadsThemAndDoNotPressEachOtherOrPause() {
        assertEquals("L3 + Y", ButtonCombo.title(ButtonCombo.QUICK_SAVE))
        assertEquals("L3 + X", ButtonCombo.title(ButtonCombo.QUICK_LOAD))
        assertNull(ButtonCombo.problem(ButtonCombo.QUICK_SAVE, ButtonCombo.QUICK_LOAD))
        assertNull(ButtonCombo.problem(ButtonCombo.QUICK_LOAD, ButtonCombo.QUICK_SAVE))
    }

    @Test fun aComboIsRefusedWhenItWouldTakeOneButtonOrPressAnotherShortcut() {
        val l3 = KeyEvent.KEYCODE_BUTTON_THUMBL; val r3 = KeyEvent.KEYCODE_BUTTON_THUMBR
        assertNotNull(ButtonCombo.problem(setOf(KeyEvent.KEYCODE_BUTTON_Y), ButtonCombo.QUICK_LOAD))
        assertNotNull(ButtonCombo.problem(setOf(l3, KeyEvent.KEYCODE_VOLUME_UP), ButtonCombo.QUICK_LOAD))
        assertNotNull("equal to the other", ButtonCombo.problem(ButtonCombo.QUICK_LOAD, ButtonCombo.QUICK_LOAD))
        assertNotNull("contains the other", ButtonCombo.problem(ButtonCombo.QUICK_LOAD + KeyEvent.KEYCODE_BUTTON_A, ButtonCombo.QUICK_LOAD))
        assertNotNull("is the pause shortcut", ButtonCombo.problem(setOf(l3, r3), ButtonCombo.QUICK_LOAD))
        assertNotNull("contains the other pause shortcut", ButtonCombo.problem(setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A), ButtonCombo.QUICK_LOAD))
        assertNull(ButtonCombo.problem(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L1), ButtonCombo.QUICK_LOAD))
    }

    @Test fun aStoredComboRoundTripsAndAnUnusableOneFallsBack() {
        val combo = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(combo, ButtonCombo.decode(ButtonCombo.encode(combo), ButtonCombo.QUICK_SAVE))
        assertEquals(ButtonCombo.QUICK_SAVE, ButtonCombo.decode(null, ButtonCombo.QUICK_SAVE))
        assertEquals(ButtonCombo.QUICK_SAVE, ButtonCombo.decode("${KeyEvent.KEYCODE_BUTTON_A}", ButtonCombo.QUICK_SAVE))
        assertEquals(ButtonCombo.QUICK_SAVE, ButtonCombo.decode("${KeyEvent.KEYCODE_HOME},${KeyEvent.KEYCODE_BUTTON_A}", ButtonCombo.QUICK_SAVE))
        assertEquals(ButtonCombo.QUICK_SAVE, ButtonCombo.decode("nonsense", ButtonCombo.QUICK_SAVE))
    }

    @Test fun freezeAndFastForwardDefaultToL3WithBAndAAndNoTwoCombosPressEachOther() {
        assertEquals("L3 + B", ButtonCombo.title(QuickCombo.Freeze.default))
        assertEquals("L3 + A", ButtonCombo.title(QuickCombo.FastForward.default))
        assertNull(ButtonCombo.conflict(ButtonCombo.DEFAULTS))
        // A combo is checked against every other one, not only its pair.
        assertNotNull(ButtonCombo.problem(ButtonCombo.QUICK_SAVE, listOf(QuickCombo.Freeze.default, ButtonCombo.QUICK_SAVE)))
        assertNull(ButtonCombo.problem(setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_R1), ButtonCombo.DEFAULTS.values))
        assertEquals(QuickCombo.Save, ButtonCombo.conflict(ButtonCombo.DEFAULTS + (QuickCombo.FastForward to ButtonCombo.QUICK_SAVE)))
        assertNotNull(ButtonCombo.conflict(ButtonCombo.DEFAULTS + (QuickCombo.Freeze to setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR))))
    }

    @Test fun everyButtonHeldUntilTheFirstIsReleasedMakesTheCombo() {
        val listener = ComboListener(ButtonCombo.QUICK_LOAD)
        assertNull(listener.key(KeyEvent.KEYCODE_BUTTON_SELECT, true))
        assertNull(listener.key(KeyEvent.KEYCODE_BUTTON_L1, true))
        assertNull(listener.key(KeyEvent.KEYCODE_BUTTON_R1, true))
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1), listener.captured)
        assertEquals(listener.captured, listener.key(KeyEvent.KEYCODE_BUTTON_L1, false))
        // The rest let go afterwards change nothing.
        assertNull(listener.key(KeyEvent.KEYCODE_BUTTON_R1, false))
    }

    @Test fun aRefusedComboSaysWhyAndListeningStartsAgain() {
        val listener = ComboListener(ButtonCombo.QUICK_LOAD)
        listener.key(KeyEvent.KEYCODE_BUTTON_A, true)
        assertNull(listener.key(KeyEvent.KEYCODE_BUTTON_A, false))
        assertNotNull(listener.problem)
        assertTrue(listener.captured.isEmpty())
        listener.key(KeyEvent.KEYCODE_BUTTON_THUMBR, true); listener.key(KeyEvent.KEYCODE_BUTTON_B, true)
        assertNull(listener.problem)
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_B), listener.key(KeyEvent.KEYCODE_BUTTON_B, false))
    }

    @Test fun thumbnailsLoseEvenBlackBarsButKeepADarkOrLopsidedScreen() {
        val black = 0xFF000000.toInt(); val grey = 0xFF808080.toInt()
        fun screen(width: Int, height: Int, inside: (Int, Int) -> Boolean) = IntArray(width * height) { if (inside(it % width, it / width)) grey else black }
        // A 4:3 picture in the middle of a 16:9 screen.
        assertArrayEquals(intArrayOf(8, 0, 40, 27), pictureBounds(screen(48, 27) { x, _ -> x in 8 until 40 }, 48, 27))
        // A picture that touches every edge stays whole.
        assertArrayEquals(intArrayOf(0, 0, 48, 27), pictureBounds(screen(48, 27) { _, _ -> true }, 48, 27))
        // Black everywhere: a dark scene, not a bar.
        assertArrayEquals(intArrayOf(0, 0, 48, 27), pictureBounds(screen(48, 27) { _, _ -> false }, 48, 27))
        // Dark on the left only is part of the picture.
        assertArrayEquals(intArrayOf(0, 0, 48, 27), pictureBounds(screen(48, 27) { x, _ -> x >= 20 }, 48, 27))
        // A small bright spot in a dark scene is not the picture's edge.
        assertArrayEquals(intArrayOf(0, 0, 48, 27), pictureBounds(screen(48, 27) { x, y -> x == 24 && y == 13 }, 48, 27))
    }
}
