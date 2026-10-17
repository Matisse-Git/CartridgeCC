package dev.cartridge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.session.CollectionScreen
import dev.cartridge.shaders.ShaderChoice
import dev.cartridge.shaders.ShaderLevel
import dev.cartridge.shaders.ShaderLibraryUpdater
import dev.cartridge.shaders.ShaderTarget
import dev.cartridge.shell.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * ADR 0027, step 4: shaders chosen through Settings › Shaders and Game options reach the picture. The Super
 * Nintendo probe draws red and white; two fixture presets paint the picture blue or yellow, so the screen
 * itself says which level won. The fixture library is the test's own; the installed one is not touched.
 */
class ShaderSettingsTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, timeout: Long = 30_000, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(timeout) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun row(tag: String) {
        ui.waitUntil(5_000) { ui.shown(tag) }
        ui.onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }.performClick(); ui.waitForIdle()
    }

    private fun solid(red: Float, green: Float, blue: Float) = """
        #version 130
        #if defined(VERTEX)
        in vec4 VertexCoord;
        uniform mat4 MVPMatrix;
        void main() { gl_Position = MVPMatrix * VertexCoord; }
        #elif defined(FRAGMENT)
        precision mediump float;
        out vec4 FragColor;
        void main() { FragColor = vec4($red, $green, $blue, 1.0); }
        #endif
    """.trimIndent()

    private fun library(base: File) {
        val root = File(base, "library/fixture").apply { mkdirs() }
        File(root, "blue.glsl").writeText(solid(0f, 0f, 1f))
        File(root, "yellow.glsl").writeText(solid(1f, 1f, 0f))
        File(root, "broken.glsl").writeText(solid(0f, 0f, 1f).replace("vec4(0.0, 0.0, 1.0, 1.0)", "not_glsl"))
        for (name in listOf("blue", "yellow", "broken")) File(root, "$name.glslp").writeText("shaders = 1\nshader0 = $name.glsl\n")
        File(base, "library/${ShaderLibraryUpdater.VERSION_FILE}").writeText("commit=${"c".repeat(40)}\ndate=2026-10-16T00:00:00Z\npresets=3\nfiles=6\nbytes=1\n")
    }

    /** The middle of the picture: "red", "blue", "yellow", or what it is when none of those. */
    private fun colour(scenario: ActivityScenario<DebugCollectionActivity>): String {
        val result = CompletableFuture<String>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete("no surface") }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val x = view.width / 2; val y = view.height / 2
            PixelCopy.request(view, Rect(x - 16, y - 16, x + 16, y + 16), bitmap, { status ->
                val pixels = IntArray(1024)
                if (status == PixelCopy.SUCCESS) bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                val r = pixels.sumOf { android.graphics.Color.red(it) } / 1024
                val g = pixels.sumOf { android.graphics.Color.green(it) } / 1024
                val b = pixels.sumOf { android.graphics.Color.blue(it) } / 1024
                result.complete(when {
                    status != PixelCopy.SUCCESS -> "copy failed"
                    b > 200 && r < 60 && g < 60 -> "blue"
                    r > 200 && g > 200 && b < 60 -> "yellow"
                    r > 180 && r - g > 40 && r - b > 40 -> "red"
                    else -> "$r,$g,$b"
                })
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun shows(scenario: ActivityScenario<DebugCollectionActivity>, wanted: String) {
        var seen = ""
        try { ui.waitUntil(10_000) { seen = colour(scenario); seen == wanted } }
        catch (failure: Throwable) { throw AssertionError("The picture should be $wanted but is $seen", failure) }
    }
    private fun start(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.tool("insert").performClick()
        await(scenario, 45_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    private fun pause(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("pause").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
    }
    private fun resume(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-0").performClick(); await(scenario) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
    }
    private fun eject(scenario: ActivityScenario<DebugCollectionActivity>) {
        ui.onNodeWithTag("menu-3").performClick()
        await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
    }
    /** Closes Game options and anything over it, back to the pause menu, as B does; B on the pause menu would resume. */
    private fun closeSheets(scenario: ActivityScenario<DebugCollectionActivity>) {
        repeat(8) { var open = false; scenario.onActivity { open = it.shell.top != null; if (open) it.shell.back() }; ui.waitForIdle(); if (!open) return }
    }
    /** Through a picker: the fixture folder, then the preset; waits for its check to finish. */
    private fun pick(scenario: ActivityScenario<DebugCollectionActivity>, preset: String) {
        row("shader-folder-fixture"); row("shader-preset-$preset")
        await(scenario) { it.shell.shaderChecking == null }
    }

    @Test fun eachLevelReachesThePictureAndABrokenPresetIsRefused() {
        val name = "m1-test-${UUID.randomUUID()}"
        val shaderBase = "m1-test-shaders-${UUID.randomUUID()}"
        val base = File(context.filesDir, shaderBase).apply { deleteRecursively() }
        library(base)
        val bytes = File(context.filesDir, "cartridge-probe.sfc").readBytes()
        UUID.randomUUID().toString().take(8).toByteArray().copyInto(bytes, 0x7000)
        File(context.filesDir, "m1-fixtures").apply { mkdirs() }.resolve("probe.sfc").writeBytes(bytes)
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java)
                .putExtra("testDatabase", name).putExtra("testShaderBase", shaderBase)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                Intents.init()
                try {
                    intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,
                        Intent().setData(Uri.parse("content://dev.cartridge.fixture/probe.sfc"))))
                    ui.tool("choose-game").performClick(); ui.waitForIdle()
                } finally { Intents.release() }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.system == GameSystem.SNES }
                var gameId = ""
                scenario.onActivity { gameId = it.coordinator.state.value.selectedGame!!.id }

                // No shader anywhere: the program's own colours.
                start(scenario); shows(scenario, "red"); pause(scenario); eject(scenario)

                // Every console: blue, chosen through Settings › Shaders.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-shaders").performClick()
                scenario.onActivity { assertEquals("Installed", it.shell.page()!!.rows.first { row -> row.id == "update" }.value) }
                row("shaders-global"); pick(scenario, "blue")
                scenario.onActivity {
                    assertEquals(ShaderChoice.Preset("fixture/blue.glslp"), it.shell.shaderChoices.get(ShaderTarget.Global))
                    assertTrue(it.shell.top == Destination.Settings)
                    assertEquals(ShaderLevel.Global, it.shell.resolveShader(GameSystem.SNES, gameId).level)
                }
                ui.home(); start(scenario); shows(scenario, "blue"); pause(scenario); eject(scenario)

                // Super Nintendo's own: yellow, over every console's blue.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-shaders").performClick()
                row("shaders-console-snes"); pick(scenario, "yellow")
                ui.home(); start(scenario); shows(scenario, "yellow")

                // In the paused game: its screen effect says a shader replaces it; the game's own Off wins at resume.
                pause(scenario); row("menu-4")
                scenario.onActivity { assertEquals("Replaced by shader", it.shell.page()!!.rows.first { row -> row.id == "option:snes.effect" }.value) }
                row("option-shader"); row("shader-off")
                scenario.onActivity { assertEquals(ShaderChoice.Off, it.shell.shaderChoices.get(ShaderTarget.Game(gameId, GameSystem.SNES, ""))) }
                closeSheets(scenario); resume(scenario); shows(scenario, "red")

                // A preset that does not build is refused where it was picked, and nothing changes.
                pause(scenario); row("menu-4"); row("option-shader"); pick(scenario, "broken")
                scenario.onActivity {
                    assertTrue("${it.shell.top}", it.shell.top is Destination.ShaderPicker)
                    assertEquals(ShaderChoice.Off, it.shell.shaderChoices.get(ShaderTarget.Game(gameId, GameSystem.SNES, "")))
                    assertEquals(setOf("fixture/broken.glslp"), it.shell.shaderChoices.failed("c".repeat(40)))
                    assertEquals("Didn't work on this device", it.shell.page()!!.rows.first { row -> row.id == "preset:fixture/broken.glslp" }.note)
                }
                // Back to the console's choice: yellow again after resuming.
                closeSheets(scenario); row("menu-4"); row("option-shader"); row("shader-inherit")
                closeSheets(scenario); resume(scenario); shows(scenario, "yellow")
                pause(scenario); eject(scenario)

                // The one Update button, from the library: GitHub's newest library replaces the fixture one in the
                // test's own folder, and the page says what is installed.
                ui.tool("console-settings").performClick(); ui.onNodeWithTag("category-shaders").performClick()
                row("shaders-update")
                await(scenario, 120_000) { it.shell.shaderUpdate == null && it.shell.shaderVersion?.commit.let { commit -> commit != null && commit != "c".repeat(40) } }
                scenario.onActivity {
                    val version = it.shell.shaderVersion!!
                    android.util.Log.i("CartridgeShaders", "Update shaders installed ${version.commit} with ${version.presets} presets")
                    assertTrue(version.presets >= 500)
                    assertEquals("Installed", it.shell.page()!!.rows.first { row -> row.id == "update" }.value)
                    assertTrue(File(base, "library/crt/crt-royale.glslp").isFile)
                }
                ui.home()
            }
        } finally {
            base.deleteRecursively()
            File(context.filesDir, "m1-fixtures/probe.sfc").delete()
        }
    }
}
