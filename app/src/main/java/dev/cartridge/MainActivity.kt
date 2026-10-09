package dev.cartridge

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.environment.*
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.InputMode
import dev.cartridge.input.MediaSound
import dev.cartridge.library.*
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.rendering.CollectionAssets
import dev.cartridge.domain.MediaMotion
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.session.*
import dev.cartridge.scraping.*
import dev.cartridge.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File

/** Android views/lifecycle only; collection and session decisions live in CollectionCoordinator. */
open class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    internal lateinit var coordinator: CollectionCoordinator
        private set
    internal lateinit var engine: LibretroEngine
        private set
    private lateinit var input: ControllerRouter
    private lateinit var labelStore: LabelStore
    internal lateinit var scraper: ScrapeCoordinator
        private set
    private var cartridgeSurface: CartridgeSurface? = null
    private var emulatorHost: EmulatorHost? = null
    private var chooseGame: (() -> Unit)? = null
    private var chooseLabel: (() -> Unit)? = null
    private var chooseDirectory: (() -> Unit)? = null
    private var choosePack: (() -> Unit)? = null
    private lateinit var packStore: ScrapePackStore
    private var addingGame = false
    private lateinit var mediaSound: MediaSound
    private var soundEnabled by mutableStateOf(false)
    private enum class LibraryOverlay { None, Manager, Search, Edit }
    private var libraryOverlay by mutableStateOf(LibraryOverlay.None)
    private var managerFocus by mutableIntStateOf(0)
    private var roots by mutableStateOf<List<DirectoryRecord>>(emptyList())
    private var autoScan by mutableStateOf(true)
    private var recoveryId: String? = null
    private var consolePage by mutableStateOf<ConsolePage?>(null)
    private var consoleFocus by mutableIntStateOf(0)
    private var consoleSettings by mutableStateOf(ConsoleSettings())
    private var homeEnabled by mutableStateOf(false)
    private var noticeText by mutableStateOf("")
    private var noticeIndex = 0
    private lateinit var repository: CollectionRepository
    private lateinit var sessionStore: SessionStore
    private var chooseBackup: (() -> Unit)? = null
    private var exportBackup: (() -> Unit)? = null
    private var backupReview by mutableStateOf<BackupReview?>(null)
    private var restorePreferences by mutableStateOf(false)
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
    protected open fun showFirstRunSetup() = true
    protected open fun createSessionStore() = SessionStore(File(filesDir, "session-checkpoint"))
    private val menuItems = listOf("Resume", "Save state", "Load state", "Eject to collection", "Game settings", "Controller")
    private val inputFrames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                input.tick(SystemClock.uptimeMillis())
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addingGame = savedInstanceState?.getBoolean("addingGame") ?: false
        recoveryId = savedInstanceState?.getString("recoveryId")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sessionStore = createSessionStore()
        engine = LibretroEngine(this, lifecycleScope, coreOptions = { consoleSettings.coreOptions() }, sessionStore = sessionStore)
        labelStore = createLabelStore()
        val preferences = presentationPreferences()
        consoleSettings = ConsolePreferences(preferences).read()
        homeEnabled = HomeRegistration(this).enabled()
        soundEnabled = preferences.getBoolean("mediaSound", false)
        autoScan = preferences.getBoolean("autoScan", true)
        mediaSound = MediaSound(this)
        repository = createRepository()
        packStore = createPackStore()
        coordinator = CollectionCoordinator(engine, repository, object : RendererHandoff {
            override fun releaseLibrary() { cartridgeSurface?.close(); cartridgeSurface = null }
            override fun releaseEmulator() { emulatorHost?.release(); emulatorHost = null }
        }, lifecycleScope, preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false),
            layout = runCatching { CollectionLayout.valueOf(preferences.getString("layout", "Horizontal")!!) }.getOrDefault(CollectionLayout.Horizontal),
            onLayoutChanged = { layout -> preferences.edit { putString("layout", layout.name) }; if (::input.isInitialized) input.reset() }, sessionStore = sessionStore)
        scraper = ScrapeCoordinator(PackAwareProvider(createArtworkProvider(), packStore), repository, labelStore, File(filesDir, "roms"), lifecycleScope, createScrapeJournal(), createScrapePreferences(), choosePack = { choosePack?.invoke() }) { game, label ->
            if (coordinator.state.value.allGames.any { it.id == game.id }) coordinator.previewScrapedLabel(game, label)
            else { coordinator.notify("This game is no longer in the collection"); scraper.previewFinished() }
        }
        input = ControllerRouter(
            navigate = { if (consolePage != null) consoleFocus = (consoleFocus + it).coerceIn(0, consoleChoices().lastIndex)
                else if (libraryOverlay == LibraryOverlay.Manager) managerFocus = (managerFocus + it).coerceIn(0, 6 + roots.size)
                else if (scraper.state.value.visible) scraper.navigate(it) else coordinator.navigate(it) },
            select = { if (consolePage != null) consoleAction(consoleFocus)
                else if (libraryOverlay == LibraryOverlay.Manager) managerAction(managerFocus)
                else if (scraper.state.value.visible) scraper.select()
                else if (coordinator.state.value.screen == CollectionScreen.Details) detailsAction(coordinator.state.value.menuIndex)
                else if (coordinator.state.value.screen == CollectionScreen.LibraryMenu && coordinator.state.value.menuIndex == 6) openLibraryManager()
                else if (coordinator.state.value.screen == CollectionScreen.LibraryMenu && coordinator.state.value.menuIndex == 8) openConsole(ConsolePage.Settings)
                else if (engine.status.value.state == SessionState.Paused && coordinator.state.value.menuIndex in 4..5) openConsole(if (coordinator.state.value.menuIndex == 4) ConsolePage.Game else ConsolePage.Controllers)
                else coordinator.select() },
            back = { if (consolePage != null) consoleBack()
                else if (libraryOverlay != LibraryOverlay.None) showOverlay(if (libraryOverlay == LibraryOverlay.Search) LibraryOverlay.Manager else LibraryOverlay.None)
                else if (scraper.state.value.visible) scraper.back() else coordinator.back() }, pause = coordinator::pause,
            gameKey = engine::key, gameMotion = engine::motion, chooseGame = { chooseGame?.invoke() },
            chooseLabel = { chooseLabel?.invoke() }, chooseScrape = ::openScraper, chooseDirectory = { chooseDirectory?.invoke() }, alternate = {
                val scrape = scraper.state.value
                if (scrape.visible && scrape.page == ScrapePage.Metadata) scrape.candidate?.metadata?.keys?.elementAtOrNull(scrape.focus)?.let(scraper::variant)
                else if (scrape.visible && scrape.page == ScrapePage.Targets) scraper.pickAll(scrape.pickedIds.size != scrape.availableGames.size)
                else if (scrape.visible && scrape.page == ScrapePage.Consoles) scraper.pickAllConsoles(scrape.pickedSystems.size != scrape.consoles.size)
            }, cancelOperation = coordinator::cancelScan, details = coordinator::details, favorite = coordinator::favorite, navigateLibrary = coordinator::navigateSpatial)
        applyControllerSettings()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        lifecycleScope.launch {
            var previousScreen = CollectionScreen.Library
            combine(coordinator.state, engine.status, scraper.state) { state, status, scrape -> Triple(state, status, scrape) }.collect { (state, status, scrape) ->
                if (previousScreen == CollectionScreen.LabelPreview && state.screen == CollectionScreen.Library)
                    scraper.previewFinished(applied = state.lastLabelAppliedTo == scrape.current?.game?.id)
                previousScreen = state.screen
                refreshInputMode()
                input.pauseShortcut = if (state.alternateShortcut)
                    setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
                else setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
            }
        }
        setContent { CartridgeApp() }
    }
    private fun refreshInputMode() {
        if (!::input.isInitialized) return
        val state = coordinator.state.value
        input.mode = when {
            consolePage != null -> if (state.busy) InputMode.Locked else InputMode.Menu
            libraryOverlay in setOf(LibraryOverlay.Edit, LibraryOverlay.Search) -> InputMode.Text
            libraryOverlay == LibraryOverlay.Manager -> InputMode.Menu
            scraper.state.value.visible -> InputMode.Menu
            state.busy || !state.loaded -> InputMode.Locked
            state.screen in setOf(CollectionScreen.LabelPreview, CollectionScreen.LibraryMenu, CollectionScreen.Details) -> InputMode.Menu
            engine.status.value.state == SessionState.Running -> InputMode.Gameplay
            engine.status.value.state == SessionState.Paused -> InputMode.Menu
            state.screen == CollectionScreen.Library -> InputMode.Library
            else -> InputMode.Locked
        }
    }
    private fun applyControllerSettings() {
        input.swapMenuButtons = consoleSettings.swapMenuButtons; input.swapGameButtons = consoleSettings.swapGameButtons
        input.swapShoulders = consoleSettings.swapShoulders; input.deadZone = consoleSettings.deadZone
    }
    private fun openConsole(page: ConsolePage) {
        if (coordinator.state.value.busy) return
        if (coordinator.state.value.screen == CollectionScreen.LibraryMenu) coordinator.back()
        consolePage = page; consoleFocus = 0; refreshInputMode()
        if (page == ConsolePage.Notices) loadNotice()
    }
    private fun consoleBack() {
        if (coordinator.state.value.busy) return
        if (consolePage == ConsolePage.Setup) presentationPreferences().edit { putBoolean("setupComplete", true) }
        if (consolePage == ConsolePage.Backup) { backupReview?.close(); backupReview = null }
        consolePage = null; input.reset(); refreshInputMode()
    }
    private fun consoleChoices(): List<String> = when(consolePage) {
        ConsolePage.Setup -> listOf("Choose game folder", "Controller", "Continue to collection")
        ConsolePage.Settings -> listOf("Back", "Controller", "Game settings", if (coordinator.state.value.reducedMotion) "Motion: Off" else "Motion: On",
            if (soundEnabled) "Media sound: On" else "Media sound: Off", if (homeEnabled) "HOME candidate: On" else "HOME candidate: Off",
            "Choose Android default home", "First-run setup", "About & licences", "Resume saved session", "Discard saved session", "Export backup", "Restore backup")
        ConsolePage.Controllers -> listOf("Back", if (consoleSettings.swapMenuButtons) "Menu select: B / back: A" else "Menu select: A / back: B",
            if (consoleSettings.swapGameButtons) "Gameplay A/B: Swapped" else "Gameplay A/B: Direct",
            if (consoleSettings.swapShoulders) "Gameplay L/R: Swapped" else "Gameplay L/R: Direct",
            "Stick dead zone: ${(consoleSettings.deadZone * 100).toInt()}%",
            if (coordinator.state.value.alternateShortcut) "Pause: Start + Select" else "Pause: L3 + R3", "Reset controller")
        ConsolePage.Game -> listOf("Back", if (consoleSettings.audioFilter) "Audio smoothing: On" else "Audio smoothing: Off", "Frames skipped: ${consoleSettings.frameSkip}")
        ConsolePage.Notices -> listOf("Back", "Next licence")
        ConsolePage.Backup -> listOf("Merge missing data", if (restorePreferences) "Restore settings: Yes" else "Restore settings: No", "Cancel")
        null -> emptyList()
    }
    private fun changeConsole(next: ConsoleSettings) {
        val commit = { consoleSettings = next; ConsolePreferences(presentationPreferences()).write(next); applyControllerSettings() }
        if (engine.status.value.state == SessionState.Paused && next.coreOptions() != consoleSettings.coreOptions()) coordinator.configureGame(next.coreOptions(), commit)
        else commit()
    }
    private fun consoleAction(index: Int) {
        if (coordinator.state.value.busy) return
        when(consolePage) {
            ConsolePage.Setup -> when(index) {
                0 -> { consoleBack(); chooseDirectory?.invoke() }
                1 -> { presentationPreferences().edit { putBoolean("setupComplete", true) }; openConsole(ConsolePage.Controllers) }
                2 -> consoleBack()
            }
            ConsolePage.Settings -> when(index) {
                0 -> consoleBack(); 1 -> openConsole(ConsolePage.Controllers); 2 -> openConsole(ConsolePage.Game)
                3 -> { val next = !coordinator.state.value.reducedMotion; coordinator.settings(reducedMotion = next); presentationPreferences().edit { putBoolean("reducedMotion", next) } }
                4 -> { soundEnabled = !soundEnabled; presentationPreferences().edit { putBoolean("mediaSound", soundEnabled) } }
                5 -> { HomeRegistration(this).enable(!homeEnabled); homeEnabled = !homeEnabled }
                6 -> try { HomeRegistration(this).apply { enable(true); homeEnabled = true }.requestIntent().let(::startActivity) }
                    catch (failure: Exception) { coordinator.notify("Android home settings unavailable: ${failure.message}") }
                7 -> openConsole(ConsolePage.Setup); 8 -> openConsole(ConsolePage.Notices)
                9 -> { consoleBack(); coordinator.resumeSavedSession() }
                10 -> { consoleBack(); coordinator.discardResume() }
                11, 12 -> if (engine.status.value.state != SessionState.Idle) coordinator.notify("Eject to the collection before backing up or restoring")
                    else if (index == 11) exportBackup?.invoke() else chooseBackup?.invoke()
            }
            ConsolePage.Controllers -> when(index) {
                0 -> consoleBack(); 1 -> changeConsole(consoleSettings.copy(swapMenuButtons = !consoleSettings.swapMenuButtons))
                2 -> changeConsole(consoleSettings.copy(swapGameButtons = !consoleSettings.swapGameButtons))
                3 -> changeConsole(consoleSettings.copy(swapShoulders = !consoleSettings.swapShoulders))
                4 -> changeConsole(consoleSettings.copy(deadZone = when { consoleSettings.deadZone < .3f -> .35f; consoleSettings.deadZone < .45f -> .5f; else -> .2f }))
                5 -> { val next = !coordinator.state.value.alternateShortcut; coordinator.settings(alternateShortcut = next); presentationPreferences().edit { putBoolean("alternateShortcut", next) } }
                6 -> { changeConsole(consoleSettings.copy(swapMenuButtons = false, swapGameButtons = false, swapShoulders = false, deadZone = .35f)); coordinator.settings(alternateShortcut = false); presentationPreferences().edit { putBoolean("alternateShortcut", false) } }
            }
            ConsolePage.Game -> when(index) { 0 -> consoleBack(); 1 -> changeConsole(consoleSettings.copy(audioFilter = !consoleSettings.audioFilter)); 2 -> changeConsole(consoleSettings.copy(frameSkip = (consoleSettings.frameSkip + 1) % 3)) }
            ConsolePage.Notices -> when(index) { 0 -> consoleBack(); 1 -> { noticeIndex++; loadNotice() } }
            ConsolePage.Backup -> when(index) { 0 -> mergeBackup(); 1 -> restorePreferences = !restorePreferences; 2 -> consoleBack() }
            null -> Unit
        }
    }
    private fun backupStore() = BackupStore(checkNotNull(repository as? BackupRepository), labelStore, SaveStore(File(filesDir, "saves")), sessionStore, File(cacheDir, "backup-staging"))
    private fun writeBackup(uri: Uri) {
        coordinator.libraryOperation {
            withContext(Dispatchers.IO) {
                val file = File.createTempFile("cartridge-backup-", ".zip", cacheDir)
                try {
                    file.outputStream().use { backupStore().export(it, BackupPreferences.capture(presentationPreferences())) }
                    check(file.length() <= 1024L * 1024 * 1024) { "Backup exceeds 1 GiB" }
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot write the selected backup" }.use { output -> file.inputStream().use { it.copyTo(output) } }
                } finally { file.delete() }
            }
            coordinator.notify("Backup exported. Keep it private: it contains your collection choices and saves.")
        }
    }
    private fun readBackup(uri: Uri) {
        coordinator.libraryOperation {
            val review = withContext(Dispatchers.IO) { checkNotNull(contentResolver.openInputStream(uri)).use { backupStore().inspect(it) } }
            backupReview?.close(); backupReview = review; restorePreferences = false
            noticeText = "${review.library.games.size} games · ${review.imageCount} images · ${review.saveCount} saves\n\nMerge keeps your existing artwork, metadata, collection membership and saves. Missing fields and saves are filled. History uses the greater value. Settings change only if you choose Yes.\n\nROMs and Android folder permissions are excluded. Choose folders again or use Locate same ROM after moving devices."
            consolePage = ConsolePage.Backup; consoleFocus = 0
        }
    }
    private fun mergeBackup() {
        val review = backupReview ?: return
        val settings = restorePreferences
        // Transfer ownership to the serialized operation so Activity cancellation cannot delete
        // reviewed files during acceptance. Its finally block releases staging.
        backupReview = null
        coordinator.libraryOperation {
            try {
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    val message = backupStore().merge(review)
                    if (settings) BackupPreferences.restore(presentationPreferences(), review.preferences)
                    message
                }
                if (settings) {
                    val preferences = presentationPreferences()
                    consoleSettings = ConsolePreferences(preferences).read(); applyControllerSettings()
                    soundEnabled = preferences.getBoolean("mediaSound", false); autoScan = preferences.getBoolean("autoScan", true)
                    lifecycleScope.launch {
                        coordinator.state.first { !it.busy }
                        coordinator.settings(preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false), CollectionLayout.valueOf(preferences.getString("layout", "Horizontal")!!))
                    }
                }
                consolePage = ConsolePage.Settings; consoleFocus = 0; coordinator.notify(result)
            } finally { withContext(NonCancellable + Dispatchers.IO) { review.close() } }
        }
    }
    private fun loadNotice() {
        lifecycleScope.launch {
            noticeText = withContext(Dispatchers.IO) {
                val names = assets.list("licenses").orEmpty().sorted()
                if (names.isEmpty()) "License files unavailable" else names[noticeIndex % names.size].let { name ->
                    "Cartridge · mGBA 0.10.5 · LibretroDroid 0.14.0 · Filament 1.72.1\nOriginal project assets; private user ROMs/artwork remain user controlled.\n\n$name\n\n" + assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            }
        }
    }
    private fun showOverlay(next: LibraryOverlay) { libraryOverlay = next; refreshInputMode() }
    private fun openLibraryManager() {
        coordinator.back()
        managerFocus = 0
        showOverlay(LibraryOverlay.Manager)
        lifecycleScope.launch { roots = (repository as? DirectoryRepository)?.directoryRecords().orEmpty() }
    }
    private fun managerAction(index: Int) {
        when (index) {
            0 -> showOverlay(LibraryOverlay.None)
            1 -> { showOverlay(LibraryOverlay.None); chooseDirectory?.invoke() }
            2, 3 -> { showOverlay(LibraryOverlay.None); scanDirectory(null, verifyAll = index == 3) }
            4 -> { autoScan = !autoScan; presentationPreferences().edit { putBoolean("autoScan", autoScan) } }
            5 -> showOverlay(LibraryOverlay.Search)
            6 -> { showOverlay(LibraryOverlay.None); coordinator.query(coordinator.state.value.query.copy(search = "")) }
            else -> roots.getOrNull(index - 7)?.let { root ->
                lifecycleScope.launch {
                    try {
                        (repository as DirectoryRepository).forgetDirectory(root.uri)
                        runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        roots = (repository as DirectoryRepository).directoryRecords()
                        managerFocus = managerFocus.coerceAtMost(6 + roots.size)
                    } catch (failure: Exception) { if (failure is CancellationException) throw failure; coordinator.notify("Cannot forget folder: ${failure.message}") }
                }
            }
        }
    }
    private fun detailsAction(index: Int) {
        if (coordinator.state.value.busy) return
        when (index) {
            0 -> coordinator.favorite()
            1 -> showOverlay(LibraryOverlay.Edit)
            2 -> { coordinator.closeDetails(); openScraper(); scraper.chooseScope(true) }
            3 -> { recoveryId = coordinator.state.value.selectedGame?.id; coordinator.closeDetails(); chooseGame?.invoke() }
            4 -> coordinator.undoEnrichment()
            5 -> coordinator.closeDetails()
        }
    }

    @SuppressLint("RestrictedApi") // Public Activity hook avoids Compose/emulator duplicate input delivery.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::input.isInitialized && input.key(event)) true else super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::input.isInitialized && input.motion(event)) true else super.dispatchGenericMotionEvent(event)

    private fun importGame(uri: Uri) {
        val expected = recoveryId
        val shouldAdd = addingGame
        recoveryId = null
        addingGame = false
        val import: suspend () -> LibraryGame = {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            withContext(Dispatchers.IO) {
                val taskContext = coroutineContext
                val game = RomImporter(contentResolver, File(filesDir, "roms")).import(uri) { taskContext.ensureActive() }
                require(expected == null || expected == game.request.id) { "Choose the same ROM content to recover this entry. Its labels and saves were kept." }
                LibraryGame(game.request.id, game.title, uri.toString(), system = game.request.system)
            }
        }
        lifecycleScope.launch {
            coordinator.state.first { it.loaded && !it.busy && it.screen == CollectionScreen.Library }
            if (shouldAdd) coordinator.addGame(import) else coordinator.assignGame(import)
        }
    }
    private fun scanDirectory(uri: Uri?, verifyAll: Boolean = false) {
        coordinator.scanDirectory { progress ->
            val directories = checkNotNull(repository as? DirectoryRepository) { "Directory import is unavailable" }
            if (uri != null) persistDirectoryPermission(uri)
            val targets = uri?.let { listOf(it.toString()) } ?: directories.directories()
            require(targets.isNotEmpty()) { "Choose a ROM folder before rescanning" }
            val scanner = DirectoryScanner(contentResolver, File(filesDir, "roms"), repository, directories)
            val results = targets.map { scanner.scan(Uri.parse(it), progress, verifyAll) }
            if (results.size == 1) results.single().message else
                "Scan complete: ${results.sumOf { it.added }} added · ${results.sumOf { it.duplicates }} duplicates · ${results.sumOf { it.failed }} failed" +
                    results.firstNotNullOfOrNull { it.errors.firstOrNull() }?.let { ". $it" }.orEmpty()
        }
    }
    private fun importLabel(uri: Uri) = coordinator.previewLabel {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        withContext(Dispatchers.IO) { labelStore.import(contentResolver, uri) }
    }
    private fun openScraper() {
        val current = coordinator.state.value
        if (current.loaded && !current.busy && current.screen == CollectionScreen.Library) scraper.open(current.allGames, current.selectedGame)
    }
    private fun importPack(uri: Uri) {
        lifecycleScope.launch {
            coordinator.state.first { it.loaded && !it.busy && it.screen == CollectionScreen.Library }
            openScraper()
            scraper.importPack { packStore.import(contentResolver, uri) }
        }
    }

    @Composable private fun CartridgeApp() {
        val state by coordinator.state.collectAsStateWithLifecycle()
        val status by engine.status.collectAsStateWithLifecycle()
        val scrape by scraper.state.collectAsStateWithLifecycle()
        val emulator by engine.surface.collectAsStateWithLifecycle()
        val frozenFrame by engine.frozenFrame.collectAsStateWithLifecycle()
        var glb by remember { mutableStateOf<CollectionAssets?>(null) }
        var toolsVisible by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.loaded) {
            if (state.loaded && showFirstRunSetup() && !presentationPreferences().getBoolean("setupComplete", false)) {
                if (state.allGames.isEmpty()) openConsole(ConsolePage.Setup)
                else presentationPreferences().edit { putBoolean("setupComplete", true) }
            }
            if (state.loaded && autoScan && (repository as? DirectoryRepository)?.directories()?.isNotEmpty() == true) scanDirectory(null)
        }
        LaunchedEffect(state.screen, soundEnabled) {
            if (soundEnabled && !state.reducedMotion) when (state.screen) {
                CollectionScreen.Inserting -> { delay(450); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mediaSound.contact() }
                CollectionScreen.Ejecting -> { delay(60); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mediaSound.contact(eject = true) }
                else -> Unit
            }
        }
        LaunchedEffect(Unit) {
            glb = withContext(Dispatchers.IO) { CollectionAssets.read(assets) }
        }
        val gamePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it != null) importGame(it) else { addingGame = false; recoveryId = null }
        }
        val labelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importLabel) }
        val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::scanDirectory) }
        val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importPack) }
        val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::readBackup) }
        val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(::writeBackup) }
        DisposableEffect(gamePicker, labelPicker) {
            chooseGame = { if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) { addingGame = true; gamePicker.launch(arrayOf("*/*")) } }
            chooseDirectory = { if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) directoryPicker.launch(null) }
            choosePack = { if (!scraper.state.value.busy) packPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
            chooseBackup = { if (!coordinator.state.value.busy) backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
            exportBackup = { if (!coordinator.state.value.busy) backupWriter.launch("Cartridge-backup.zip") }
            chooseLabel = {
                if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) {
                    if (coordinator.state.value.selectedGame == null) coordinator.notify("Choose a game before assigning its label.")
                    else labelPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                }
            }
            onDispose { chooseGame = null; chooseLabel = null; chooseDirectory = null; choosePack = null; chooseBackup = null; exportBackup = null }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD8C7A8), background = Color(0xFF191817))) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (state.screen in listOf(CollectionScreen.Library, CollectionScreen.LibraryMenu, CollectionScreen.Details, CollectionScreen.Inserting, CollectionScreen.Ejecting, CollectionScreen.LabelPreview)) {
                    glb?.let { bytes -> key(state.viewportGeneration) { LibraryViewport(bytes, state, scrape.visible) } }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.title, fontSize = 23.sp, color = Color(0xFFE9E2D6), modifier = Modifier.testTag("selected-title"))
                        if (state.selectedGame?.favorite == true || state.query != LibraryQuery()) Text(
                            listOfNotNull(if (state.selectedGame?.favorite == true) "★" else null, if (state.query.favoritesOnly) "Favorites" else null,
                                state.query.search.takeIf(String::isNotEmpty)?.let { "Search: $it" }, if (state.query.sort != LibrarySort.Added) state.query.sort.title else null).joinToString(" · "), fontSize = 12.sp)
                        Text(if (state.screen == CollectionScreen.LabelPreview) "↑ ↓ Choose   ·   A Apply action   ·   B Cancel"
                            else "${when (state.layout) { CollectionLayout.Horizontal -> "← →"; CollectionLayout.Vertical -> "↑ ↓"; CollectionLayout.Grid -> "← ↑ ↓ →" }} Browse   ·   A Insert   ·   R1 Details   ·   L1 Favorite   ·   Y Scrape   ·   B Collection   ·   ${if (state.selectedGame == null) 0 else state.selection.index + 1} / ${state.slots.count { it != null }}",
                            fontSize = 12.sp, color = Color(0xFF9C968B), modifier = Modifier.padding(top = 8.dp))
                    }
                    if (state.screen == CollectionScreen.Library) {
                        TextButton(onClick = { toolsVisible = !toolsVisible }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).testTag("toggle-tools")) {
                            Text(if (toolsVisible) "Close tools" else "Tools")
                        }
                        if (toolsVisible) Column(Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                            .background(Color(0xEE191817)).padding(8.dp)) {
                            Row {
                                TextButton(enabled = !state.busy && state.loaded, onClick = { chooseGame?.invoke() }, modifier = Modifier.testTag("choose-game")) { Text("Add game") }
                                TextButton(enabled = !state.busy && state.loaded, onClick = { chooseDirectory?.invoke() }, modifier = Modifier.testTag("choose-folder")) { Text("Folder") }
                                TextButton(enabled = !state.busy && state.loaded, onClick = ::openScraper, modifier = Modifier.testTag("scrape")) { Text("Scrape") }
                                TextButton(enabled = !state.busy && state.selectedGame != null, onClick = { chooseLabel?.invoke() }, modifier = Modifier.testTag("choose-label")) { Text("Image") }
                                if (state.selectedGame?.label != null) TextButton(enabled = !state.busy, onClick = coordinator::removeLabel) { Text("Reset label") }
                                TextButton(enabled = !state.busy && state.rendererReady && state.loaded, onClick = coordinator::insert, modifier = Modifier.testTag("insert")) { Text("Insert") }
                            }
                            Row {
                                TextButton(enabled = !state.busy, onClick = {
                                    val next = !state.reducedMotion
                                    coordinator.settings(reducedMotion = next)
                                    presentationPreferences().edit { putBoolean("reducedMotion", next) }
                                }, modifier = Modifier.testTag("motion-setting")) { Text(if (state.reducedMotion) "Motion off" else "Motion on") }
                                TextButton(enabled = !state.busy, onClick = {
                                    soundEnabled = !soundEnabled
                                    presentationPreferences().edit { putBoolean("mediaSound", soundEnabled) }
                                }) { Text(if (soundEnabled) "Sound on" else "Sound off") }
                                TextButton(enabled = !state.busy, onClick = {
                                    val next = !state.alternateShortcut
                                    coordinator.settings(alternateShortcut = next)
                                    presentationPreferences().edit { putBoolean("alternateShortcut", next) }
                                }, modifier = Modifier.testTag("shortcut-setting")) { Text(if (state.alternateShortcut) "Menu: Start + Select" else "Menu: L3 + R3") }
                                TextButton(enabled = !state.busy, onClick = { scanDirectory(null) }, modifier = Modifier.testTag("rescan")) { Text("Rescan") }
                                TextButton(enabled = !state.busy && state.loaded, onClick = coordinator::openLibraryMenu, modifier = Modifier.testTag("collection-menu")) { Text("Collection") }
                                TextButton(enabled = !state.busy && state.selectedGame != null, onClick = coordinator::details, modifier = Modifier.testTag("details")) { Text("Details") }
                                TextButton(enabled = !state.busy, onClick = { openConsole(ConsolePage.Settings) }, modifier = Modifier.testTag("console-settings")) { Text("Settings") }
                                if (state.resumeSession != null) TextButton(enabled = !state.busy, onClick = coordinator::resumeSavedSession,
                                    modifier = Modifier.testTag("resume-session")) { Text("Resume session") }
                            }
                        }
                        if (state.scanning) TextButton(onClick = coordinator::cancelScan, modifier = Modifier.align(Alignment.Center).testTag("cancel-scan")) { Text("Cancel scan") }
                        TextButton(enabled = !state.busy, onClick = { coordinator.navigate(-1) }, modifier = Modifier.align(Alignment.CenterStart).testTag("previous")) { Text("‹") }
                        TextButton(enabled = !state.busy, onClick = { coordinator.navigate(1) }, modifier = Modifier.align(Alignment.CenterEnd).testTag("next")) { Text("›") }
                    }
                    if (state.screen == CollectionScreen.LibraryMenu) {
                        val menuScroll = rememberScrollState()
                        LaunchedEffect(state.menuIndex) { menuScroll.animateScrollTo(if (state.menuIndex >= 4) menuScroll.maxValue else 0) }
                        Column(Modifier.align(Alignment.CenterEnd).width(280.dp).background(Color(0xF0191817)).verticalScroll(menuScroll).padding(12.dp)) {
                            Text("Collection", fontSize = 19.sp, modifier = Modifier.padding(8.dp))
                            Text("Removal keeps ROMs, saves and artwork. Rescan skips removed entries; Add game restores them.", fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            listOf("Back to collection", "Remove selected entry", "Undo last removal", "Sort: ${state.query.sort.title}",
                                if (state.query.favoritesOnly) "Show all games" else "Show favorites", "Reset filters & sort").forEachIndexed { index, title ->
                                TextButton(enabled = !state.busy && (index != 1 || state.selectedGame != null) && (index != 2 || state.removedEntry != null),
                                    onClick = { coordinator.libraryAction(index) }, modifier = Modifier.fillMaxWidth().testTag("collection-action-$index")
                                        .background(if (state.menuIndex == index) Color(0xFF34312D) else Color.Transparent)) { Text(title) }
                            }
                            LibraryAction("Folders & search", state.menuIndex == 6, "library-manager-open", !state.busy, ::openLibraryManager)
                            LibraryAction("View: ${state.layout.title}", state.menuIndex == 7, "layout-setting", !state.busy) { coordinator.settings(layout = state.layout.next()) }
                            LibraryAction("Console settings", state.menuIndex == 8, "console-settings-menu", !state.busy) { openConsole(ConsolePage.Settings) }
                        }
                    }
                    if (state.screen == CollectionScreen.LabelPreview) {
                        val actions = listOf("Sizing: ${state.labelDraft?.label?.fit}", "Rotate 90°", "Apply label", "Cancel")
                        Column(Modifier.align(Alignment.CenterEnd).width(210.dp).background(Color(0xF0191817)).padding(12.dp)) {
                            Text("Label preview", fontSize = 19.sp, color = Color(0xFFE9E2D6), modifier = Modifier.padding(8.dp))
                            actions.forEachIndexed { index, title ->
                                TextButton(enabled = !state.busy && (index != 2 || state.labelPreviewReady), onClick = { coordinator.labelAction(index) },
                                    modifier = Modifier.fillMaxWidth().testTag("label-action-$index").background(
                                        if (state.menuIndex == index) Color(0xFF34312D) else Color.Transparent)) { Text(title) }
                            }
                        }
                    }
                } else if (state.screen == CollectionScreen.Emulator) {
                    emulator?.let { view ->
                        key(view) {
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { EmulatorHost(view).also { emulatorHost = it } }, onRelease = { it.release() })
                        }
                    }
                    if (status.state == SessionState.Running) TextButton(onClick = coordinator::pause,
                        modifier = Modifier.align(Alignment.TopEnd).testTag("pause")) { Text("Menu") }
                    if (status.state == SessionState.Paused) {
                        frozenFrame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        Box(Modifier.fillMaxSize().background(Color(0xCE191817)), contentAlignment = Alignment.Center) {
                            Column(Modifier.width(280.dp)) {
                                Text("Paused", fontSize = 25.sp, color = Color(0xFFE9E2D6), modifier = Modifier.padding(12.dp))
                                menuItems.forEachIndexed { index, title ->
                                    TextButton(enabled = !state.busy, onClick = {
                                        if (index in 4..5) openConsole(if (index == 4) ConsolePage.Game else ConsolePage.Controllers)
                                        else coordinator.menuAction(index)
                                    }, modifier = Modifier.fillMaxWidth()
                                        .testTag("menu-$index").background(if (index == state.menuIndex) Color(0xFF34312D) else Color.Transparent)) {
                                        Text(title, color = Color(0xFFE9E2D6))
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.screen == CollectionScreen.Details) state.selectedGame?.let { game ->
                    var facts by remember(game.id) { mutableStateOf<LibraryFacts?>(null) }
                    LaunchedEffect(game.id) { facts = repository.facts(game.id) }
                    GameDetails(game, facts, state.menuIndex, state.busy, ::detailsAction)
                }
                when (libraryOverlay) {
                    LibraryOverlay.Manager -> LibraryManager(roots, managerFocus, autoScan, ::managerAction)
                    LibraryOverlay.Search -> LibrarySearch(state.query, { showOverlay(LibraryOverlay.Manager) }) {
                        showOverlay(LibraryOverlay.None); coordinator.query(state.query.copy(search = it))
                    }
                    LibraryOverlay.Edit -> state.selectedGame?.let { MetadataEditor(it, state.busy, { showOverlay(LibraryOverlay.None) }) { values ->
                        showOverlay(LibraryOverlay.None); coordinator.editMetadata(values)
                    } }
                    LibraryOverlay.None -> Unit
                }
                if (!state.loaded || state.busy && state.screen !in listOf(CollectionScreen.Inserting, CollectionScreen.Ejecting)) {
                    Text(if (status.state == SessionState.Preparing) "Preparing embedded core…" else "Working…",
                        color = Color(0xFFE9E2D6), modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
                }
                listOfNotNull(status.saveWarning ?: state.message, state.historyWarning).joinToString("\n").takeIf(String::isNotEmpty)?.let { Text(it, color = Color(0xFFE2CFAE), modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color(0xEE191817)).padding(16.dp).testTag("message"), fontSize = 13.sp) }
                if (scrape.visible) ScrapePanel(scrape, scraper)
                consolePage?.let { ConsolePanel(it, consoleChoices(), consoleFocus, state.busy, ::consoleAction, noticeText) }
            }
        }
    }

    @Composable private fun LibraryViewport(bytes: CollectionAssets, state: CollectionState, scrapeVisible: Boolean) {
        var surface by remember { mutableStateOf<CartridgeSurface?>(null) }
        var pixels by remember { mutableStateOf<List<LabelPixels?>>(List(3) { null }) }
        val cache = remember { linkedMapOf<String, LabelPixels>() }
        val labels = state.labels
        val systems = state.systems
        val keys = labels.mapIndexed { index, label -> label?.textureKey(systems[index]) }
        LaunchedEffect(keys) {
            pixels = labels.mapIndexed { index, label -> label?.let {
                cache[keys[index]] ?: try { withContext(Dispatchers.IO) { labelStore.decode(it, systems[index]) } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    coordinator.notify(failure.message ?: "Cannot display the label")
                    null
                }?.also { decoded -> cache[decoded.key] = decoded }
            } }
            val needed = keys.filterNotNull().toSet()
            while (cache.size > state.layout.visibleCount * 2) cache.keys.firstOrNull { it !in needed }?.let { cache.remove(it) } ?: break
        }
        val motion = remember { Animatable(if (state.screen == CollectionScreen.Ejecting) 1f else 0f) }
        LaunchedEffect(state.screen, state.reducedMotion) {
            val target = if (state.screen == CollectionScreen.Inserting && !state.reducedMotion) 1f else 0f
            if (state.reducedMotion) motion.snapTo(target)
            else motion.animateTo(target, tween(if (state.screen == CollectionScreen.Ejecting) MediaMotion.EJECT_MS else MediaMotion.INSERT_MS, easing = LinearEasing))
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            CartridgeSurface(it, bytes, state.selection.index,
                onReady = { coordinator.rendererReady(state.viewportGeneration) }, onReleased = {},
                onLabelsReady = { first, keys -> coordinator.labelsReady(state.viewportGeneration, keys, first) }, onLabelError = coordinator::notify
            ).also { renderer -> surface = renderer; cartridgeSurface = renderer }
        }, update = {
            it.systems = systems; it.layout = state.layout; it.selection = state.selection.index; it.windowStart = state.windowStart; it.collectionCount = state.selection.count; it.insertion = motion.value
            it.reducedMotion = state.reducedMotion
            it.setLabels(if (pixels.map { pixel -> pixel?.key } == keys) pixels else List(labels.size) { null })
        }, onRelease = { it.close() })
        val suspended = state.screen == CollectionScreen.Details || libraryOverlay != LibraryOverlay.None || scrapeVisible || consolePage != null
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
        outState.putBoolean("addingGame", addingGame)
        outState.putString("recoveryId", recoveryId)
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
        backupReview?.close(); backupReview = null
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
