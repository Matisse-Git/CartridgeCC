package dev.cartridge

import dev.cartridge.environment.ConsoleSettings
import org.junit.Assert.*
import org.junit.Test

class ConsoleSettingsTest {
    @Test fun explicitSupportedCoreChoicesAndIndependentControllerPreferences() {
        val defaults = ConsoleSettings()
        assertEquals(mapOf("mgba_audio_low_pass_filter" to "disabled", "mgba_frameskip" to "0"), defaults.coreOptions())
        assertEquals(defaults.coreOptions(), defaults.copy(swapMenuButtons = true, swapGameButtons = true, deadZone = .5f).coreOptions())
        assertEquals(mapOf("mgba_audio_low_pass_filter" to "enabled", "mgba_frameskip" to "2"), defaults.copy(audioFilter = true, frameSkip = 2).coreOptions())
    }
    @Test fun unsafeOrUnsupportedChoicesAreRejected() {
        for (zone in listOf(Float.NaN, 0f, 1f)) assertThrows(IllegalArgumentException::class.java) { ConsoleSettings(deadZone = zone) }
        for (skip in listOf(-1, 3)) assertThrows(IllegalArgumentException::class.java) { ConsoleSettings(frameSkip = skip) }
    }
}
