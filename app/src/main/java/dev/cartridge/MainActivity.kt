package dev.cartridge

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
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
import dev.cartridge.apps.AppSource
import dev.cartridge.apps.InstalledApps
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.Media
import dev.cartridge.domain.MediaMotion
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.environment.HomeRegistration
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.GameButtons
import dev.cartridge.input.MediaSound
import dev.cartridge.input.QuickCombo
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
    internal lateinit var autoScraper: dev.cartridge.scraping.AutoScraper
    internal lateinit var artworkSettings: dev.cartridge.scraping.ArtworkSettings
        private set
    internal lateinit var shell: ConsoleShell
        private set
    private lateinit var input: ControllerRouter
    private lateinit var labelStore: LabelStore
    private lateinit var gameMedia: dev.cartridge.ui.design.GameMedia
    private lateinit var mediaSound: MediaSound
    private var cartridgeSurface: CartridgeSurface? = null
    /** Labels decode a few at a time: enough to fill a grid row promptly, bounded so large images cannot pile up in memory. */
    private val labelDecoding = Dispatchers.IO.limitedParallelism(3)
    private var emulatorHost: EmulatorHost? = null
    /** A shelf change and the direction it came from, applied by the stage once that shelf is what it draws. */
    private var arrival by mutableStateOf(Triple(0, "", 0))
    internal val collectionSurface get() = cartridgeSurface
    protected open fun createRepository(): CollectionRepository = RoomCollectionRepository(this)
    protected open fun createLabelStore(): LabelStore = LabelStore(File(filesDir, "labels"))
    protected open fun createArtworkSettings() = dev.cartridge.scraping.ArtworkSettings(this)
    protected open fun createArtworkProvider(): ArtworkProvider = dev.cartridge.scraping.ChosenProvider(artworkSettings, File(cacheDir, "scrape-candidates"))
    protected open fun createPendingScrapes(): dev.cartridge.scraping.PendingScrapes = dev.cartridge.scraping.SharedPendingScrapes(this)
    /** Added games and bulk scopes are scraped without a review (ADR 0029). Device tests of the reviewed queue turn it off. */
    protected open fun automaticScraping() = true
    protected open fun createScrapeJournal(): ScrapeJournal = FileScrapeJournal(File(filesDir, "scrape-job"))
    protected open fun createScrapePreferences(): ScrapePreferenceStore = SharedScrapePreferences(this)
    protected open fun createPackStore(): ScrapePackStore = ScrapePackStore(File(filesDir, "scrape-packs"))
    protected open fun persistDirectoryPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    protected open fun presentationPreferences() = getPreferences(MODE_PRIVATE)
    protected open fun createSessionStore() = SessionStore(File(filesDir, "session-checkpoint"))
    protected open fun systemDirectory() = File(filesDir, "system")
    /** The shader preset a game starts with: its own choice, its console's, or every console's (ADR 0027). */
    protected open fun sessionShader(game: dev.cartridge.emulation.GameRequest): File? = shell.sessionShader(game.system, game.id)
    /** Where the shader library is installed and updated. */
    protected open fun shaderBase() = File(filesDir, "shaders")
    protected open fun createAppSource(): AppSource = InstalledApps(this)
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
    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(shell::firmwarePicked) }
    private val backupWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(shell::backupCreated) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From Android 10 the interface is taken down before onDestroy is reached: the lifecycle is told of the end
        // first, Compose then disposes of its views, and the emulator's view takes with it the thread its core ran
        // on. A game still loaded is let go while that thread is there. These callbacks are told in the reverse of
        // the order they were added in, and the lifecycle's own were added by the line above.
        if (android.os.Build.VERSION.SDK_INT >= 29) registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPreDestroyed(activity: android.app.Activity) = endSession()
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val sessionStore = createSessionStore()
        val preferences = presentationPreferences()
        engine = LibretroEngine(this, lifecycleScope, sessionOptions = { game -> shell.sessionOptions(game.system, game.id) }, sessionStore = sessionStore,
            systemDirectory = systemDirectory(), sessionShader = ::sessionShader, shaderLibrary = File(shaderBase(), "library"))
        labelStore = createLabelStore()
        val videoStore = VideoStore(File(filesDir, "videos"))
        gameMedia = dev.cartridge.ui.design.GameMedia(labelStore, videoStore)
        mediaSound = MediaSound(this)
        val repository = createRepository()
        val packStore = createPackStore()
        coordinator = CollectionCoordinator(engine, repository, object : RendererHandoff {
            override fun releaseLibrary() { cartridgeSurface?.close(); cartridgeSurface = null }
            override fun releaseEmulator() { emulatorHost?.release(); emulatorHost = null }
            // A core's first frames are often a blank display: the GBA shows white until a game turns its screen
            // on. The curtain waits for a picture, but not for long, because some games do open on an empty screen.
            override suspend fun awaitPicture() {
                val began = SystemClock.uptimeMillis()
                val seen = withTimeoutOrNull(PICTURE_WAIT_MS) { while (!pictureShown()) delay(50) } != null
                android.util.Log.i("CartridgeLaunch", "Game picture ${if (seen) "seen" else "not seen"} after ${SystemClock.uptimeMillis() - began} ms")
            }
        }, lifecycleScope, preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false),
            layout = runCatching { CollectionLayout.valueOf(preferences.getString("layout", "Horizontal")!!) }.getOrDefault(CollectionLayout.Horizontal),
            onLayoutChanged = { layout -> preferences.edit { putString("layout", layout.name) }; if (::input.isInitialized) input.reset() }, sessionStore = sessionStore,
            initialShelf = Shelf.parse(preferences.getString("shelf", null)),
            initialShelfFocus = runCatching { JSONObject(preferences.getString("shelfFocus", "{}")!!).let { json -> json.keys().asSequence().associateWith(json::getString) } }.getOrDefault(emptyMap()),
            onBrowseChanged = { shelf, focus -> preferences.edit { putString("shelf", shelf.key); putString("shelfFocus", JSONObject(focus).toString()) } })
        artworkSettings = createArtworkSettings()
        val provider = PackAwareProvider(createArtworkProvider(), packStore)
        val scrapePreferences = createScrapePreferences()
        autoScraper = dev.cartridge.scraping.AutoScraper(provider, repository, File(filesDir, "roms"), lifecycleScope, createPendingScrapes(), scrapePreferences,
            importImage = { source, bytes -> labelStore.importStream(source) { bytes.inputStream() } }, videos = videoStore,
            extras = { if (::shell.isInitialized) ExtraMedia(shell.scrapeScreenshots, shell.scrapeVideos) else ExtraMedia() })
        val automatic = automaticScraping()
        if (automatic) coordinator.onAdded = autoScraper::added
        scraper = ScrapeCoordinator(provider, repository, labelStore, File(filesDir, "roms"), lifecycleScope, createScrapeJournal(), scrapePreferences, choosePack = ::pickPack,
            automatic = if (!automatic) null else { games, policy ->
                autoScraper.scrape(games.map { it.id }, policy)
                coordinator.notify("Fetching artwork for ${games.size} ${if (games.size == 1) "game" else "games"}. Settings › Library shows how far it is.")
            }) { game, label ->
            if (coordinator.state.value.allGames.any { it.id == game.id }) coordinator.previewScrapedLabel(game, label)
            else { coordinator.notify("This game is no longer in the collection"); scraper.previewFinished() }
        }
        shell = ConsoleShell(this, lifecycleScope, this, coordinator, engine, repository, scraper, labelStore, sessionStore, packStore, preferences, systemDirectory(),
            createAppSource(), shaderBase = shaderBase(), artwork = artworkSettings, autoScraper = autoScraper)
        shell.pendingLocate = savedInstanceState?.getString("recoveryId")
        shell.reopenApps = savedInstanceState?.getBoolean("appsOpen") == true
        input = ControllerRouter(navigate = shell::navigate, select = shell::select, back = shell::back, pause = coordinator::pause,
            gameKey = engine::key, gameMotion = engine::motion, adjust = shell::adjust, alternate = shell::alternate, secondary = shell::secondary,
            shoulder = shell::shoulder, menu = shell::menu, cancelOperation = coordinator::cancelScan, navigateLibrary = coordinator::navigateSpatial,
            inspect = ::inspect, systemBack = shell::systemBack, gameAnalog = engine::analog, trigger = shell::trigger, thumb = shell::stick,
            quick = { action ->
                when (action) {
                    QuickCombo.Save -> coordinator.quickSave()
                    QuickCombo.Load -> coordinator.quickLoad()
                    QuickCombo.Freeze -> coordinator.toggleFreeze()
                    QuickCombo.FastForward -> coordinator.toggleFastForward(shell.controls.fastForwardSpeed)
                }
            }, record = shell::recordKey)
        inputChanged()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        lifecycleScope.launch {
            combine(coordinator.state, engine.status, scraper.state, autoScraper.status) { state, status, _, _ -> state to status }.collect { (state, status) ->
                shell.sync(state, status)
                input.pauseShortcut = if (state.alternateShortcut)
                    setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
                else setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
            }
        }
        lifecycleScope.launch {
            // One line when an automatic run ends, so the user knows without opening Settings.
            var running = false
            autoScraper.status.collect { run ->
                if (running && !run.running) coordinator.notify(run.problem ?: if (run.done > 0) "Artwork: ${run.summary}" else "Artwork is up to date")
                running = run.running
            }
        }
        setContent { Console() }
    }

    // ---- ShellHost --------------------------------------------------------------------------------------

    override fun inputChanged() {
        if (!::input.isInitialized) return
        input.mode = shell.inputMode()
        val controls = shell.controls
        val system = coordinator.state.value.selectedGame?.system
        input.swapMenuButtons = controls.swapMenuButtons
        input.gameButtons = system?.let { GameButtons.routes(controls.buttons(it)) } ?: GameButtons.IDENTITY
        input.combos = QuickCombo.entries.associateWith(controls::combo)
        input.analogGameplay = system?.core?.analog == true
        if (input.deadZone != controls.deadZone) input.deadZone = controls.deadZone
    }
    private val libraryIdle get() = coordinator.state.value.let { !it.busy && it.screen == CollectionScreen.Library }
    override fun pickFolder() { if (libraryIdle) folderPicker.launch(null) }
    override fun pickGame() { if (libraryIdle) gamePicker.launch(arrayOf("*/*")) }
    override fun pickLabel() { if (libraryIdle) labelPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }
    override fun pickFirmware() { firmwarePicker.launch(arrayOf("*/*")) }
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

    /** The right stick turns the focused object wherever that object is the subject: on its shelf and beside its details. */
    private fun inspect(x: Float, y: Float) {
        val subject = ::shell.isInitialized && (shell.top == null || shell.top == Destination.Details) && !scraper.state.value.visible
        cartridgeSurface?.spin(if (subject) x else 0f, if (subject) y else 0f)
    }
    /** True once the middle of the emulator's presented frame is something other than a blank white or black display. */
    private suspend fun pictureShown(): Boolean {
        val view = engine.surface.value ?: return false
        if (view.width < 8 || view.height < 8 || !view.isAttachedToWindow) return false
        val sample = androidx.core.graphics.createBitmap(32, 18)
        try {
            val copied = CompletableDeferred<Int>()
            // The middle only: bars beside a 3:2 or 4:3 picture would make a blank display look like content.
            PixelCopy.request(view, Rect(view.width / 4, view.height / 5, view.width * 3 / 4, view.height * 4 / 5), sample, { copied.complete(it) }, Handler(Looper.getMainLooper()))
            if (copied.await() != PixelCopy.SUCCESS) return false
            return !dev.cartridge.domain.blankDisplay(IntArray(32 * 18).also { sample.getPixels(it, 0, 32, 0, 0, 32, 18) })
        } finally { sample.recycle() }
    }

    @SuppressLint("RestrictedApi") // Public Activity hook avoids Compose/emulator duplicate input delivery.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pad = if (::input.isInitialized && input.mode != dev.cartridge.input.InputMode.Text) DebugKeyboard.asPad(this, event) else event
        return if (::input.isInitialized && input.key(pad)) true else super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::input.isInitialized && input.motion(event)) true else super.dispatchGenericMotionEvent(event)

    @Composable private fun Console() {
        val state by coordinator.state.collectAsStateWithLifecycle()
        val status by engine.status.collectAsStateWithLifecycle()
        val scrape by scraper.state.collectAsStateWithLifecycle()
        val emulator by engine.surface.collectAsStateWithLifecycle()
        val frozenFrame by engine.frozenFrame.collectAsStateWithLifecycle()
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
        // The shelf is drawn by Compose: a rebuilt library is ready once it has drawn a frame.
        LaunchedEffect(state.viewportGeneration, state.rendererReady, state.screen) {
            if (!state.rendererReady && state.screen in listOf(CollectionScreen.Library, CollectionScreen.Ejecting, CollectionScreen.LabelPreview)) {
                withFrameNanos { }
                coordinator.rendererReady(state.viewportGeneration)
            }
        }
        CartridgeTheme(shell.accent, state.reducedMotion) {
            Box(Modifier.fillMaxSize().background(Ink.Stage)) {
                if (state.screen in listOf(CollectionScreen.Library, CollectionScreen.Inserting, CollectionScreen.Ejecting, CollectionScreen.LabelPreview)) {
                    val browsing = state.screen == CollectionScreen.Library && shell.top == null && !scrape.visible && status.state == SessionState.Idle
                    CompositionLocalProvider(dev.cartridge.ui.design.LocalGameMedia provides gameMedia) {
                        dev.cartridge.ui.design.DesignCanvas(state.reducedMotion) {
                            dev.cartridge.ui.screens.LibraryScreen(state, shell, device, shell.controls.swapMenuButtons, browsing)
                            // Game detail rests on the shelf, and the sheets opened from it rest on Game detail.
                            val detail = state.screen == CollectionScreen.Library && Destination.Details in shell.stack && !scrape.visible
                            AnimatedVisibility(detail, enter = fadeIn(tween(if (state.reducedMotion) 0 else 220)), exit = fadeOut(tween(if (state.reducedMotion) 0 else 180))) {
                                dev.cartridge.ui.screens.GameDetailScreen(state, shell, device, shell.controls.swapMenuButtons, active = shell.top == Destination.Details)
                            }
                        }
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
                    if (state.screen == CollectionScreen.Emulator) GameBadge(status, Modifier.align(Alignment.TopEnd), state.discBadge)
                }
                ShellOverlays(shell, state, status, device, confirm, back)
                if (scrape.visible) ScrapePanel(scrape, scraper, confirm, back)
                // Over everything the stage and its menus draw; only notices sit above it.
                Curtain(state.curtain, shell.transition)
                Notices(state.message.takeIf { state.launchFailure == null && shell.import == null },
                    listOfNotNull(status.saveWarning, state.historyWarning).joinToString("\n").ifEmpty { null },
                    beside = when (shell.top) {
                        Destination.SystemMenu -> 300.dp
                        Destination.Manage, Destination.GameMore -> 400.dp
                        Destination.Options -> if (status.state == SessionState.Paused) 340.dp else 400.dp
                        else -> 0.dp
                    },
                    // Over a running game a quick save or load is confirmed in a glance.
                    lingerMs = if (status.state == SessionState.Running) 1500 else 4200)
            }
        }
    }

    /** One decoded or typeset label per object of the bounded window; never more. */
    private class LabelSpec(val key: String, val label: LabelRef?, val title: String, val system: GameSystem, val media: Media, val owner: String)

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
            // The object follows the console and the market its file is named for, never a scraped title (ADR 0029).
            val media = game?.shownAs ?: system.media(title)
            // A case shows only case art; a label scraped for a cartridge stays stored and the case shows its title.
            val art = label?.takeIf { media.cover == null || it.isCaseArt }
            LabelSpec(art?.textureKey(media) ?: TypesetLabel.key(title, system, media), art, title, system, media, game?.id.orEmpty())
        }
        val keys = specs.map { it.key }
        LaunchedEffect(keys) {
            val focus = state.selection.index - windowStart
            val needed = keys.toSet()
            // Results return from the decoding threads. On the device they are delivered on the main thread, but
            // the cache must not depend on that: a test dispatcher may hand them over on the threads they came from.
            fun publish(arrived: LabelPixels? = null) {
                decoded = synchronized(cache) {
                    if (arrived != null) cache[arrived.key] = arrived
                    while (cache.size > state.layout.windowCount * 2) cache.keys.firstOrNull { it !in needed }?.let { cache.remove(it) } ?: break
                    cache.toMap()
                }
            }
            val known = synchronized(cache) { cache.keys.toSet() }
            // Only labels new to the window wait for IO: nearest the focus first, a few at a time. One still queued
            // when the window moves on is dropped; one already decoding is finished and used by whoever asks next.
            specs.withIndex().sortedBy { kotlin.math.abs(it.index - focus) }.map { it.value }.filter { it.key !in known }.distinctBy { it.key }.forEach { spec ->
                launch {
                    val asked = coroutineContext.job
                    val pixels = withContext(labelDecoding + NonCancellable) {
                        if (!asked.isActive) null else runCatching { spec.label?.let { labelStore.decode(it, spec.media) } ?: TypesetLabel.render(spec.title, spec.system, spec.media) }
                    }?.getOrElse { failure ->
                        coordinator.notify(failure.message ?: "Cannot display the label")
                        // The object still needs a face: its title in type, under the key it was asked for.
                        withContext(labelDecoding + NonCancellable) { runCatching { TypesetLabel.render(spec.title, spec.system, spec.media).copy(key = spec.key) }.getOrNull() }
                    } ?: return@launch
                    publish(pixels)
                }
            }
            publish()
        }
        val motion = remember { Animatable(if (state.screen == CollectionScreen.Ejecting) 1f else 0f) }
        LaunchedEffect(state.screen, state.reducedMotion, state.curtain) {
            // After a game the stage is rebuilt behind the closed screen; the object comes back up once it opens.
            if (state.curtain && state.screen != CollectionScreen.Inserting) return@LaunchedEffect
            val target = if (state.screen == CollectionScreen.Inserting && !state.reducedMotion) 1f else 0f
            if (state.reducedMotion) motion.snapTo(target)
            else motion.animateTo(target, tween(if (target == 0f) MediaMotion.EJECT_MS else MediaMotion.INSERT_MS, easing = LinearEasing))
        }
        var applied by remember { mutableIntStateOf(arrival.first) }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            CartridgeSurface(it, bytes, state.selection.index,
                onReady = { coordinator.rendererReady(state.viewportGeneration) }, onReleased = {},
                onLabelsReady = { first, ready -> coordinator.labelsReady(state.viewportGeneration, ready, first) }, onLabelError = coordinator::notify
            ).also { renderer -> surface = renderer; cartridgeSurface = renderer; renderer.onFrameRate = { shell.frameRate = it } }
        }, update = {
            it.layout = state.layout; it.selection = state.selection.index
            // A search with no result shows no object; an empty library still shows the one that welcomes you.
            it.collectionCount = if (state.selectedGame == null && state.allGames.isNotEmpty()) 0 else state.selection.count
            it.insertion = motion.value
            it.reducedMotion = state.reducedMotion
            it.presentation = if (presented) 1f else 0f
            if (arrival.first != applied && arrival.second == state.shelf.key) { applied = arrival.first; it.arriveFrom(arrival.third) }
            it.setWindow(windowStart, specs.map { spec -> CartridgeSurface.Staged(spec.media, spec.owner, spec.key, decoded[spec.key]) })
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
        if (::shell.isInitialized) shell.resumed()
        Choreographer.getInstance().removeFrameCallback(inputFrames)
        Choreographer.getInstance().postFrameCallback(inputFrames)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::shell.isInitialized) { outState.putString("recoveryId", shell.pendingLocate); outState.putBoolean("appsOpen", shell.appsOpen) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(inputFrames)
        if (::input.isInitialized) input.reset()
        if (::shell.isInitialized) shell.paused()
        if (::coordinator.isInitialized) coordinator.background()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::input.isInitialized) { input.reset(); coordinator.pause() }
    }
    private var ended = false
    /** The end of everything that works on the session, and of the session, while the emulator's view still has its thread. */
    private fun endSession() {
        if (ended) return
        ended = true
        shell.close()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        coordinator.destroy()
        scraper.destroy()
        engine.releaseOnDestroy()
    }
    override fun onDestroy() {
        endSession()
        emulatorHost?.release()
        cartridgeSurface?.close()
        if (::mediaSound.isInitialized) mediaSound.close()
        super.onDestroy()
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) { input.reset(); coordinator.pause() }
}

/** The longest the screen stays closed over a running game that has not drawn anything yet. */
private const val PICTURE_WAIT_MS = 2_000L

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

/**
 * Debug builds only: a PC keyboard (as in the Android emulator) stands in for the controller buttons it lacks.
 * Arrows are the D-pad and Enter is A already; Backspace B, X and Y themselves, Q/E L1/R1, Z/C L2/R2, F/R L3/R3,
 * M Start, N Select. A release build, and every real gamepad, is untouched.
 */
private object DebugKeyboard {
    private val keys = mapOf(
        KeyEvent.KEYCODE_DEL to KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X to KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Y to KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_Q to KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_E to KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_Z to KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_C to KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_F to KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_R to KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_M to KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_N to KeyEvent.KEYCODE_BUTTON_SELECT)
    fun asPad(context: android.content.Context, event: KeyEvent): KeyEvent {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return event
        if (event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) || event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return event
        val mapped = keys[event.keyCode] ?: return event
        return KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount, 0, event.deviceId, event.scanCode, event.flags, android.view.InputDevice.SOURCE_GAMEPAD)
    }
}
