package dev.cartridge

import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.shaders.PresetReader
import dev.cartridge.shaders.ShaderLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR 0027, step 2: presets are read, built and drawn on this GPU in a GL context of their own, the way
 * Settings checks one before saving it. The library itself is downloaded, never committed: these tests are
 * skipped where `files/shaders/library` holds none.
 */
class ShaderPresetTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val library get() = ShaderLibrary.root(context)

    /** On a thread of its own, as a check always runs: never one with a GL context, never Main. */
    private fun check(root: File, preset: File): String? {
        val thread = Executors.newSingleThreadExecutor()
        try {
            return thread.submit<String?> {
                try { ShaderLibrary.check(ShaderLibrary.load(root, preset)) } catch (unusable: PresetReader.Unusable) { "unusable: ${unusable.message}" }
            }.get(120, TimeUnit.SECONDS)
        } finally {
            thread.shutdown()
        }
    }

    @Test fun aBrokenPresetIsRefusedWithItsReason() {
        val root = File(context.cacheDir, "shader-test").apply { deleteRecursively(); mkdirs() }
        try {
            File(root, "broken.glsl").writeText("""
                #version 130
                #if defined(VERTEX)
                in vec4 VertexCoord;
                uniform mat4 MVPMatrix;
                void main() { gl_Position = MVPMatrix * VertexCoord; }
                #elif defined(FRAGMENT)
                out vec4 color;
                void main() { color = this_is_not_glsl; }
                #endif
            """.trimIndent())
            File(root, "broken.glslp").writeText("shaders = 1\nshader0 = broken.glsl\n")
            val failure = check(root, File(root, "broken.glslp"))
            assertNotNull(failure)
            assertTrue(failure!!, failure.contains("broken.glsl") && failure.contains("fragment stage"))

            File(root, "fine.glsl").writeText("""
                #version 130
                #if defined(VERTEX)
                in vec4 VertexCoord;
                in vec2 TexCoord;
                out vec2 uv;
                uniform mat4 MVPMatrix;
                void main() { uv = TexCoord; gl_Position = MVPMatrix * VertexCoord; }
                #elif defined(FRAGMENT)
                precision mediump float;
                uniform sampler2D Texture;
                uniform sampler2D PrevTexture;
                in vec2 uv;
                out vec4 color;
                void main() { color = mix(texture(Texture, uv), texture(PrevTexture, uv), 0.5); }
                #endif
            """.trimIndent())
            File(root, "fine.glslp").writeText("shaders = 1\nshader0 = fine.glsl\n")
            assertNull(check(root, File(root, "fine.glslp")))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Every preset the library holds, in a report on the device (files/diagnostics/shader-check.txt) and the log. */
    @Test fun everyPresetInTheLibraryIsCheckedOnThisGpu() {
        assumeTrue("No shader library in files/shaders/library", library.isDirectory)
        // -e presets a.glslp,b.glslp checks only those.
        val only = InstrumentationRegistry.getArguments().getString("presets")?.split(',')?.toSet()
        val presets = library.walkTopDown().filter { it.isFile && it.extension == "glslp" }.sortedBy { it.path }
            .filter { only == null || it.relativeTo(library).invariantSeparatorsPath in only }.toList()
        assumeTrue("The shader library holds no presets", presets.isNotEmpty())
        val results = linkedMapOf<String, String?>()
        val times = linkedMapOf<String, Long>()
        for (preset in presets) {
            val name = preset.relativeTo(library).invariantSeparatorsPath
            val began = System.nanoTime()
            results[name] = check(library, preset)
            times[name] = (System.nanoTime() - began) / 1_000_000
            android.util.Log.i(TAG, "$name ${times[name]} ms: ${results[name] ?: "works"}")
        }
        val working = results.count { it.value == null }
        val slowest = times.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} ${it.value} ms" }
        val report = "presets=${presets.size} working=$working refused=${presets.size - working}\nslowest: $slowest\n" +
            results.filterValues { it != null }.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        File(context.filesDir, "diagnostics").apply { mkdirs() }.resolve("shader-check.txt").writeText(report)
        android.util.Log.i(TAG, report.lineSequence().take(2).joinToString(" | "))
        // The presets the owner's checks rely on (ADR 0027).
        for (needed in listOf("crt/crt-royale.glslp", "handheld/lcd-grid-v2-gba-color-motionblur.glslp")) {
            if (needed in results) assertEquals("$needed: ${results[needed]}", null, results[needed])
        }
    }

    private companion object { const val TAG = "CartridgeShaders" }
}
