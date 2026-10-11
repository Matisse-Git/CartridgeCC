package dev.cartridge.emulation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import dev.cartridge.domain.SessionMachine
import dev.cartridge.domain.SessionState
import dev.cartridge.environment.GameOptions
import dev.cartridge.storage.SaveStore
import dev.cartridge.storage.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** All state/lifecycle mutations run on Main. JNI save calls run on the view's GL thread. */
class LibretroEngine(private val context: Context, private val scope: CoroutineScope,
    private val checkpointIntervalMs: Long = 15_000,
    private val sessionOptions: (GameRequest) -> Map<String, String> = { emptyMap() },
    private val sessionStore: SessionStore? = null,
    /** Where a core looks for firmware. Tests give it an empty one so diagnostics never depend on the owner's BIOS. */
    private val systemDirectory: File = File(context.filesDir, "system")) : EmulationEngine {
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
    private val mutableFrozenFrame = MutableStateFlow<Bitmap?>(null)
    val frozenFrame = mutableFrozenFrame.asStateFlow()
    private val saves = SaveStore(File(context.filesDir, "saves"))
    private var owner: SessionOwner? = null
    private var game: GameRequest? = null
    private var eventJob: Job? = null
    private var errorJob: Job? = null
    private var checkpointJob: Job? = null
    private var lastWrittenRam: ByteArray? = null
    private var lastWrittenRtc: ByteArray? = null
    private var corePath = ""
    private val pressed = mutableSetOf<Int>()
    private var ownsNative = false
    private var coreReady = false

    private fun transition(next: SessionState, error: String? = null) {
        machine.transition(next)
        mutableStatus.value = EngineStatus(next, error,
            if (next in setOf(SessionState.Running, SessionState.Paused)) status.value.saveWarning else null)
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
                val core = File(context.applicationInfo.nativeLibraryDir, game.system.coreFile)
                check(core.isFile) { "The packaged ${game.system.title} core is missing" }
                corePath = core.absolutePath
                val savedRam = withContext(Dispatchers.IO) { saves.read(game.id, "sram") }
                val savedRtc = withContext(Dispatchers.IO) { saves.read(game.id, "rtc") }
                lastWrittenRam = savedRam
                lastWrittenRtc = savedRtc
                val options = sessionOptions(game)
                // A start that continues from the game's own Save State. A missing or unreadable one is no reason
                // for the game not to start; a resumed session already has its state.
                var startNotice: String? = null
                val startState = if (game.resumeState != null || options[GameOptions.START_FROM_STATE] != "enabled") null else try {
                    withContext(Dispatchers.IO) { saves.read(game.id, "state") }?.let { dev.cartridge.storage.ManualState.decode(game.system, it) }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    startNotice = STATE_NOT_USED
                    null
                }
                val data = GLRetroViewData(context).apply {
                    coreFilePath = core.absolutePath
                    gameFilePath = game.rom.absolutePath
                    systemDirectory = this@LibretroEngine.systemDirectory.apply { mkdirs() }.path
                    savesDirectory = File(context.filesDir, "saves").apply { mkdirs() }.path
                    rumbleEventsEnabled = false
                    enableMicrophone = false
                    shader = screenEffect(options[GameOptions.SCREEN_EFFECT])
                    // Firmware and memory-card ownership are fixed by the save design; user options cannot replace them.
                    variables = ((options - GameOptions.adapterKeys) + if (game.system == dev.cartridge.domain.GameSystem.PS1) mapOf(
                        "pcsx_rearmed_bios" to "auto", "pcsx_rearmed_memcard1" to "libretro", "pcsx_rearmed_memcard2" to "disabled")
                        else emptyMap()).map { Variable(it.key, it.value) }.toTypedArray()
                }
                val view = GLRetroView(context, data).apply { isFocusable = false }
                val session = SessionOwner()
                owner = session
                mutableSurface.value = view
                val ready = CompletableDeferred<Unit>()
                val initialized = CompletableDeferred<Unit>()
                eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    view.getGLRetroEvents().collect { event ->
                        if (event == GLRetroView.GLRetroEvents.SurfaceCreated) initialized.complete(Unit)
                        // Two produced frames let the restored video pipeline settle before pausing
                        // a new surface; an upstream timing-only draw is not a produced frame.
                        if (event == GLRetroView.GLRetroEvents.FrameRendered && !ready.isCompleted && onGl { SaveBridge.frameCount(corePath) >= 2 }) {
                            coreReady = true
                            ready.complete(Unit)
                        }
                    }
                }
                errorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    view.getGLRetroErrors().collect { code ->
                        val failure = IllegalStateException("Embedded emulator failed (code $code). Check the ROM and native log.")
                        if (!ready.isCompleted) { initialized.completeExceptionally(failure); ready.completeExceptionally(failure) }
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
                // Prime the event scope so its observer cannot be mistaken for successful core setup.
                session.lifecycle.coroutineScope
                val setupObservers = session.registry.observerCount
                // Create the GL surface/core while RenderLifecycleObserver cannot run retro_run.
                session.registry.currentState = Lifecycle.State.STARTED
                withTimeout(15_000) { initialized.await() }
                // In pinned 0.14.0 SurfaceCreated also fires after a swallowed load error. Only
                // successful initializeCore posts RenderLifecycleObserver BEFORE that event.
                // The private session registry has no other observer producers (no reflection).
                // A synchronous Handler check follows the upstream synchronous observer post.
                // Coroutine Main dispatch uses async messages, which can bypass UI sync barriers.
                val setupConfirmed = CompletableDeferred<Unit>()
                Handler(Looper.getMainLooper()).post {
                    if (session.registry.observerCount > setupObservers) setupConfirmed.complete(Unit)
                    else setupConfirmed.completeExceptionally(IllegalStateException(
                        "The embedded core did not finish loading the game. The battery save was kept."))
                }
                withTimeout(15_000) { setupConfirmed.await() }
                suspend fun restoreBattery() {
                    if (savedRam != null && savedRam.isNotEmpty()) {
                        check(onGl {
                            SaveBridge.restore(corePath, savedRam) && SaveBridge.read(corePath).let { restored ->
                                restored.size >= savedRam.size && savedRam.indices.all { restored[it] == savedRam[it] }
                            }
                        }) { "The core rejected the stored battery save. The original file was kept." }
                    }
                    if (savedRtc != null && savedRtc.isNotEmpty()) {
                        check(onGl { SaveBridge.restore(corePath, savedRtc, 1) }) { "The core rejected the cartridge clock save. The original file was kept." }
                    }
                }
                restoreBattery()
                game.resumeState?.let { bytes ->
                    require(bytes.isNotEmpty() && bytes.size <= SessionStore.MAX_STATE) { "The resume state is invalid" }
                    check(onGl { unserializeState(bytes, false) }) { "The core rejected the saved session" }
                }
                if (startState != null) {
                    val accepted = try { onGl { unserializeState(startState, false) } }
                        catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { false }
                    if (!accepted) {
                        // A refused state may have been applied in part: power the game on again and say so.
                        onGl { reset(false) }
                        startNotice = STATE_NOT_USED
                    }
                    // A state carries the battery memory of the moment it was made. The cartridge keeps what was
                    // saved last, so starting from a state never rolls an in-game save back.
                    restoreBattery()
                }
                session.registry.currentState = Lifecycle.State.RESUMED
                withTimeout(15_000) { ready.await() }
                transition(SessionState.Running)
                startNotice?.let { mutableStatus.value = status.value.copy(notice = it) }
                startCheckpoints()
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
            // GLSurfaceView releases its buffers on pause. Retain an actual presented frame for
            // the custom menu, rather than keeping an active emulator render loop behind it.
            mutableFrozenFrame.value = capturePresentedFrame()
            // ON_PAUSE waits for GLSurfaceView's GL thread before pausing native audio.
            owner!!.registry.currentState = Lifecycle.State.STARTED
            transition(SessionState.Paused)
            checkpointWithWarning()
        }
    }
    private suspend fun capturePresentedFrame(): Bitmap? {
        val view = mutableSurface.value ?: return null
        if (view.width <= 0 || view.height <= 0 || !view.isShown) return null
        return withTimeoutOrNull(300) {
            while (true) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val copied = CompletableDeferred<Int>()
                PixelCopy.request(view, Rect(0, 0, view.width, view.height), bitmap,
                    { copied.complete(it) }, Handler(Looper.getMainLooper()))
                if (copied.await() == PixelCopy.SUCCESS) return@withTimeoutOrNull bitmap
                bitmap.recycle(); delay(16)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }
    override suspend fun resume() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (machine.state != SessionState.Paused) return@withLock
            owner!!.registry.currentState = Lifecycle.State.RESUMED
            mutableFrozenFrame.value = null
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
                    writeBatterySave()
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
        val current = checkNotNull(game)
        val bytes = onGl { SaveBridge.captureState(corePath) }
        withContext(NonCancellable + Dispatchers.IO) { saves.write(current.id, "state", dev.cartridge.storage.ManualState.encode(current.system, bytes)) }
        checkpointWithWarning()
    }
    override suspend fun loadState() = pausedOperation {
        val current = checkNotNull(game)
        val bytes = withContext(Dispatchers.IO) { saves.read(current.id, "state") }
            ?: error("There is no saved state for this game")
        val native = dev.cartridge.storage.ManualState.decode(current.system, bytes)
        check(onGl { unserializeState(native, false) }) { "The core rejected this saved state" }
        checkpointWithWarning()
    }
    override suspend fun persistSaveRam() = pausedOperation {
        writeBatterySave()
    }
    override suspend fun checkpointSession() = pausedOperation { writeBatterySave(); writeSession() }
    private suspend fun writeSession() {
        val store = sessionStore ?: return
        val current = checkNotNull(game)
        val bytes = onGl { SaveBridge.captureState(corePath) }
        withContext(NonCancellable + Dispatchers.IO) { store.write(current.id, current.system, bytes) }
    }
    /** Called under the session mutex; capture happens between frames on the core's own GL thread. */
    private suspend fun writeBatterySave() {
        val current = checkNotNull(game)
        val (bytes, rtc) = onGl { SaveBridge.read(corePath) to SaveBridge.read(corePath, 1) }
        if (bytes.isNotEmpty() && lastWrittenRam?.contentEquals(bytes) != true) {
            withContext(NonCancellable + Dispatchers.IO) { saves.write(current.id, "sram", bytes) }
            lastWrittenRam = bytes
        }
        if (rtc.isNotEmpty() && lastWrittenRtc?.contentEquals(rtc) != true) {
            withContext(NonCancellable + Dispatchers.IO) { saves.write(current.id, "rtc", rtc) }
            lastWrittenRtc = rtc
        }
        mutableStatus.value = status.value.copy(saveWarning = null)
    }
    private suspend fun checkpointWithWarning() {
        try { writeBatterySave(); writeSession() }
        catch (failure: TimeoutCancellationException) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            mutableStatus.value = status.value.copy(saveWarning = "Save checkpoint could not be written: ${failure.message}. Pause or eject to retry.")
        }
    }
    private fun startCheckpoints() {
        checkpointJob = scope.launch {
            while (isActive) {
                delay(checkpointIntervalMs.coerceAtLeast(100))
                mutex.withLock {
                    if (machine.state == SessionState.Running) {
                        try { checkpointWithWarning() }
                        catch (failure: TimeoutCancellationException) {
                            releaseNative()
                            transition(SessionState.Error, "Battery save capture timed out. The session was released.")
                        }
                    }
                }
            }
        }
    }
    override suspend fun configure(options: Map<String, String>) = pausedOperation {
        val variables = options - GameOptions.adapterKeys
        if (variables.isNotEmpty()) onGl {
            val supported = getVariables().mapNotNull { it.key }.toSet()
            require(variables.keys.all { it in supported }) { "Unsupported core option" }
            updateVariables(*variables.map { Variable(it.key, it.value) }.toTypedArray())
        }
        // The wrapper stores the shader choice and applies it with the next presented frame.
        val effect = options[GameOptions.SCREEN_EFFECT]
        if (effect != null) checkNotNull(mutableSurface.value).shader = screenEffect(effect)
    }
    private fun screenEffect(value: String?): ShaderConfig = when (value) {
        "sharp" -> ShaderConfig.Sharp
        "lcd" -> ShaderConfig.LCD
        "crt" -> ShaderConfig.CRT
        else -> ShaderConfig.Default
    }

    private fun releaseNative() {
        checkpointJob?.cancel(); checkpointJob = null
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
        mutableFrozenFrame.value = null
        owner = null
        coreReady = false
        game = null; lastWrittenRam = null; lastWrittenRtc = null
        if (ownsNative) nativeClaim.set(false)
        ownsNative = false
    }
    /** Last-resort Activity destruction barrier; regular ejection persists RAM first. */
    fun releaseOnDestroy() { releaseNative() }
    companion object {
        private val nativeClaim = AtomicBoolean(false)
        private const val STATE_NOT_USED = "This game's save state could not be loaded, so it started from the beginning. The state was kept."
    }
}
