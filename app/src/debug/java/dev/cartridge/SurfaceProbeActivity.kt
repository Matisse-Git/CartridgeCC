package dev.cartridge

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.GameRequest
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.rendering.CartridgeSurface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File

/** Debug-only instrumentation fixture: real GPU surfaces and the packaged mGBA core. */
class SurfaceProbeActivity : ComponentActivity() {
    @Volatile var completedCycles = 0
        private set
    @Volatile var failure: String? = null
        private set
    private lateinit var engine: LibretroEngine
    private lateinit var host: FrameLayout
    private var cartridge: CartridgeSurface? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
        engine = LibretroEngine(this, lifecycleScope)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { assets.open("models/gba_cartridge.glb").use { it.readBytes() } }
                val request = GameRequest(File(checkNotNull(intent.getStringExtra("rom"))), "c".repeat(64))
                repeat(3) {
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
                    engine.prepare(request)
                    check(engine.status.value.state == SessionState.Running) { engine.status.value.error ?: "Core never ran" }
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
                }
            } catch (problem: Throwable) { failure = problem.stackTraceToString() }
        }
    }
    override fun onDestroy() {
        cartridge?.close()
        engine.releaseOnDestroy()
        host.removeAllViews()
        super.onDestroy()
    }
}
