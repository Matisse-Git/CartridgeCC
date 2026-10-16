package dev.cartridge

import dev.cartridge.shaders.ConfigFile
import dev.cartridge.shaders.PresetReader
import dev.cartridge.shaders.PresetReader.Scale
import dev.cartridge.shaders.PresetReader.Wrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** RetroArch 1.22.2's reading of a GLSL preset, as [PresetReader] repeats it. Every file here is made up. */
class PresetReaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val library get() = File(folder.root, "library").apply { mkdirs() }

    private fun write(path: String, text: String) = File(library, path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun read(path: String) = PresetReader(library).read(File(library, path))
    private fun unusable(path: String, containing: String) {
        try {
            read(path)
            fail("$path was read")
        } catch (expected: PresetReader.Unusable) {
            assertTrue("${expected.message} should mention $containing", expected.message.orEmpty().contains(containing))
        }
    }

    @Test fun configLinesAreReadAsRetroArchReadsThem() {
        val parsed = ConfigFile.parse("""
            # a comment
            shaders = "2"   # passes
            scale0 = 2.0 # unquoted, then a comment
            alias0 = "WITH SPACE"
            broken = "never closed
            #reference "base.glslp"
            texture_wrap_mode1 = "clamp_to_edge"
            shaders = 3
        """.trimIndent())
        assertEquals("3", parsed.values["shaders"])
        assertEquals("2.0", parsed.values["scale0"])
        assertEquals("WITH SPACE", parsed.values["alias0"])
        assertFalse("broken" in parsed.values)
        assertEquals(listOf("base.glslp"), parsed.references)
    }

    @Test fun passesTexturesAndScalesFollowRetroArch() {
        write("crt/shaders/a.glsl", "#pragma parameter MASK \"Mask\" 1.0 0.0 3.0 1.0\nvoid main() {}\n")
        write("crt/shaders/b.glsl", "#pragma parameter GLOW \"Glow\" 0.5 0.0 1.0\n#pragma parameter BAD \"no numbers\"\n")
        write("blurs/c.glsl", "void main() {}")
        write("crt/shaders/mask.png", "not decoded here")
        write("crt/preset.glslp", """
            shaders = "3"
            textures = "mask_texture"
            mask_texture = "shaders/mask.png"
            mask_texture_wrap_mode = "repeat"
            mask_texture_mipmap = "true"
            shader0 = "shaders/a.glsl"
            alias0 = "ORIG_LINEARIZED"
            filter_linear0 = "false"
            srgb_framebuffer0 = "true"
            scale_type0 = "source"
            scale0 = "1.0"
            shader1 = "shaders/b.glsl"
            scale_type_y1 = "viewport"
            scale_y1 = "0.0625"
            float_framebuffer1 = "true"
            mipmap_input1 = "true"
            frame_count_mod1 = "2"
            shader2 = "../blurs/c.glsl"
            scale_type2 = "absolute"
            scale_x2 = "320"
            scale_y2 = "240"
            wrap_mode2 = "mirrored_repeat"
            GLOW = "0.75"
        """.trimIndent())
        val preset = read("crt/preset.glslp")
        assertEquals(3, preset.passes.size)
        val (first, second, third) = preset.passes
        assertEquals("ORIG_LINEARIZED", first.alias)
        assertEquals(0, first.filter)
        assertTrue(first.srgbFramebuffer)
        assertEquals(Scale.SOURCE to Scale.SOURCE, first.scaleTypeX to first.scaleTypeY)
        assertEquals(Wrap.BORDER, first.wrap)
        // Only the y axis named: x is the source at its own size.
        assertEquals(Scale.SOURCE to Scale.VIEWPORT, second.scaleTypeX to second.scaleTypeY)
        assertEquals(1f to 0.0625f, second.scaleX to second.scaleY)
        assertEquals(-1, second.filter)
        assertTrue(second.floatFramebuffer && second.mipmapInput)
        assertEquals(2, second.frameCountMod)
        assertEquals(Scale.ABSOLUTE to Scale.ABSOLUTE, third.scaleTypeX to third.scaleTypeY)
        assertEquals(320f to 240f, third.scaleX to third.scaleY)
        assertEquals(Wrap.MIRRORED_REPEAT, third.wrap)
        assertEquals(File(library, "blurs/c.glsl").canonicalFile, third.file)

        val texture = preset.textures.single()
        assertEquals("mask_texture", texture.name)
        assertTrue(texture.linear && texture.mipmap)
        assertEquals(Wrap.REPEAT, texture.wrap)
        // Initial values from #pragma parameter, the preset's own value over them; a malformed line is not one.
        assertEquals(mapOf("MASK" to 1f, "GLOW" to 0.75f), preset.parameters)
        assertEquals(-1, preset.feedbackPass)
    }

    @Test fun aPassWithoutAScaleIsLeftToTheDefaults() {
        write("a.glsl", "")
        write("plain.glslp", "shaders = 1\nshader0 = a.glsl\nfeedback_pass = \"0\"\n")
        val pass = read("plain.glslp").passes.single()
        assertEquals(Scale.NONE to Scale.NONE, pass.scaleTypeX to pass.scaleTypeY)
        assertEquals(0, read("plain.glslp").feedbackPass)
    }

    @Test fun aReferenceIsTheBaseAndTheReferringFileOverridesIt() {
        write("base/shaders/pass.glsl", "#pragma parameter BORDER_ON \"Border\" 1.0 0.0 1.0 1.0\n")
        write("base/default.png", "")
        write("textures/other.png", "")
        write("base/base.glslp", """
            shaders = 1
            shader0 = shaders/pass.glsl
            textures = BORDER
            BORDER = default.png
        """.trimIndent())
        write("leaf.glslp", """
            #reference "base/base.glslp"
            BORDER = "textures/other.png"
            BORDER_ON = "0.0"
        """.trimIndent())
        val preset = read("leaf.glslp")
        assertEquals(File(library, "base/shaders/pass.glsl").canonicalFile, preset.passes.single().file)
        // The override's path is relative to the file that wrote it.
        assertEquals(File(library, "textures/other.png").canonicalFile, preset.textures.single().file)
        assertEquals(mapOf("BORDER_ON" to 0f), preset.parameters)
    }

    @Test fun nothingOutsideTheLibraryIsRead() {
        File(folder.root, "outside.glsl").writeText("")
        write("escape.glslp", "shaders = 1\nshader0 = \"../outside.glsl\"\n")
        unusable("escape.glslp", "outside the shader library")
        write("refer.glslp", "#reference \"../../elsewhere.glslp\"\n")
        unusable("refer.glslp", "outside the shader library")
    }

    @Test fun anythingRetroArchWouldNotLoadIsUnusable() {
        write("cg.cg", "")
        write("cg.glslp", "shaders = 1\nshader0 = cg.cg\n")
        unusable("cg.glslp", "not a GLSL shader")
        write("missing.glslp", "shaders = 1\nshader0 = nowhere.glsl\n")
        unusable("missing.glslp", "missing")
        write("wild.glslp", "shaders = 1\nshader0 = \"\$CORE\$/a.glsl\"\n")
        unusable("wild.glslp", "wildcard")
        write("a.glsl", "")
        write("scale.glslp", "shaders = 1\nshader0 = a.glsl\nscale_type0 = \"stretch\"\n")
        unusable("scale.glslp", "unknown scale type")
        write("empty.glslp", "# nothing\n")
        unusable("empty.glslp", "names no shader passes")
        write("texture.glslp", "shaders = 1\nshader0 = a.glsl\ntextures = \"LUT\"\n")
        unusable("texture.glslp", "texture LUT")
        write("loop.glslp", "#reference \"loop.glslp\"\n")
        unusable("loop.glslp", "refers to itself")
    }

    @Test fun parameterLinesAreReadLikeSscanf() {
        assertEquals("A" to 0.5f, PresetReader.parameter("#pragma parameter A \"Desc\" 0.5 0.0 1.0"))
        assertEquals("B" to 2f, PresetReader.parameter("#pragma parameter B \"Desc\" 2.0 0.0 4.0 0.5"))
        assertEquals(null, PresetReader.parameter("#pragma parameter C \"Desc\" 2.0"))
        assertEquals(null, PresetReader.parameter("  #pragma parameter D \"Desc\" 1 0 1"))
        assertEquals(null, PresetReader.parameter("#pragma once"))
    }
}
