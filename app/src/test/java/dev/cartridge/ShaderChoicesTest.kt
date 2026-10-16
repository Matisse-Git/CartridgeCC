package dev.cartridge

import dev.cartridge.shaders.ResolvedShader
import dev.cartridge.shaders.ShaderChoice
import dev.cartridge.shaders.ShaderChoice.Inherit
import dev.cartridge.shaders.ShaderChoice.Off
import dev.cartridge.shaders.ShaderChoice.Preset
import dev.cartridge.shaders.ShaderLevel
import dev.cartridge.shaders.ShaderResolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** A game's shader: its own choice, then its console's, then every console's (ADR 0027). */
class ShaderChoicesTest {
    private val crt = Preset("crt/crt-royale.glslp")
    private val lcd = Preset("handheld/lcd-grid-v2.glslp")
    private val dot = Preset("handheld/gb-dot-matrix-DMG.glslp")

    @Test fun eachLevelWinsOverTheOnesAboveIt() {
        assertEquals(ResolvedShader(null, ShaderLevel.None), ShaderResolution.resolve(Inherit, Inherit, Inherit))
        assertEquals(ResolvedShader(crt.path, ShaderLevel.Global), ShaderResolution.resolve(Inherit, Inherit, crt))
        assertEquals(ResolvedShader(lcd.path, ShaderLevel.Console), ShaderResolution.resolve(Inherit, lcd, crt))
        assertEquals(ResolvedShader(dot.path, ShaderLevel.Game), ShaderResolution.resolve(dot, lcd, crt))
    }

    @Test fun offStopsTheLevelsAboveIt() {
        assertEquals(ResolvedShader(null, ShaderLevel.Game), ShaderResolution.resolve(Off, lcd, crt))
        assertEquals(ResolvedShader(null, ShaderLevel.Console), ShaderResolution.resolve(Inherit, Off, crt))
        assertEquals(ResolvedShader(dot.path, ShaderLevel.Game), ShaderResolution.resolve(dot, Off, Off))
    }

    @Test fun storedValuesAreReadBackOrIgnored() {
        for (choice in listOf(Off, crt, Preset("borders/sgb/sgb-tvout+interlacing.glslp"))) assertEquals(choice, ShaderChoice.decode(ShaderChoice.encode(choice)))
        assertEquals(null, ShaderChoice.encode(Inherit))
        // Anything that is not Off or a plain preset path counts as no choice at that level.
        for (value in listOf(null, "", "on", "preset:", "preset:../x.glslp", "preset:crt/x.glsl", "preset:/abs.glslp", "preset:a\\b.glslp"))
            assertEquals(value, Inherit, ShaderChoice.decode(value))
        assertEquals("crt-royale", ShaderResolution.title(crt.path))
    }
}
