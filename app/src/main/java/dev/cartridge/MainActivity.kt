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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.InputMode
import dev.cartridge.input.MediaSound
import dev.cartridge.library.*
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.rendering.CollectionAssets
import dev.cartridge.domain.MediaMotion
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
    private var addingGame = false
    private lateinit var mediaSound: MediaSound
    private var soundEnabled by mutableStateOf(false)
    private enum class LibraryOverlay { None, Manager, Search, Edit }
    private var libraryOverlay by mutableStateOf(LibraryOverlay.None)
    private var managerFocus by mutableIntStateOf(0)
    private var roots by mutableStateOf<List<DirectoryRecord>>(emptyList())
    private var autoScan by mutableStateOf(true)
    private var recoveryId: String? = null
    private lateinit var repository: CollectionRepository
    internal val collectionSurface get() = cartridgeSurface
    protected open fun createRepository(): CollectionRepository = RoomCollectionRepository(this)
    protected open fun createLabelStore(): LabelStore = LabelStore(File(filesDir, "labels"))
    protected open fun createArtworkProvider(): ArtworkProvider = RetroBaseProvider(File(cacheDir, "scrape-candidates"))
    protected open fun createScrapeJournal(): ScrapeJournal = FileScrapeJournal(File(filesDir, "scrape-job"))
    protected open fun createScrapePreferences(): ScrapePreferenceStore = SharedScrapePreferences(this)
    protected open fun persistDirectoryPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private val menuItems = listOf("Resume", "Save state", "Load state", "Eject to collection")
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
        engine = LibretroEngine(this, lifecycleScope)
        labelStore = createLabelStore()
        val preferences = getPreferences(MODE_PRIVATE)
        soundEnabled = preferences.getBoolean("mediaSound", false)
        autoScan = preferences.getBoolean("autoScan", true)
        mediaSound = MediaSound(this)
        repository = createRepository()
        coordinator = CollectionCoordinator(engine, repository, object : RendererHandoff {
            override fun releaseLibrary() { cartridgeSurface?.close(); cartridgeSurface = null }
            override fun releaseEmulator() { emulatorHost?.release(); emulatorHost = null }
        }, lifecycleScope, preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false))
        scraper = ScrapeCoordinator(createArtworkProvider(), repository, labelStore, File(filesDir, "roms"), lifecycleScope, createScrapeJournal(), createScrapePreferences()) { game, label ->
            if (coordinator.state.value.allGames.any { it.id == game.id }) coordinator.previewScrapedLabel(game, label)
            else { coordinator.notify("This game is no longer in the collection"); scraper.previewFinished() }
        }
        input = ControllerRouter(
            navigate = { if (libraryOverlay == LibraryOverlay.Manager) managerFocus = (managerFocus + it).coerceIn(0, 6 + roots.size)
                else if (scraper.state.value.visible) scraper.navigate(it) else coordinator.navigate(it) },
            select = { if (libraryOverlay == LibraryOverlay.Manager) managerAction(managerFocus)
                else if (scraper.state.value.visible) scraper.select()
                else if (coordinator.state.value.screen == CollectionScreen.Details) detailsAction(coordinator.state.value.menuIndex)
                else if (coordinator.state.value.screen == CollectionScreen.LibraryMenu && coordinator.state.value.menuIndex == 6) openLibraryManager()
                else coordinator.select() },
            back = { if (libraryOverlay != LibraryOverlay.None) showOverlay(if (libraryOverlay == LibraryOverlay.Search) LibraryOverlay.Manager else LibraryOverlay.None)
                else if (scraper.state.value.visible) scraper.back() else coordinator.back() }, pause = coordinator::pause,
            gameKey = engine::key, gameMotion = engine::motion, chooseGame = { chooseGame?.invoke() },
            chooseLabel = { chooseLabel?.invoke() }, chooseScrape = ::openScraper, chooseDirectory = { chooseDirectory?.invoke() }, alternate = {
                val scrape = scraper.state.value
                if (scrape.visible && scrape.page == ScrapePage.Metadata) scrape.candidate?.metadata?.keys?.elementAtOrNull(scrape.focus)?.let(scraper::variant)
                else if (scrape.visible && scrape.page == ScrapePage.Targets) scraper.pickAll(scrape.pickedIds.size != scrape.availableGames.size)
            }, cancelOperation = coordinator::cancelScan, details = coordinator::details, favorite = coordinator::favorite)
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
            4 -> { autoScan = !autoScan; getPreferences(MODE_PRIVATE).edit { putBoolean("autoScan", autoScan) } }
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
                LibraryGame(game.request.id, game.title, uri.toString())
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

    @Composable private fun CartridgeApp() {
        val state by coordinator.state.collectAsStateWithLifecycle()
        val status by engine.status.collectAsStateWithLifecycle()
        val scrape by scraper.state.collectAsStateWithLifecycle()
        val emulator by engine.surface.collectAsStateWithLifecycle()
        var glb by remember { mutableStateOf<CollectionAssets?>(null) }
        var toolsVisible by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.loaded) {
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
        DisposableEffect(gamePicker, labelPicker) {
            chooseGame = { if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) { addingGame = true; gamePicker.launch(arrayOf("*/*")) } }
            chooseDirectory = { if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) directoryPicker.launch(null) }
            chooseLabel = {
                if (!coordinator.state.value.busy && coordinator.state.value.screen == CollectionScreen.Library) {
                    if (coordinator.state.value.selectedGame == null) coordinator.notify("Choose a game before assigning its label.")
                    else labelPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                }
            }
            onDispose { chooseGame = null; chooseLabel = null; chooseDirectory = null }
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
                            else "← → Browse   ·   A Insert   ·   R1 Details   ·   L1 Favorite   ·   Y Scrape   ·   B Collection   ·   ${if (state.selectedGame == null) 0 else state.selection.index + 1} / ${state.slots.count { it != null }}",
                            fontSize = 12.sp, color = Color(0xFF9C968B), modifier = Modifier.padding(top = 8.dp))
                    }
                    if (state.screen == CollectionScreen.Library) {
                        TextButton(onClick = { toolsVisible = !toolsVisible }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).testTag("toggle-tools")) {
                            Text(if (toolsVisible) "Close tools" else "Tools")
                        }
                        if (toolsVisible) Column(Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                            .background(Color(0xEE191817)).padding(8.dp)) {
                            Row {
                                TextButton(enabled = !state.busy && state.loaded, onClick = { chooseGame?.invoke() }, modifier = Modifier.testTag("choose-game")) { Text("Add .gba") }
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
                                    getPreferences(MODE_PRIVATE).edit { putBoolean("reducedMotion", next) }
                                }, modifier = Modifier.testTag("motion-setting")) { Text(if (state.reducedMotion) "Motion off" else "Motion on") }
                                TextButton(enabled = !state.busy, onClick = {
                                    soundEnabled = !soundEnabled
                                    getPreferences(MODE_PRIVATE).edit { putBoolean("mediaSound", soundEnabled) }
                                }) { Text(if (soundEnabled) "Sound on" else "Sound off") }
                                TextButton(enabled = !state.busy, onClick = {
                                    val next = !state.alternateShortcut
                                    coordinator.settings(alternateShortcut = next)
                                    getPreferences(MODE_PRIVATE).edit { putBoolean("alternateShortcut", next) }
                                }, modifier = Modifier.testTag("shortcut-setting")) { Text(if (state.alternateShortcut) "Menu: Start + Select" else "Menu: L3 + R3") }
                                TextButton(enabled = !state.busy, onClick = { scanDirectory(null) }, modifier = Modifier.testTag("rescan")) { Text("Rescan") }
                                TextButton(enabled = !state.busy && state.loaded, onClick = coordinator::openLibraryMenu, modifier = Modifier.testTag("collection-menu")) { Text("Collection") }
                                TextButton(enabled = !state.busy && state.selectedGame != null, onClick = coordinator::details, modifier = Modifier.testTag("details")) { Text("Details") }
                            }
                        }
                        if (state.scanning) TextButton(onClick = coordinator::cancelScan, modifier = Modifier.align(Alignment.Center).testTag("cancel-scan")) { Text("Cancel scan") }
                        TextButton(enabled = !state.busy, onClick = { coordinator.navigate(-1) }, modifier = Modifier.align(Alignment.CenterStart).testTag("previous")) { Text("‹") }
                        TextButton(enabled = !state.busy, onClick = { coordinator.navigate(1) }, modifier = Modifier.align(Alignment.CenterEnd).testTag("next")) { Text("›") }
                    }
                    if (state.screen == CollectionScreen.LibraryMenu) {
                        Column(Modifier.align(Alignment.CenterEnd).width(280.dp).background(Color(0xF0191817)).padding(12.dp)) {
                            Text("Collection", fontSize = 19.sp, modifier = Modifier.padding(8.dp))
                            Text("Removal keeps ROMs, saves and artwork. Rescan skips removed entries; Add .gba restores them.", fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            listOf("Back to collection", "Remove selected entry", "Undo last removal", "Sort: ${state.query.sort.title}",
                                if (state.query.favoritesOnly) "Show all games" else "Show favorites", "Reset filters & sort").forEachIndexed { index, title ->
                                TextButton(enabled = !state.busy && (index != 1 || state.selectedGame != null) && (index != 2 || state.removedEntry != null),
                                    onClick = { coordinator.libraryAction(index) }, modifier = Modifier.fillMaxWidth().testTag("collection-action-$index")
                                        .background(if (state.menuIndex == index) Color(0xFF34312D) else Color.Transparent)) { Text(title) }
                            }
                            LibraryAction("Folders & search", state.menuIndex == 6, "library-manager-open", !state.busy, ::openLibraryManager)
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
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { EmulatorHost(view).also { emulatorHost = it } }, onRelease = { it.release() })
                    }
                    if (status.state == SessionState.Running) TextButton(onClick = coordinator::pause,
                        modifier = Modifier.align(Alignment.TopEnd).testTag("pause")) { Text("Menu") }
                    if (status.state == SessionState.Paused) {
                        Box(Modifier.fillMaxSize().background(Color(0xCE191817)), contentAlignment = Alignment.Center) {
                            Column(Modifier.width(280.dp)) {
                                Text("Paused", fontSize = 25.sp, color = Color(0xFFE9E2D6), modifier = Modifier.padding(12.dp))
                                menuItems.forEachIndexed { index, title ->
                                    TextButton(enabled = !state.busy, onClick = { coordinator.menuAction(index) }, modifier = Modifier.fillMaxWidth()
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
            }
        }
    }

    @Composable private fun LibraryViewport(bytes: CollectionAssets, state: CollectionState, scrapeVisible: Boolean) {
        var surface by remember { mutableStateOf<CartridgeSurface?>(null) }
        var pixels by remember { mutableStateOf<List<LabelPixels?>>(List(3) { null }) }
        val cache = remember { linkedMapOf<String, LabelPixels>() }
        val labels = state.labels
        LaunchedEffect(labels.map { it?.textureKey }) {
            pixels = labels.map { label -> label?.let {
                cache[it.textureKey] ?: try { withContext(Dispatchers.IO) { labelStore.decode(it) } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    coordinator.notify(failure.message ?: "Cannot display the label")
                    null
                }?.also { decoded -> cache[it.textureKey] = decoded }
            } }
            val needed = labels.mapNotNull { it?.textureKey }.toSet()
            while (cache.size > 6) cache.keys.firstOrNull { it !in needed }?.let { cache.remove(it) } ?: break
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
            it.selection = state.selection.index; it.windowStart = state.windowStart; it.collectionCount = state.selection.count; it.insertion = motion.value
            it.reducedMotion = state.reducedMotion
            it.setLabels(if (pixels.map { pixel -> pixel?.key } == labels.map { label -> label?.textureKey }) pixels else List(3) { null })
        }, onRelease = { it.close() })
        val suspended = state.screen == CollectionScreen.Details || libraryOverlay != LibraryOverlay.None || scrapeVisible
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
