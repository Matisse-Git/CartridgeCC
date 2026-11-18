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
import dev.cartridge.domain.Snapshots
import dev.cartridge.environment.GameOptions
import dev.cartridge.shaders.ShaderLibrary
import dev.cartridge.storage.SaveStore
import dev.cartridge.storage.SessionStore
import dev.cartridge.storage.StateSlots
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
    /** The shader preset a game is shown with (ADR 0027), a file inside [shaderLibrary], or none. */
    private val sessionShader: (GameRequest) -> File? = { null },
    private val shaderLibrary: File = ShaderLibrary.root(context),
    private val sessionStore: SessionStore? = null,
    /** Where a core looks for firmware. Tests give it an empty one so diagnostics never depend on the user's BIOS. */
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
    private val slots = StateSlots(File(context.filesDir, "saves"))
    private var owner: SessionOwner? = null
    private var game: GameRequest? = null
    private var eventJob: Job? = null
    private var errorJob: Job? = null
    private var shaderJob: Job? = null
    /** A preset is drawn: the screen effect waits behind it (ADR 0027). */
    private var presetActive = false
    private var checkpointJob: Job? = null
    private var lastWrittenRam: ByteArray? = null
    private var lastWrittenRtc: ByteArray? = null
    private var corePath = ""
    private val pressed = mutableSetOf<Int>()
    private var ownsNative = false
    private var coreReady = false
    /** Paused by the freeze combo: no menu, and the speed is kept for when it goes on (ADR 0031). */
    private var frozen = false
    private var speed = 1
    /** The disc in the drive and how many the engine can change between (ADR 0032); 0 where it cannot. */
    private var disc = 0
    private var discs = 0

    private fun transition(next: SessionState, error: String? = null) {
        machine.transition(next)
        val live = next in setOf(SessionState.Running, SessionState.Paused)
        mutableStatus.value = EngineStatus(next, error, if (live) status.value.saveWarning else null,
            frozen = frozen && next == SessionState.Paused, speed = if (live) speed else 1,
            disc = if (live) disc else 0, discs = if (live) discs else 0)
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
                // Which state is the user's choice: the quick save, or the newest of all. Never an older one in its place.
                val fromState = game.startFromState ?: (options[GameOptions.START_FROM_STATE] == "enabled")
                val newest = game.startFromState == true || options[GameOptions.START_SLOT] == GameOptions.LATEST
                val startState = if (game.resumeState != null || !fromState) null else try {
                    withContext(Dispatchers.IO) {
                        if (newest) slots.latest(game.id, game.system)?.let { slots.read(game.id, game.system, it.name) }
                        else saves.read(game.id, "state")
                    }?.let { dev.cartridge.storage.ManualState.decode(game.system, it) }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    startNotice = STATE_NOT_USED
                    null
                }
                // A preset is optional too: one that cannot be read gives the normal picture and one notice.
                var shaderNotice: String? = null
                val preset = sessionShader(game)?.let { file ->
                    try {
                        ShaderConfig.Preset(withContext(Dispatchers.IO) { ShaderLibrary.load(shaderLibrary, file) })
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        shaderNotice = shaderNotUsed(file, failure.message)
                        null
                    }
                }
                // A core that writes its own files gets a folder per game, so two games never share one.
                val ownFiles = File(context.filesDir, "saves").let { if (game.system.core.ownFiles) File(it, "${game.id}.files") else it }.apply { mkdirs() }
                game.system.core.data?.let { (archive, folder) ->
                    withContext(Dispatchers.IO) { dev.cartridge.storage.CoreData.unpack(context.assets, archive, File(systemDirectory, folder), game.system.core.revision) }
                }
                // A game of several discs on an engine that changes them is started from a playlist of all of them, on the
                // disc asked for (ADR 0032). Elsewhere the disc asked for is the game file.
                val playlist = if (game.discs.size > 1 && game.system.core.discs) withContext(Dispatchers.IO) { writePlaylist(game) } else null
                val data = GLRetroViewData(context).apply {
                    coreFilePath = core.absolutePath
                    // An engine that takes a disc as one image is started from the sheet's first track, which holds the game.
                    // The path with no link in it: an engine that works out a program's folder for itself resolves
                    // links on the way, and then no longer recognises the path it was given as the same file.
                    gameFilePath = playlist?.first?.path
                        ?: (if (!game.system.core.sheets && game.rom.extension == "cue") File(game.rom.parentFile, "track-00.bin") else game.rom).canonicalPath
                    if (playlist != null) { initialDisk = game.disc; initialDiskPath = playlist.second[game.disc] }
                    systemDirectory = this@LibretroEngine.systemDirectory.apply { mkdirs() }.path
                    savesDirectory = ownFiles.path
                    rumbleEventsEnabled = false
                    enableMicrophone = false
                    shader = preset ?: screenEffect(options[GameOptions.SCREEN_EFFECT])
                    presetActive = preset != null
                    // Firmware and memory-card ownership are fixed by the save design; user options cannot replace them.
                    variables = ((options - GameOptions.adapterKeys) + game.system.core.fixed).map { Variable(it.key, it.value) }.toTypedArray()
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
                // A preset that the GPU will not build, or that fails while drawing, is replaced by the default
                // picture on the GL thread; the player hears about it once.
                shaderJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    view.getShaderFailures().collect { reason ->
                        val notice = shaderNotUsed(sessionShader(game), reason)
                        if (machine.state in setOf(SessionState.Running, SessionState.Paused)) mutableStatus.value = status.value.copy(notice = notice)
                        else shaderNotice = notice
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
                // What every Libretro frontend does after loading a game: say what is in each port. One pad, and the
                // other three named as empty, because an engine may wait to hear about all of them before it fits
                // the first with its memory unit (Flycast does).
                if (game.system.core.emptyPorts) onGl {
                    setControllerType(0, RETRO_DEVICE_JOYPAD)
                    for (port in 1..3) setControllerType(port, RETRO_DEVICE_NONE)
                }
                // What is in the drive. One file that holds several discs starts on its disc here, before any frame runs.
                if (game.system.core.discs) {
                    discs = onGl { getAvailableDisks(false) }.coerceAtLeast(0)
                    if (playlist == null && game.disc in 1 until discs) onGl { changeDisk(game.disc, false) }
                    readDisc()
                    if (discs < 2) discs = 0
                }
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
                // A state names the disc it was made on, and the engine puts that disc back as it loads it.
                if (discs > 0) readDisc()
                session.registry.currentState = Lifecycle.State.RESUMED
                withTimeout(15_000) { ready.await() }
                transition(SessionState.Running)
                listOfNotNull(startNotice, shaderNotice).takeIf { it.isNotEmpty() }?.let {
                    mutableStatus.value = status.value.copy(notice = it.joinToString(" "))
                }
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
            // The menu opens over the frame a freeze already holds; the game was written when it froze.
            if (machine.state == SessionState.Paused && frozen) {
                frozen = false; applySpeed(1)
                mutableStatus.value = status.value.copy(frozen = false, speed = 1)
                return@withLock
            }
            if (machine.state != SessionState.Running) return@withLock
            applySpeed(1)
            halt()
        }
    }
    override suspend fun freeze() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (machine.state != SessionState.Running) return@withLock
            frozen = true
            halt()
        }
    }
    /** A pause, menu or not: it is what writes the save, so the picture it keeps is optional. */
    private suspend fun halt() {
        releaseInput()
        // GLSurfaceView releases its buffers on pause. Retain an actual presented frame for
        // the custom menu, rather than keeping an active emulator render loop behind it.
        mutableFrozenFrame.value = capturePresentedFrame()
        // ON_PAUSE waits for GLSurfaceView's GL thread before pausing native audio.
        owner!!.registry.currentState = Lifecycle.State.STARTED
        transition(SessionState.Paused)
        checkpointWithWarning()
    }
    override suspend fun speed(frames: Int) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            require(frames >= 1) { "Invalid speed" }
            if (machine.state != SessionState.Running) return@withLock
            val current = checkNotNull(game)
            check(frames == 1 || current.system.core.fastForward) { "Fast-forward isn't available for ${current.system.title}" }
            applySpeed(frames)
            mutableStatus.value = status.value.copy(speed = frames)
        }
    }
    /** The host runs the core [frames] times per frame shown. Sound would be a pitch-shifted blur, so it is off above 1. */
    private fun applySpeed(frames: Int) {
        if (speed == frames) return
        speed = frames
        mutableSurface.value?.let { view -> view.frameSpeed = frames; view.audioEnabled = frames == 1 }
    }
    private suspend fun capturePresentedFrame(): Bitmap? {
        val view = mutableSurface.value ?: return null
        if (view.width <= 0 || view.height <= 0 || !view.isShown) return null
        return withTimeoutOrNull(300) {
            while (true) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val copied = CompletableDeferred<Int>()
                // The screen going to sleep takes the surface away, sometimes before this pause is reached, and a
                // surface that is gone cannot be asked for a copy. The pause goes on without a picture: it is what
                // writes the save, and failing here ended the game unsaved.
                try {
                    PixelCopy.request(view, Rect(0, 0, view.width, view.height), bitmap,
                        { copied.complete(it) }, Handler(Looper.getMainLooper()))
                } catch (gone: IllegalArgumentException) { bitmap.recycle(); return@withTimeoutOrNull null }
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
            frozen = false
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
    override fun analog(stick: Int, x: Float, y: Float) {
        if (machine.state == SessionState.Running) mutableSurface.value?.sendMotionEvent(
            if (stick == 0) GLRetroView.MOTION_SOURCE_ANALOG_LEFT else GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, 0)
    }
    private fun releaseInput() {
        mutableSurface.value?.let { view ->
            pressed.forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it, 0) }
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, 0)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f, 0)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f, 0)
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
    /** The quick save and quick load work during play as well as from the pause menu; the list works from the menu. */
    private suspend fun <T> liveOperation(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(machine.state == SessionState.Running || machine.state == SessionState.Paused) { "No game is running" }
            val current = checkNotNull(game)
            check(current.system.core.snapshots != Snapshots.Never) { "Can't save states on ${current.system.title}" }
            block()
        }
    }
    /**
     * A core whose state is only consistent while it is at rest (`snapshots = Paused`) stops its frames for the moment
     * a state is made or loaded during play, and goes on afterwards. Nothing else about the session changes.
     */
    private suspend fun <T> atRest(block: suspend () -> T): T {
        val rest = machine.state == SessionState.Running && game?.system?.core?.snapshots == Snapshots.Paused
        if (rest) owner!!.registry.currentState = Lifecycle.State.STARTED
        try { return block() }
        finally { if (rest && machine.state == SessionState.Running) owner?.registry?.currentState = Lifecycle.State.RESUMED }
    }
    /**
     * The picture a state is listed with: the screen as the player saw it, copied before the state is made. Paused, that
     * is the frame the pause menu already holds. A picture that cannot be had is no reason not to save.
     */
    private suspend fun thumbnail(): ByteArray? {
        val paused = machine.state == SessionState.Paused
        val source = (if (paused) mutableFrozenFrame.value else capturePresentedFrame()) ?: return null
        return try { withContext(Dispatchers.Default) { runCatching { Thumbnails.encode(source) }.getOrNull() } }
        finally { if (!paused) source.recycle() }
    }
    private suspend fun captureEncoded(): ByteArray {
        val current = checkNotNull(game)
        return dev.cartridge.storage.ManualState.encode(current.system, atRest { onGl { SaveBridge.captureState(corePath) } })
    }
    private suspend fun apply(bytes: ByteArray) {
        val native = dev.cartridge.storage.ManualState.decode(checkNotNull(game).system, bytes)
        check(atRest { onGl { unserializeState(native, false) } }) { "The core rejected this saved state" }
        if (discs > 0) readDisc()
    }
    /** The disc in the drive as the engine has it, into the status. Read on the core's thread. */
    private suspend fun readDisc() {
        disc = onGl { getCurrentDisk(false) }.coerceAtLeast(0)
        mutableStatus.value = status.value.copy(disc = disc, discs = discs)
    }
    /**
     * The playlist a game of several discs is started from: one absolute path per disc, written beside nothing the
     * game owns, and the paths as the engine will name them, so the starting disc can be named the same way.
     */
    private fun writePlaylist(game: GameRequest): Pair<File, List<String>> {
        val paths = game.discs.map { it.canonicalPath }
        val folder = File(context.filesDir, "playlists").apply { check(isDirectory || mkdirs()) { "Cannot create the disc list" } }
        val file = File(folder, "${game.id}.m3u")
        val temporary = File.createTempFile("discs-", ".tmp", folder)
        try {
            java.io.FileOutputStream(temporary).use { it.write(dev.cartridge.domain.Playlist.write(paths).toByteArray()); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot write the disc list" }
        } finally { temporary.delete() }
        return file.canonicalFile to paths
    }
    override suspend fun changeDisc(disc: Int) = pausedOperation {
        val current = checkNotNull(game)
        check(current.system.core.discs && discs > 1) { "This game has one disc" }
        require(disc in 0 until discs) { "There is no such disc" }
        // The host opens the drive, puts the disc in and closes it; the console sees the lid open for a moment as it resumes.
        onGl { changeDisk(disc, false) }
        readDisc()
        check(this.disc == disc) { "The disc could not be changed" }
    }
    override suspend fun loadDiscState(discId: String, name: String, disc: Int) = pausedOperation {
        val current = checkNotNull(game)
        val bytes = withContext(Dispatchers.IO) {
            if (name == StateSlots.QUICK) saves.read(discId, "state") ?: error("There is no quick save for this disc") else slots.read(discId, current.system, name)
        }
        apply(bytes)
        // Made when this disc was a game of its own, the state names the first disc; the disc it was made on goes back in.
        if (discs > 1 && this.disc != disc) { onGl { changeDisk(disc, false) }; readDisc() }
        checkpointWithWarning()
    }
    override suspend fun saveState() = liveOperation {
        val current = checkNotNull(game)
        val picture = thumbnail()
        val bytes = captureEncoded()
        withContext(NonCancellable + Dispatchers.IO) { slots.writeQuick(current.id, bytes, picture) }
        checkpointWithWarning(playing = machine.state == SessionState.Running)
    }
    override suspend fun loadState() = liveOperation {
        val current = checkNotNull(game)
        val bytes = withContext(Dispatchers.IO) { saves.read(current.id, "state") } ?: error("There is no quick save for this game yet")
        apply(bytes)
        checkpointWithWarning(playing = machine.state == SessionState.Running)
    }
    override suspend fun saveSlot(replacing: String?): String = pausedOperation {
        val current = checkNotNull(game)
        check(current.system.core.snapshots != Snapshots.Never) { "Can't save states on ${current.system.title}" }
        val picture = thumbnail()
        val bytes = captureEncoded()
        val name = withContext(NonCancellable + Dispatchers.IO) { slots.write(current.id, current.system, bytes, picture, replacing = replacing) }
        checkpointWithWarning()
        name
    }
    override suspend fun loadSlot(name: String) = pausedOperation {
        val current = checkNotNull(game)
        val bytes = withContext(Dispatchers.IO) { slots.read(current.id, current.system, name) }
        apply(bytes)
        checkpointWithWarning()
    }
    override suspend fun persistSaveRam() = pausedOperation {
        writeBatterySave()
    }
    override suspend fun checkpointSession() = pausedOperation { writeBatterySave(); writeSession() }
    private suspend fun writeSession() {
        val store = sessionStore ?: return
        val current = checkNotNull(game)
        if (current.system.core.snapshots == Snapshots.Never) return
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
    /** [playing] is the periodic checkpoint of a running game, which a core with large states sits out. */
    private suspend fun checkpointWithWarning(playing: Boolean = false) {
        try { writeBatterySave(); if (!playing || game?.system?.core?.snapshots == Snapshots.Always) writeSession() }
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
                        try { checkpointWithWarning(playing = true) }
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
        if (effect != null && !presetActive) checkNotNull(mutableSurface.value).shader = screenEffect(effect)
    }
    override suspend fun reloadShader() = pausedOperation {
        val current = checkNotNull(game)
        val view = checkNotNull(mutableSurface.value)
        val file = sessionShader(current)
        val preset = file?.let {
            try {
                ShaderConfig.Preset(withContext(Dispatchers.IO) { ShaderLibrary.load(shaderLibrary, it) })
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableStatus.value = status.value.copy(notice = shaderNotUsed(it, failure.message))
                null
            }
        }
        // The wrapper keeps the choice and builds it with the next presented frame.
        view.shader = preset ?: screenEffect(sessionOptions(current)[GameOptions.SCREEN_EFFECT])
        presetActive = preset != null
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
        shaderJob?.cancel(); shaderJob = null
        val view = mutableSurface.value
        owner?.let {
            if (it.registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                it.registry.currentState = Lifecycle.State.STARTED
            }
            // Stop even an aborted preparation with no RenderLifecycleObserver installed.
            view?.onPause()
            // The frames have stopped and the thread that ran them still takes work. An engine that draws for itself
            // is unloaded there, as it expects, before the host unloads it from this thread. A view that has left its
            // window has no such thread any more: the engine is then unloaded from here, which is still its own order
            // of letting go rather than the host's.
            if (view != null && game?.system?.core?.draws == true && !view.isAttachedToWindow) {
                try { SaveBridge.unload(corePath) } catch (failure: Throwable) { }
            } else if (view != null && game?.system?.core?.draws == true) {
                val path = corePath
                val unloaded = java.util.concurrent.CountDownLatch(1)
                view.queueEvent { try { SaveBridge.unload(path) } catch (failure: Throwable) { } finally { unloaded.countDown() } }
                unloaded.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            it.registry.currentState = Lifecycle.State.DESTROYED
        }
        mutableSurface.value = null
        mutableFrozenFrame.value = null
        owner = null
        coreReady = false
        // A new view starts the host at normal speed with its sound on.
        frozen = false; speed = 1; disc = 0; discs = 0
        game = null; lastWrittenRam = null; lastWrittenRtc = null
        if (ownsNative) nativeClaim.set(false)
        ownsNative = false
    }
    /** Last-resort Activity destruction barrier; regular ejection persists RAM first. */
    fun releaseOnDestroy() { releaseNative() }
    companion object {
        private val nativeClaim = AtomicBoolean(false)
        /** libretro.h: nothing plugged in, and the standard pad. */
        private const val RETRO_DEVICE_NONE = 0
        private const val RETRO_DEVICE_JOYPAD = 1
        private fun shaderNotUsed(preset: File?, reason: String?) =
            "The shader ${preset?.nameWithoutExtension ?: ""} could not be used, so the game is shown without it. ${reason ?: ""}".replace("  ", " ").trim()
        private const val STATE_NOT_USED = "This game's save state could not be loaded, so it started from the beginning. The state was kept."
    }
}
