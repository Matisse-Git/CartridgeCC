package dev.cartridge

import android.view.KeyEvent
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.PadKey
import dev.cartridge.environment.ConsoleSettings
import dev.cartridge.input.GameButtons
import org.junit.Assert.*
import org.junit.Test

class ConsoleSettingsTest {
    @Test fun explicitSupportedCoreChoicesAndIndependentControllerPreferences() {
        val defaults = ConsoleSettings()
        assertEquals(mapOf("mgba_audio_low_pass_filter" to "disabled", "mgba_frameskip" to "0"), defaults.coreOptions())
        assertEquals(defaults.coreOptions(), defaults.copy(swapMenuButtons = true, deadZone = .5f)
            .withButtons(GameSystem.GBA, ButtonMap.assign(ButtonMap.default(GameSystem.GBA), PadKey.A, PadKey.B)).coreOptions())
        assertEquals(mapOf("mgba_audio_low_pass_filter" to "enabled", "mgba_frameskip" to "2"), defaults.copy(audioFilter = true, frameSkip = 2).coreOptions())
    }
    @Test fun unsafeOrUnsupportedChoicesAreRejected() {
        for (zone in listOf(Float.NaN, 0f, 1f)) assertThrows(IllegalArgumentException::class.java) { ConsoleSettings(deadZone = zone) }
        for (skip in listOf(-1, 3)) assertThrows(IllegalArgumentException::class.java) { ConsoleSettings(frameSkip = skip) }
    }

    @Test fun fastForwardRunsAWholeNumberOfFramesFromTwoToEight() {
        assertEquals(2, ConsoleSettings().fastForwardSpeed)
        for (speed in listOf(1, 0, 9)) assertThrows(IllegalArgumentException::class.java) { ConsoleSettings(fastForwardSpeed = speed) }
        assertEquals(8, ConsoleSettings(fastForwardSpeed = 8).fastForwardSpeed)
    }

    @Test fun everyConsoleHasItsOwnPadAndItsDefaultIsWhatItsGamesWereAlwaysSent() {
        for (system in GameSystem.entries) {
            val pad = system.pad
            assertTrue(system.name, pad.isNotEmpty() && pad.any { it.key == PadKey.Start })
            assertEquals(system.name, pad.size, pad.map { it.key }.toSet().size)
            assertEquals(system.name, pad.size, pad.map { it.name }.toSet().size)
            // Before this setting every physical button went to the core as itself: the default keeps exactly that.
            val routes = GameButtons.routes(ButtonMap.default(system))
            assertTrue(system.name, routes.all { (physical, sent) -> physical == sent })
        }
        // Saturn's two rows of three, read from the pinned core: A B C below, X Y Z above.
        assertEquals(listOf("A", "B", "C", "X", "Y", "Z", "L", "R", "Start"), GameSystem.SATURN.pad.map { it.name })
        assertEquals(listOf(PadKey.B, PadKey.A, PadKey.R1, PadKey.Y, PadKey.X, PadKey.L1, PadKey.L2, PadKey.R2, PadKey.Start), GameSystem.SATURN.pad.map { it.key })
        // Consoles that share a core still have their own buttons.
        assertNotEquals(GameSystem.GB.pad.map { it.name }, GameSystem.GBA.pad.map { it.name })
        assertNotEquals(GameSystem.GENESIS.pad.map { it.name }, GameSystem.SMS.pad.map { it.name })
    }

    @Test fun aButtonTakenFromAnotherTradesPlacesSoNoneIsLostOrShared() {
        val saturn = ButtonMap.default(GameSystem.SATURN)
        // Saturn A (core key B) onto the physical X button: Saturn Y, which had X, takes A's old one.
        val moved = ButtonMap.assign(saturn, PadKey.B, PadKey.X)
        assertEquals(PadKey.X, moved[PadKey.B]); assertEquals(PadKey.B, moved[PadKey.X])
        assertEquals(saturn.size, moved.values.toSet().size)
        // A physical button no Saturn button had is simply taken.
        val free = ButtonMap.assign(saturn, PadKey.Start, PadKey.Select)
        assertEquals(PadKey.Select, free[PadKey.Start]); assertEquals(saturn - PadKey.Start, free - PadKey.Start)
        // Pressed with the X button, the game hears its A; the physical button nothing has sends nothing.
        val routes = GameButtons.routes(free)
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, routes[KeyEvent.KEYCODE_BUTTON_SELECT]); assertNull(routes[KeyEvent.KEYCODE_BUTTON_START])
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GameButtons.routes(moved)[KeyEvent.KEYCODE_BUTTON_X])
        // A button the console doesn't have changes nothing.
        assertEquals(ButtonMap.default(GameSystem.GB), ButtonMap.assign(ButtonMap.default(GameSystem.GB), PadKey.R2, PadKey.A))
        // Left and right step through all twelve and come back round.
        var stepped = saturn
        repeat(PadKey.entries.size) { stepped = ButtonMap.step(stepped, PadKey.Start, 1) }
        assertEquals(PadKey.Start, stepped[PadKey.Start]); assertEquals(saturn.size, stepped.values.toSet().size)
        assertEquals(PadKey.R3, ButtonMap.step(saturn, PadKey.Start, -1)[PadKey.Start])
    }

    @Test fun storedMapsReadBackAndAnythingThatNoLongerFitsIsTheDefault() {
        val map = ButtonMap.assign(ButtonMap.default(GameSystem.SATURN), PadKey.B, PadKey.X)
        assertEquals(map, ButtonMap.decode(GameSystem.SATURN, ButtonMap.encode(map)))
        for (stored in listOf(null, "", "B=X", "B=X,A=X", "Q=A", "B=Q", ButtonMap.encode(ButtonMap.default(GameSystem.GB)),
                ButtonMap.encode(map) + ",Select=Select", ButtonMap.encode(map) + ",B=B"))
            assertNull(stored, ButtonMap.decode(GameSystem.SATURN, stored))
        // A map equal to the default is not kept: the console follows its default.
        val custom = ConsoleSettings().withButtons(GameSystem.SATURN, map)
        assertEquals(setOf(GameSystem.SATURN), custom.buttons.keys); assertEquals(ButtonMap.default(GameSystem.GBA), custom.buttons(GameSystem.GBA))
        assertEquals(ConsoleSettings(), custom.withButtons(GameSystem.SATURN, ButtonMap.default(GameSystem.SATURN)))
    }
}
