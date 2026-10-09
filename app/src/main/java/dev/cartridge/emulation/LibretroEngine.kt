package dev.cartridge.emulation

import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import dev.cartridge.domain.SessionMachine
import dev.cartridge.domain.SessionState
import dev.cartridge.storage.SaveStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** All state/lifecycle mutations run on Main. JNI save calls run on the view's GL thread. */
class LibretroEngine(private val context: Context, private val scope: CoroutineScope) : EmulationEngine {
    private class SessionOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val mutex = Mutex()
    private val machine = SessionMachine()
    private val mutableStatus = MutableStateFlow(EngineStatus())
    override val status = mutableStatus.asStateFlow()
    private val mutableSurface = MutableStateFlow<GLRetroView?>(null)
    val surface = mutableSurface.asStateFlow()
    private val saves = SaveStore(File(context.filesDir, "saves"))
    private var owner: SessionOwner? = null
    private var game: GameRequest? = null
    private var eventJob: Job? = null
    private var errorJob: Job? = null
    private val pressed = mutableSetOf<Int>()
    private var ownsNative = false
    private var coreReady = false

    private fun transition(next: SessionState, error: String? = null) {
        machine.transition(next)
        mutableStatus.value = EngineStatus(next, error)
    }

    override suspend fun prepare(game: GameRequest) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(machine.state == SessionState.Idle)
            transition(SessionState.Preparing)
            try {
                check(nativeClaim.compareAndSet(false, true)) { "An emulator session is already active" }
                ownsNative = true
                this@LibretroEngine.game = game
                require(game.rom.isFile) { "The game file is missing" }
                val core = File(context.applicationInfo.nativeLibraryDir, "libmgba_libretro.so")
                check(core.isFile) { "The packaged arm64 mGBA core is missing" }
                val savedRam = withContext(Dispatchers.IO) { saves.read(game.id, "sram") }
                val data = GLRetroViewData(context).apply {
                    coreFilePath = core.absolutePath
                    gameFilePath = game.rom.absolutePath
                    systemDirectory = File(context.filesDir, "system").apply { mkdirs() }.path
                    savesDirectory = File(context.filesDir, "saves").apply { mkdirs() }.path
                    rumbleEventsEnabled = false
                    enableMicrophone = false
                }
                val view = GLRetroView(context, data).apply { isFocusable = false }
                val session = SessionOwner()
                owner = session
                mutableSurface.value = view
                val ready = CompletableDeferred<Unit>()
                eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    view.getGLRetroEvents().collect { event ->
                        if (event == GLRetroView.GLRetroEvents.FrameRendered) {
                            coreReady = true
                            ready.complete(Unit)
                        }
                    }
                }
                errorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    view.getGLRetroErrors().collect { code ->
                        val failure = IllegalStateException("Embedded emulator failed (code $code). Check the ROM and native log.")
                        if (!ready.isCompleted) ready.completeExceptionally(failure)
                        else mutex.withLock {
                            if (machine.state in setOf(SessionState.Running, SessionState.Paused)) {
                                releaseNative()
                                transition(SessionState.Error, failure.message)
                            }
                        }
                    }
                }
                // GLRetroView owns core initialization on its renderer thread, after onCreate.
                session.registry.addObserver(view)
                session.registry.currentState = Lifecycle.State.RESUMED
                withTimeout(15_000) { ready.await() }
                if (savedRam != null && savedRam.isNotEmpty()) {
                    // Upstream's initial saveRAMState path ignores the core's rejection result.
                    // Restore explicitly while paused and report incompatibility to the shell.
                    session.registry.currentState = Lifecycle.State.STARTED
                    check(onGl { unserializeSRAM(savedRam, false) }) { "The core rejected the stored SaveRAM" }
                    session.registry.currentState = Lifecycle.State.RESUMED
                }
                transition(SessionState.Running)
            } catch (failure: Throwable) {
                releaseNative()
                transition(SessionState.Error, failure.message ?: "Emulator initialization failed")
                if (failure is CancellationException) throw failure
            }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (machine.state != SessionState.Running) return@withLock
            releaseInput()
            // ON_PAUSE waits for GLSurfaceView's GL thread before pausing native audio.
            owner!!.registry.currentState = Lifecycle.State.STARTED
            transition(SessionState.Paused)
        }
    }
    override suspend fun resume() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (machine.state != SessionState.Paused) return@withLock
            owner!!.registry.currentState = Lifecycle.State.RESUMED
            transition(SessionState.Running)
        }
    }
    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (machine.state == SessionState.Idle) return@withLock
            transition(SessionState.Stopping)
            var saveFailure: Throwable? = null
            try {
                releaseInput()
                if (coreReady && mutableSurface.value != null) {
                    owner!!.registry.currentState = Lifecycle.State.STARTED
                    val bytes = onGl { serializeSRAM(false) }
                    withContext(Dispatchers.IO) { saves.write(game!!.id, "sram", bytes) }
                }
            } catch (failure: Throwable) { saveFailure = failure }
            finally { releaseNative() }
            if (saveFailure != null) transition(SessionState.Error, "SaveRAM could not be written: ${saveFailure.message}")
            else transition(SessionState.Idle)
        }
    }
    override fun key(action: Int, androidKeyCode: Int) {
        if (machine.state != SessionState.Running) return
        if (action == KeyEvent.ACTION_DOWN) pressed.add(androidKeyCode) else pressed.remove(androidKeyCode)
        mutableSurface.value?.sendKeyEvent(action, androidKeyCode, 0)
    }
    override fun motion(x: Float, y: Float) {
        if (machine.state == SessionState.Running) mutableSurface.value?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, 0)
    }
    private fun releaseInput() {
        mutableSurface.value?.let { view ->
            pressed.forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it, 0) }
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, 0)
        }
        pressed.clear()
    }

    /** Avoid GLRetroView's blocking latch API: propagate GL exceptions and bound waits. */
    private suspend fun <T> onGl(block: GLRetroView.() -> T): T {
        val view = checkNotNull(mutableSurface.value) { "No emulator surface" }
        val result = CompletableDeferred<T>()
        view.queueEvent {
            try { result.complete(view.block()) }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return withTimeout(3_000) { result.await() }
    }
    private suspend fun <T> pausedOperation(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(machine.state == SessionState.Paused) { "Open the pause menu first" }
            block()
        }
    }
    override suspend fun saveState() = pausedOperation {
        val bytes = onGl { serializeState(false) }
        withContext(Dispatchers.IO) { saves.write(game!!.id, "state", bytes) }
    }
    override suspend fun loadState() = pausedOperation {
        val bytes = withContext(Dispatchers.IO) { saves.read(game!!.id, "state") }
            ?: error("There is no saved state for this game")
        check(onGl { unserializeState(bytes, false) }) { "The core rejected this saved state" }
    }
    override suspend fun persistSaveRam() = pausedOperation {
        val bytes = onGl { serializeSRAM(false) }
        withContext(Dispatchers.IO) { saves.write(game!!.id, "sram", bytes) }
    }
    override suspend fun configure(options: Map<String, String>) = pausedOperation {
        onGl {
            val supported = getVariables().mapNotNull { it.key }.toSet()
            require(options.keys.all { it in supported }) { "Unsupported core option" }
            updateVariables(*options.map { Variable(it.key, it.value) }.toTypedArray())
        }
    }

    private fun releaseNative() {
        eventJob?.cancel(); eventJob = null
        errorJob?.cancel(); errorJob = null
        val view = mutableSurface.value
        owner?.let {
            if (it.registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                it.registry.currentState = Lifecycle.State.STARTED
            }
            // Stop even an aborted preparation with no RenderLifecycleObserver installed.
            view?.onPause()
            it.registry.currentState = Lifecycle.State.DESTROYED
        }
        mutableSurface.value = null
        owner = null
        coreReady = false
        if (ownsNative) nativeClaim.set(false)
        ownsNative = false
    }
    /** Last-resort Activity destruction barrier; regular ejection persists RAM first. */
    fun releaseOnDestroy() { releaseNative() }
    companion object { private val nativeClaim = AtomicBoolean(false) }
}
