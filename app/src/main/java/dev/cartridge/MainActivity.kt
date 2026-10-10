package dev.cartridge

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.MediaMotion
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.environment.HomeRegistration
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.MediaSound
import dev.cartridge.library.*
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.rendering.CollectionAssets
import dev.cartridge.scraping.*
import dev.cartridge.session.*
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.Destination
import dev.cartridge.shell.ShellHost
import dev.cartridge.storage.*
import dev.cartridge.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.io.File

/**
 * Android views, lifecycle, pickers and input delivery only. Session and library decisions live in
 * [CollectionCoordinator]; what is on screen and what each button means live in [ConsoleShell].
 */
open class MainActivity : ComponentActivity(), InputManager.InputDeviceListener, ShellHost {
    internal lateinit var coordinator: CollectionCoordinator
        private set
    internal lateinit var engine: LibretroEngine
        private set
    internal lateinit var scraper: ScrapeCoordinator
        private set
    internal lateinit var shell: ConsoleShell
        private set
    private lateinit var input: ControllerRouter
    private lateinit var labelStore: LabelStore
    private lateinit var mediaSound: MediaSound
    private var cartridgeSurface: CartridgeSurface? = null
    private var emulatorHost: EmulatorHost? = null
    /** A shelf change and the direction it came from, applied by the stage once that shelf is what it draws. */
    private var arrival by mutableStateOf(Triple(0, "", 0))
    internal val collectionSurface get() = cartridgeSurface
    protected open fun createRepository(): CollectionRepository = RoomCollectionRepository(this)
    protected open fun createLabelStore(): LabelStore = LabelStore(File(filesDir, "labels"))
    protected open fun createArtworkProvider(): ArtworkProvider = RetroBaseProvider(File(cacheDir, "scrape-candidates"))
    protected open fun createScrapeJournal(): ScrapeJournal = FileScrapeJournal(File(filesDir, "scrape-job"))
    protected open fun createScrapePreferences(): ScrapePreferenceStore = SharedScrapePreferences(this)
    protected open fun createPackStore(): ScrapePackStore = ScrapePackStore(File(filesDir, "scrape-packs"))
    protected open fun persistDirectoryPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    protected open fun presentationPreferences() = getPreferences(MODE_PRIVATE)
    protected open fun createSessionStore() = SessionStore(File(filesDir, "session-checkpoint"))
    protected open fun systemDirectory() = File(filesDir, "system")
    private val inputFrames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                input.tick(SystemClock.uptimeMillis())
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(shell::folderPicked) }
    private val gamePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) shell.gamePicked(it) else shell.gamePickCancelled() }
    private val labelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(shell::labelPicked) }
    private val packPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(shell::packPicked) }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(shell::backupPicked) }
    private val biosPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(shell::biosPicked) }
    private val backupWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(shell::backupCreated) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val sessionStore = createSessionStore()
        val preferences = presentationPreferences()
        engine = LibretroEngine(this, lifecycleScope, sessionOptions = { game -> shell.sessionOptions(game.system, game.id) }, sessionStore = sessionStore,
            systemDirectory = systemDirectory())
        labelStore = createLabelStore()
        mediaSound = MediaSound(this)
        val repository = createRepository()
        val packStore = createPackStore()
        coordinator = CollectionCoordinator(engine, repository, object : RendererHandoff {
            override fun releaseLibrary() { cartridgeSurface?.close(); cartridgeSurface = null }
            override fun releaseEmulator() { emulatorHost?.release(); emulatorHost = null }
        }, lifecycleScope, preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false),
            layout = runCatching { CollectionLayout.valueOf(preferences.getString("layout", "Horizontal")!!) }.getOrDefault(CollectionLayout.Horizontal),
            onLayoutChanged = { layout -> preferences.edit { putString("layout", layout.name) }; if (::input.isInitialized) input.reset() }, sessionStore = sessionStore,
            initialShelf = Shelf.parse(preferences.getString("shelf", null)),
            initialShelfFocus = runCatching { JSONObject(preferences.getString("shelfFocus", "{}")!!).let { json -> json.keys().asSequence().associateWith(json::getString) } }.getOrDefault(emptyMap()),
            onBrowseChanged = { shelf, focus -> preferences.edit { putString("shelf", shelf.key); putString("shelfFocus", JSONObject(focus).toString()) } })
        scraper = ScrapeCoordinator(PackAwareProvider(createArtworkProvider(), packStore), repository, labelStore, File(filesDir, "roms"), lifecycleScope, createScrapeJournal(), createScrapePreferences(), choosePack = ::pickPack) { game, label ->
            if (coordinator.state.value.allGames.any { it.id == game.id }) coordinator.previewScrapedLabel(game, label)
            else { coordinator.notify("This game is no longer in the collection"); scraper.previewFinished() }
        }
        shell = ConsoleShell(this, lifecycleScope, this, coordinator, engine, repository, scraper, labelStore, sessionStore, packStore, preferences, systemDirectory())
        shell.pendingLocate = savedInstanceState?.getString("recoveryId")
        input = ControllerRouter(navigate = shell::navigate, select = shell::select, back = shell::back, pause = coordinator::pause,
            gameKey = engine::key, gameMotion = engine::motion, adjust = shell::adjust, alternate = shell::alternate, secondary = shell::secondary,
            shoulder = shell::shoulder, menu = shell::menu, cancelOperation = coordinator::cancelScan, navigateLibrary = coordinator::navigateSpatial)
        inputChanged()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        lifecycleScope.launch {
            combine(coordinator.state, engine.status, scraper.state) { state, status, _ -> state to status }.collect { (state, status) ->
                shell.sync(state, status)
                input.pauseShortcut = if (state.alternateShortcut)
                    setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
                else setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
            }
        }
        setContent { Console() }
    }

    // ---- ShellHost --------------------------------------------------------------------------------------

    override fun inputChanged() {
        if (!::input.isInitialized) return
        input.mode = shell.inputMode()
        val controls = shell.controls
        input.swapMenuButtons = controls.swapMenuButtons; input.swapGameButtons = controls.swapGameButtons
        input.swapShoulders = controls.swapShoulders
        if (input.deadZone != controls.deadZone) input.deadZone = controls.deadZone
    }
    private val libraryIdle get() = coordinator.state.value.let { !it.busy && it.screen == CollectionScreen.Library }
    override fun pickFolder() { if (libraryIdle) folderPicker.launch(null) }
    override fun pickGame() { if (libraryIdle) gamePicker.launch(arrayOf("*/*")) }
    override fun pickLabel() { if (libraryIdle) labelPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }
    override fun pickBios() { biosPicker.launch(arrayOf("*/*")) }
    override fun pickBackup() { backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
    override fun pickPack() { if (!scraper.state.value.busy) packPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
    override fun createBackup() { backupWriter.launch("Cartridge-backup.zip") }
    override fun openAndroidSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        catch (failure: Exception) { coordinator.notify("Android settings could not be opened on this device") }
    }
    override val homeEnabled get() = HomeRegistration(this).enabled()
    override fun setHome(enabled: Boolean) = HomeRegistration(this).enable(enabled)
    override fun chooseDefaultHome() {
        try { HomeRegistration(this).apply { enable(true) }.requestIntent().let(::startActivity) }
        catch (failure: Exception) { coordinator.notify("Android's home app settings could not be opened on this device") }
    }
    override fun persistFolder(uri: Uri) = persistDirectoryPermission(uri)
    override fun releaseFolder(uri: Uri) = contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    override fun stageArrive(direction: Int) { arrival = Triple(arrival.first + 1, coordinator.state.value.shelf.key, direction) }

    @SuppressLint("RestrictedApi") // Public Activity hook avoids Compose/emulator duplicate input delivery.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::input.isInitialized && input.key(event)) true else super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::input.isInitialized && input.motion(event)) true else super.dispatchGenericMotionEvent(event)

    @Composable private fun Console() {
        val state by coordinator.state.collectAsStateWithLifecycle()
        val status by engine.status.collectAsStateWithLifecycle()
        val scrape by scraper.state.collectAsStateWithLifecycle()
        val emulator by engine.surface.collectAsStateWithLifecycle()
        val frozenFrame by engine.frozenFrame.collectAsStateWithLifecycle()
        var glb by remember { mutableStateOf<CollectionAssets?>(null) }
        val device = rememberDeviceStatus(shell.clock)
        val confirm = if (shell.controls.swapMenuButtons) "B" else "A"
        val back = if (shell.controls.swapMenuButtons) "A" else "B"
        val view = LocalView.current
        // The console sleeps like any other screen. Only work the player is waiting on holds it awake;
        // gameplay is kept on by the emulator's own view.
        SideEffect { view.keepScreenOn = state.scanning || scrape.busy }
        LaunchedEffect(state.loaded) { if (state.loaded) shell.scanOnOpen() }
        LaunchedEffect(state.screen, shell.sound) {
            if (shell.sound && !state.reducedMotion) when (state.screen) {
                CollectionScreen.Inserting -> { delay(450); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mediaSound.contact() }
                CollectionScreen.Ejecting -> { delay(60); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mediaSound.contact(eject = true) }
                else -> Unit
            }
        }
        LaunchedEffect(Unit) { glb = withContext(Dispatchers.IO) { CollectionAssets.read(assets) } }
        CartridgeTheme(shell.accent, state.reducedMotion) {
            Box(Modifier.fillMaxSize().background(Ink.Stage)) {
                if (state.screen in listOf(CollectionScreen.Library, CollectionScreen.Inserting, CollectionScreen.Ejecting, CollectionScreen.LabelPreview)) {
                    val empty = state.loaded && state.allGames.isEmpty()
                    // The focused object steps aside for anything said about it: its details, its sheets, its label preview.
                    val presented = state.screen == CollectionScreen.LabelPreview ||
                        state.screen == CollectionScreen.Library && (empty || shell.top in setOf(Destination.Details, Destination.Manage, Destination.Options))
                    glb?.let { bytes -> key(state.viewportGeneration) { LibraryViewport(bytes, state, shell.base != null || scrape.visible, presented) } }
                    StageShade()
                    val browsing = state.screen == CollectionScreen.Library && shell.top == null && !scrape.visible && status.state == SessionState.Idle
                    AnimatedVisibility(browsing, enter = fadeIn(LocalMotion.current.fade()), exit = fadeOut(LocalMotion.current.exit())) {
                        Box(Modifier.fillMaxSize()) { LibraryChrome(state, shell, device, confirm, back) }
                    }
                } else {
                    if (state.screen == CollectionScreen.Emulator) emulator?.let { surface ->
                        key(surface) {
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { EmulatorHost(surface).also { emulatorHost = it } }, onRelease = { it.release() })
                        }
                    }
                    // No chrome rests on a running game. The top corner and Android's Back both open the pause menu for touch.
                    if (status.state == SessionState.Running) Box(Modifier.align(Alignment.TopEnd).size(72.dp).tap("pause") { coordinator.pause() })
                    if (status.state == SessionState.Paused) frozenFrame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    if (status.state == SessionState.Preparing || state.screen == CollectionScreen.Handoff) Starting(state.selectedGame?.title)
                }
                ShellOverlays(shell, state, status, device, confirm, back)
                if (scrape.visible) ScrapePanel(scrape, scraper, confirm, back)
                Notices(state.message.takeIf { state.launchFailure == null && shell.import == null },
                    listOfNotNull(status.saveWarning, state.historyWarning).joinToString("\n").ifEmpty { null },
                    beside = when (shell.top) {
                        Destination.SystemMenu -> 300.dp
                        Destination.Details, Destination.Manage -> 400.dp
                        Destination.Options -> if (status.state == SessionState.Paused) 340.dp else 400.dp
                        else -> 0.dp
                    })
            }
        }
    }

    /** One decoded or typeset label per object of the bounded window; never more. */
    private class LabelSpec(val key: String, val label: LabelRef?, val title: String, val system: GameSystem, val owner: String)

    @Composable private fun LibraryViewport(bytes: CollectionAssets, state: CollectionState, suspended: Boolean, presented: Boolean) {
        var surface by remember { mutableStateOf<CartridgeSurface?>(null) }
        // Decoded labels by image key, never by position: a moved window finds every label it already had.
        var decoded by remember { mutableStateOf<Map<String, LabelPixels>>(emptyMap()) }
        val cache = remember { linkedMapOf<String, LabelPixels>() }
        val systems = state.systems
        val labels = state.labels
        val windowStart = state.windowStart
        val specs = state.window.mapIndexed { index, game ->
            val label = labels[index]; val system = systems[index]
            // A game without artwork carries its own title in type. The empty library shows the product's name.
            val title = game?.title ?: "Cartridge"
            LabelSpec(label?.textureKey(system) ?: TypesetLabel.key(title, system), label, title, system, game?.id.orEmpty())
        }
        val keys = specs.map { it.key }
        LaunchedEffect(keys) {
            val focus = state.selection.index - windowStart
            // Only labels new to the window wait for IO, nearest the focus first. A finished decode is kept
            // even if the window has moved on meanwhile, so fast browsing never repeats the work.
            for (spec in specs.withIndex().sortedBy { kotlin.math.abs(it.index - focus) }.map { it.value }) {
                if (spec.key in cache) continue
                ensureActive()
                val pixels = withContext(Dispatchers.IO + NonCancellable) {
                    runCatching { spec.label?.let { labelStore.decode(it, spec.system) } ?: TypesetLabel.render(spec.title, spec.system) }
                }.getOrElse { failure ->
                    coordinator.notify(failure.message ?: "Cannot display the label")
                    // The object still needs a face: its title in type, under the key it was asked for.
                    withContext(Dispatchers.IO + NonCancellable) { runCatching { TypesetLabel.render(spec.title, spec.system).copy(key = spec.key) }.getOrNull() }
                }
                if (pixels == null) continue
                cache[spec.key] = pixels
                decoded = cache.toMap()
            }
            val needed = keys.toSet()
            while (cache.size > state.layout.windowCount * 2) cache.keys.firstOrNull { it !in needed }?.let { cache.remove(it) } ?: break
            decoded = cache.toMap()
        }
        val motion = remember { Animatable(if (state.screen == CollectionScreen.Ejecting) 1f else 0f) }
        LaunchedEffect(state.screen, state.reducedMotion) {
            val target = if (state.screen == CollectionScreen.Inserting && !state.reducedMotion) 1f else 0f
            if (state.reducedMotion) motion.snapTo(target)
            else motion.animateTo(target, tween(if (state.screen == CollectionScreen.Ejecting) MediaMotion.EJECT_MS else MediaMotion.INSERT_MS, easing = LinearEasing))
        }
        var applied by remember { mutableIntStateOf(arrival.first) }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            CartridgeSurface(it, bytes, state.selection.index,
                onReady = { coordinator.rendererReady(state.viewportGeneration) }, onReleased = {},
                onLabelsReady = { first, ready -> coordinator.labelsReady(state.viewportGeneration, ready, first) }, onLabelError = coordinator::notify
            ).also { renderer -> surface = renderer; cartridgeSurface = renderer }
        }, update = {
            it.layout = state.layout; it.selection = state.selection.index
            // A search with no result shows no object; an empty library still shows the one that welcomes you.
            it.collectionCount = if (state.selectedGame == null && state.allGames.isNotEmpty()) 0 else state.selection.count
            it.insertion = motion.value
            it.reducedMotion = state.reducedMotion
            it.presentation = if (presented) 1f else 0f
            if (arrival.first != applied && arrival.second == state.shelf.key) { applied = arrival.first; it.arriveFrom(arrival.third) }
            it.setWindow(windowStart, specs.map { spec -> CartridgeSurface.Staged(spec.system, spec.owner, spec.key, decoded[spec.key]) })
        }, onRelease = { it.close() })
        DisposableEffect(surface, suspended) {
            val renderer = surface
            if (suspended) renderer?.pauseRendering() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) renderer?.resumeRendering()
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) { if (!suspended) renderer?.resumeRendering() }
                override fun onPause(owner: LifecycleOwner) { renderer?.pauseRendering() }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); renderer?.pauseRendering() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::coordinator.isInitialized) coordinator.foreground()
        Choreographer.getInstance().removeFrameCallback(inputFrames)
        Choreographer.getInstance().postFrameCallback(inputFrames)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::shell.isInitialized) outState.putString("recoveryId", shell.pendingLocate)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(inputFrames)
        if (::input.isInitialized) input.reset()
        if (::coordinator.isInitialized) coordinator.background()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::input.isInitialized) { input.reset(); coordinator.pause() }
    }
    override fun onDestroy() {
        shell.close()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        coordinator.destroy()
        scraper.destroy()
        engine.releaseOnDestroy()
        emulatorHost?.release()
        cartridgeSurface?.close()
        if (::mediaSound.isInitialized) mediaSound.close()
        super.onDestroy()
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) { input.reset(); coordinator.pause() }
}

@SuppressLint("ViewConstructor")
private class EmulatorHost(view: GLRetroView) : FrameLayout(view.context) {
    private var released = false
    init { addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    fun release() {
        if (released) return
        released = true
        removeAllViews() // GLSurfaceView waits for its renderer thread before the next Filament owner.
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); release() }
}
