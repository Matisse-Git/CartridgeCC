package dev.cartridge

import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.rendering.CollectionAssets
import dev.cartridge.domain.CollectionLayout
import kotlinx.coroutines.*

/** Original study labels only. Measures the real renderer without touching the owner's library. */
class CollectionProfileActivity : ComponentActivity() {
    internal var surface: CartridgeSurface? = null
    @Volatile var startedNanos = 0L
        private set
    @Volatile var finished = false
        private set
    @Volatile var failure: String? = null
        private set
    internal val cpuNanos = mutableListOf<Long>()
    internal var submitted = 0
    internal var callbacks = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        lifecycleScope.launch {
            try {
                val assets = withContext(Dispatchers.IO) { CollectionAssets.read(assets) }
                val ready = CompletableDeferred<Unit>()
                val renderer = CartridgeSurface(this@CollectionProfileActivity, assets, 1,
                    onReady = { ready.complete(Unit) }, onReleased = {}).also { surface = it; it.collectionCount = 3 }
                setContentView(renderer)
                renderer.resumeRendering()
                withTimeout(15_000) { ready.await() }
                delay(1_000) // Exclude asset/shader startup from the steady-state measurement.
                startedNanos = System.nanoTime()
                renderer.frameObserver = { timestamp, cpu, accepted ->
                    if (!finished) {
                        callbacks++; if (accepted) submitted++
                        cpuNanos.add(cpu)
                        val elapsed = (timestamp-startedNanos)/1_000_000
                        when {
                            elapsed < 10_000 -> renderer.selection = 1
                            elapsed < 30_000 -> renderer.selection = intArrayOf(0,1,2,1)[((elapsed-10_000)/150%4).toInt()]
                            elapsed < 36_000 -> {
                                renderer.selection = 1
                                val phase = (elapsed-30_000)%960
                                renderer.insertion = if (phase < 540) phase/540f else 1f-(phase-540)/420f
                            }
                            elapsed < 44_000 -> {
                                renderer.insertion = 0f; renderer.layout = CollectionLayout.Vertical
                                renderer.selection = intArrayOf(0,1,2,1)[((elapsed-36_000)/150%4).toInt()]
                            }
                            elapsed < 54_000 -> {
                                renderer.layout = CollectionLayout.Grid; renderer.collectionCount = 9
                                renderer.selection = ((elapsed-44_000)/150%9).toInt()
                            }
                            else -> { renderer.insertion = 0f; finished = true }
                        }
                    }
                }
            } catch (error: Exception) { failure = error.message ?: error.toString(); finished = true }
        }
    }
    override fun onPause() { surface?.pauseRendering(); super.onPause() }
    override fun onResume() { super.onResume(); surface?.resumeRendering() }
    override fun onDestroy() { surface?.close(); super.onDestroy() }
}
