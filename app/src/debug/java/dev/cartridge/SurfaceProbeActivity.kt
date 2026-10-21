package dev.cartridge

import android.os.Bundle
import android.os.Debug
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.GameRequest
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.rendering.CollectionAssets
import dev.cartridge.storage.SaveStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File

/** Debug-only instrumentation fixture: real GPU surfaces and the packaged mGBA core. */
class SurfaceProbeActivity : ComponentActivity() {
    @Volatile var completedCycles = 0
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var memorySamplesKiB: Map<Int, Int> = emptyMap()
        private set
    private lateinit var engine: LibretroEngine
    private lateinit var host: FrameLayout
    private var cartridge: CartridgeSurface? = null
    private var phase = "creating"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = FrameLayout(this)
        setContentView(host)
        engine = LibretroEngine(this, lifecycleScope)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { CollectionAssets.read(assets) }
                val request = GameRequest(File(checkNotNull(intent.getStringExtra("rom"))), "c".repeat(64))
                verifyFailedPreparationRecovery(request)
                val cycles = intent.getIntExtra("cycles", 3).coerceIn(1, 50)
                repeat(cycles) { cycle ->
                    phase = "cycle ${cycle + 1}: Filament"
                    val rendered = CompletableDeferred<Unit>()
                    cartridge = CartridgeSurface(this@SurfaceProbeActivity, bytes, 1,
                        onReady = { rendered.complete(Unit) }, onReleased = {})
                    host.addView(cartridge)
                    cartridge!!.resumeRendering()
                    withTimeout(10_000) { rendered.await() }
                    cartridge!!.close()
                    host.removeAllViews()
                    cartridge = null
                    val attach = launch {
                        engine.surface.collect { surface ->
                            host.removeAllViews()
                            surface?.let { host.addView(it) }
                        }
                    }
                    phase = "cycle ${cycle + 1}: core preparation"
                    engine.prepare(request)
                    check(engine.status.value.state == SessionState.Running) { engine.status.value.error ?: "Core never ran" }
                    if (cycle == 0) verifyProbePixels()
                    engine.pause()
                    check(engine.status.value.state == SessionState.Paused)
                    engine.saveState()
                    engine.loadState()
                    engine.resume()
                    delay(100)
                    engine.stop()
                    attach.cancelAndJoin()
                    host.removeAllViews()
                    check(engine.status.value.state == SessionState.Idle) { engine.status.value.error ?: "Core did not stop" }
                    completedCycles++
                    if (completedCycles in setOf(1, 10, 50)) {
                        memorySamplesKiB = memorySamplesKiB + (completedCycles to Debug.getPss().toInt())
                    }
                }
            } catch (problem: Throwable) { failure = "$phase\n${problem.stackTraceToString()}" }
        }
    }

    private suspend fun verifyFailedPreparationRecovery(request: GameRequest) {
        // Always exercise failed initialization WITH existing battery bytes; SurfaceCreated is
        // not upstream proof of success. This synthetic fixture ID never identifies a user ROM.
        withContext(Dispatchers.IO) { SaveStore(File(filesDir, "saves")).write(request.id, "sram", ByteArray(32 * 1024) { 1 }) }
        phase = "missing ROM recovery"
        val missing = File(filesDir, "missing-m0-probe.gba")
        check(!missing.exists())
        engine.prepare(request.copy(rom = missing))
        check(engine.status.value.state == SessionState.Error)
        check(engine.surface.value == null)
        engine.stop()
        check(engine.status.value.state == SessionState.Idle)

        phase = "invalid ROM native rejection and recovery"
        val invalid = File(filesDir, "invalid-m0-probe.gba")
        withContext(Dispatchers.IO) { invalid.writeBytes(ByteArray(192)) }
        val attach = lifecycleScope.launch {
            engine.surface.collect { surface ->
                host.removeAllViews()
                surface?.let { host.addView(it) }
            }
        }
        try {
            engine.prepare(request.copy(rom = invalid))
            check(engine.status.value.state == SessionState.Error) { "The invalid ROM was not rejected" }
            engine.stop()
            check(engine.status.value.state == SessionState.Idle)
        } finally {
            attach.cancelAndJoin()
            host.removeAllViews()
            withContext(Dispatchers.IO) { invalid.delete() }
        }
        // The subsequent genuine cartridge/core cycles prove that native ownership was released.
    }

    /** Sample the actual GPU framebuffer, not just an upstream readiness event. */
    private suspend fun verifyProbePixels() {
        val view = checkNotNull(engine.surface.value)
        phase = "initial gray framebuffer"
        awaitColor(view, green = false)
        engine.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        phase = "A changes framebuffer to green"
        awaitColor(view, green = true)
        engine.pause() // Releasing held input must not step the paused core.
        engine.saveState()
        engine.resume()
        engine.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        phase = "B resets framebuffer to gray"
        awaitColor(view, green = false)
        engine.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)
        engine.pause()
        engine.loadState()
        engine.resume()
        phase = "saved state restores green framebuffer"
        awaitColor(view, green = true)
        engine.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        awaitColor(view, green = false)
        engine.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)
    }

    private suspend fun awaitColor(view: SurfaceView, green: Boolean) = withTimeout(5_000) {
        while (true) {
            // FrameRendered is emitted before EGL swaps its first buffer. Only NO_DATA is retryable.
            val color = try { centerPixel(view) } catch (_: NoFrameYet) { delay(50); continue }
            val matches = if (green) isGreen(color) else {
                val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                r in 40..180 && kotlin.math.abs(r - g) < 15 && kotlin.math.abs(g - b) < 15
            }
            if (matches) break
            delay(50)
        }
    }
    private fun isGreen(color: Int) = Color.green(color) > 180 && Color.red(color) < 40 && Color.blue(color) < 40
    private class NoFrameYet : Exception()

    private suspend fun centerPixel(view: SurfaceView): Int {
        check(view.width >= 8 && view.height >= 8)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val result = CompletableDeferred<Int>()
        val x = view.width / 2; val y = view.height / 2
        PixelCopy.request(view, Rect(x - 4, y - 4, x + 4, y + 4), bitmap, { code ->
            if (code == PixelCopy.SUCCESS) result.complete(bitmap.getPixel(4, 4))
            else result.completeExceptionally(if (code == PixelCopy.ERROR_SOURCE_NO_DATA) NoFrameYet()
                else IllegalStateException("Framebuffer copy failed: $code"))
            bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
        return withTimeout(3_000) { result.await() }
    }
    override fun onDestroy() {
        cartridge?.close()
        engine.releaseOnDestroy()
        host.removeAllViews()
        super.onDestroy()
    }
}
