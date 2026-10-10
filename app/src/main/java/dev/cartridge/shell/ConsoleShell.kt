package dev.cartridge.shell

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EmulationEngine
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.environment.ConsolePreferences
import dev.cartridge.environment.ConsoleSettings
import dev.cartridge.environment.GameOption
import dev.cartridge.environment.GameOptions
import dev.cartridge.environment.OptionStore
import dev.cartridge.input.InputMode
import dev.cartridge.library.*
import dev.cartridge.scraping.ScrapeCoordinator
import dev.cartridge.scraping.ScrapePackStore
import dev.cartridge.scraping.ScrapePage
import dev.cartridge.session.CollectionCoordinator
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.CollectionState
import dev.cartridge.storage.*
import dev.cartridge.ui.Accent
import dev.cartridge.ui.ClockStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** What only an Activity can do. Pickers answer through the matching `picked` function on the shell. */
interface ShellHost {
    fun pickFolder()
    fun pickGame()
    fun pickLabel()
    fun pickBios()
    fun pickBackup()
    fun pickPack()
    fun createBackup()
    fun openAndroidSettings()
    val homeEnabled: Boolean
    fun setHome(enabled: Boolean)
    fun chooseDefaultHome()
    fun persistFolder(uri: Uri)
    fun releaseFolder(uri: Uri)
    /** The stage lets a newly chosen shelf slide in from the side it was reached from. */
    fun stageArrive(direction: Int)
    fun inputChanged()
}

enum class HintMode(val title: String) {
    Auto("Until I know them"), Always("Always"), Hidden("Never");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Auto }
}

/**
 * The console's one navigation model. A stack of destinations sits over the library or a paused game;
 * every controller role is resolved here, so A, B, X, Y, the shoulders and Start mean one thing per screen.
 * Session and library truth stay in [CollectionCoordinator]; this class owns only presentation and preferences.
 */
class ConsoleShell(
    internal val context: Context,
    private val scope: CoroutineScope,
    internal val host: ShellHost,
    val coordinator: CollectionCoordinator,
    private val engine: EmulationEngine,
    internal val repository: CollectionRepository,
    val scraper: ScrapeCoordinator,
    private val labels: LabelStore,
    private val sessions: SessionStore,
    private val packs: ScrapePackStore,
    internal val preferences: SharedPreferences,
    private val systemDirectory: File = File(context.filesDir, "system"),
) {
    val stack = mutableStateListOf<Destination>()
    val top get() = stack.lastOrNull()
    private val focus = mutableStateMapOf<String, String>()
    var category by mutableStateOf(SettingsCategory.Library); private set
    /** Settings has two columns: 0 is the category list, 1 is that category's rows. */
    var pane by mutableIntStateOf(0); private set

    var sound by mutableStateOf(preferences.getBoolean("mediaSound", false)); private set
    var autoScan by mutableStateOf(preferences.getBoolean("autoScan", true)); private set
    var hints by mutableStateOf(HintMode.of(preferences.getString("hints", null))); private set
    var launches by mutableIntStateOf(preferences.getInt("launches", 0)); private set
    var showStatus by mutableStateOf(preferences.getBoolean("showStatus", true)); private set
    var showPercent by mutableStateOf(preferences.getBoolean("showPercent", true)); private set
    var clock by mutableStateOf(runCatching { ClockStyle.valueOf(preferences.getString("clock", "System")!!) }.getOrDefault(ClockStyle.System)); private set
    var accent by mutableStateOf(Accent.of(preferences.getString("accent", null))); private set
    var controls by mutableStateOf(ConsolePreferences(preferences).read()); private set
    val options = OptionStore(preferences)
    /** Bumped whenever a stored option changes, so pages built from [options] recompose. */
    var optionsRevision by mutableIntStateOf(0); private set
    var homeEnabled by mutableStateOf(host.homeEnabled); private set
    var biosReady by mutableStateOf(false); private set
    var roots by mutableStateOf<List<DirectoryRecord>>(emptyList()); private set
    var hidden by mutableStateOf<List<LibraryGame>>(emptyList()); private set
    var storage by mutableStateOf<String?>(null); private set
    /** Save and source facts for one game, keyed by its id so a stale answer is never shown for another. */
    var facts by mutableStateOf<Pair<String, LibraryFacts>?>(null); private set
    var notices by mutableStateOf<List<String>>(emptyList()); private set
    var noticeText by mutableStateOf(""); private set
    var readerLine by mutableIntStateOf(0); private set
    var backupSummary by mutableStateOf(""); private set
    var restorePreferences by mutableStateOf(false); private set
    private var backupReview: BackupReview? = null
    var import by mutableStateOf<ImportFlow?>(null); private set
    /** The most recent finished scan, whichever way it was reported. */
    var lastScan by mutableStateOf<ScanResult?>(null); private set
    /** Finished scans this session. Two scans can report equal results, so equality cannot tell them apart. */
    var scansFinished = 0; private set
    var draft by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private var draftOriginal = emptyMap<String, String>()
    var text by mutableStateOf(""); private set
    var shift by mutableStateOf(false); private set
    var key by mutableIntStateOf(0); private set
    /** A game being re-located keeps its identity across the picker, even if the Activity is recreated. */
    var pendingLocate: String? = null
    private var playingId: String? = null
    private enum class Scene { None, Library, Game, Label }
    private var scene = Scene.None
    private var lastScreen = CollectionScreen.Library
    private var lastSession = SessionState.Idle
    private var lastBusy = false

    init {
        scope.launch { biosReady = withContext(Dispatchers.IO) { bios().description().contains("Imported") } }
        refreshRoots()
    }
    private fun bios() = Ps1BiosStore(systemDirectory)
    private val state get() = coordinator.state.value
    private val status get() = engine.status.value

    // ---- Navigation -------------------------------------------------------------------------------------

    /** A destination already on top is not stacked again, however fast the button is pressed. */
    fun push(destination: Destination) {
        if (top?.key == destination.key) return
        // Arriving somewhere starts at its first row; coming back from a child keeps the place you left.
        focus.keys.filter { it == destination.key || it.startsWith("${destination.key}:") }.forEach(focus::remove)
        stack.add(destination)
        when (destination) {
            Destination.Settings -> { pane = 0; category = SettingsCategory.Library; refreshRoots(); refreshStorage(); homeEnabled = host.homeEnabled }
            Destination.Details -> state.selectedGame?.let { loadFacts(it.id) }
            Destination.Hidden -> scope.launch { hidden = repository.hiddenGames() }
            Destination.About -> scope.launch { notices = withContext(Dispatchers.IO) { context.assets.list("licenses").orEmpty().sorted() } }
            is Destination.Notice -> { readerLine = 0; noticeText = ""; scope.launch {
                noticeText = withContext(Dispatchers.IO) { context.assets.open("licenses/${destination.name}").bufferedReader().use { it.readText() } } } }
            is Destination.TextEntry -> { text = destination.spec.initial; shift = destination.spec.titleCase && text.isEmpty(); key = KEY_COLUMNS }
            else -> Unit
        }
        host.inputChanged()
    }
    fun pop() {
        when (stack.removeLastOrNull()) {
            Destination.BackupReview -> closeReview()
            Destination.LaunchFailure -> coordinator.dismissLaunchFailure()
            Destination.Import -> finishImport()
            else -> Unit
        }
        host.inputChanged()
    }
    fun home() { while (stack.isNotEmpty()) pop() }
    private fun replace(destination: Destination) { home(); push(destination) }

    fun page(state: CollectionState = this.state, status: EngineStatus = this.status, destination: Destination? = top): Page? =
        buildPage(state, status, destination)
    /** The opaque destination nearest the top, if any: it is what a sheet above it rests on. */
    val base get() = stack.lastOrNull { it.opaque }
    fun reloadHidden() { scope.launch { coordinator.state.first { !it.busy }; hidden = repository.hiddenGames() } }
    fun focused(page: Page): MenuRow? = page.rows.firstOrNull { it.id == focus[page.key] && it.focusable } ?: page.rows.firstOrNull { it.focusable }
    fun focus(page: Page, row: MenuRow) { if (row.focusable) focus[page.key] = row.id }
    private fun move(page: Page, delta: Int) {
        val rows = page.rows.filter { it.focusable }
        if (rows.isEmpty()) return
        val index = rows.indexOf(focused(page)).coerceAtLeast(0)
        focus[page.key] = rows[(index + delta).coerceIn(0, rows.lastIndex)].id
    }
    fun chooseCategory(next: SettingsCategory) { category = next }
    fun enterRows() { pane = 1 }

    fun inputMode(): InputMode = when {
        scraper.state.value.visible || top != null -> InputMode.Menu
        !state.loaded || state.busy -> InputMode.Locked
        state.screen == CollectionScreen.LabelPreview -> InputMode.Menu
        status.state == SessionState.Running -> InputMode.Gameplay
        status.state == SessionState.Paused -> InputMode.Menu
        state.screen == CollectionScreen.Library -> InputMode.Library
        else -> InputMode.Locked
    }

    /** Called for every coordinator or engine change. Overlays never outlive the scene they were opened on. */
    fun sync(state: CollectionState, status: EngineStatus) {
        val next = when {
            status.state == SessionState.Paused -> Scene.Game
            state.screen == CollectionScreen.LabelPreview -> Scene.Label
            state.screen == CollectionScreen.Library && status.state == SessionState.Idle -> Scene.Library
            else -> Scene.None
        }
        if (next != scene) {
            if (top != Destination.LaunchFailure) home()
            scene = next
            if (next == Scene.Game) { playingId = state.selectedGame?.id; playingId?.let(::loadFacts) }
        }
        if (lastScreen == CollectionScreen.LabelPreview && state.screen == CollectionScreen.Library)
            scraper.previewFinished(applied = state.lastLabelAppliedTo == scraper.state.value.current?.game?.id)
        lastScreen = state.screen
        if (status.state == SessionState.Running && lastSession == SessionState.Preparing) {
            launches++; preferences.edit { putInt("launches", launches) }
        }
        lastSession = status.state
        // A finished pause-menu operation may have written a state; show its time without asking.
        if (lastBusy && !state.busy && scene == Scene.Game) playingId?.let(::loadFacts)
        lastBusy = state.busy
        if (state.launchFailure != null && top != Destination.LaunchFailure && state.screen == CollectionScreen.Library) replace(Destination.LaunchFailure)
        host.inputChanged()
    }

    // ---- Controller roles -------------------------------------------------------------------------------

    fun navigate(delta: Int) {
        if (scraper.state.value.visible) return scraper.navigate(delta)
        when (top) {
            is Destination.TextEntry -> moveKey(0, delta)
            is Destination.Notice -> readerLine = (readerLine + delta).coerceAtLeast(0)
            Destination.Settings -> if (pane == 0) category = SettingsCategory.entries[(category.ordinal + delta).coerceIn(0, SettingsCategory.entries.lastIndex)]
                else page()?.let { move(it, delta) }
            else -> page()?.let { move(it, delta) }
        }
    }
    fun adjust(delta: Int) {
        if (scraper.state.value.visible) return
        when (top) {
            is Destination.TextEntry -> moveKey(delta, 0)
            Destination.Settings -> if (pane == 0) { if (delta > 0) pane = 1 } else {
                val row = page()?.let(::focused)
                if (row?.adjust != null && row.enabled) row.adjust.invoke(delta) else if (delta < 0) pane = 0
            }
            else -> page()?.let(::focused)?.takeIf { it.enabled }?.adjust?.invoke(delta)
        }
    }
    fun select() {
        if (scraper.state.value.visible) return scraper.select()
        when (top) {
            is Destination.TextEntry -> pressKey()
            Destination.Settings -> if (pane == 0) pane = 1 else page()?.let(::activate)
            else -> page()?.let(::activate) ?: confirmLibrary()
        }
    }
    fun activate(page: Page, row: MenuRow? = focused(page)) {
        if (row == null || !row.enabled) return
        focus(page, row)
        row.action?.invoke() ?: row.adjust?.invoke(1)
    }
    private fun confirmLibrary() {
        if (state.screen != CollectionScreen.Library || status.state != SessionState.Idle) return
        if (state.allGames.isEmpty()) addGames() else coordinator.insert()
    }
    fun back() {
        if (scraper.state.value.visible) return scraper.back()
        val current = top
        when {
            current == Destination.Settings && pane == 1 -> pane = 0
            current == Destination.Import && import.let { it is ImportFlow.Looking || it is ImportFlow.Adding } -> coordinator.cancelScan()
            current != null -> pop()
            state.screen == CollectionScreen.LabelPreview -> coordinator.cancelLabel()
            status.state == SessionState.Paused -> coordinator.menuAction(0)
            state.screen == CollectionScreen.Library && state.searching -> coordinator.query(state.query.copy(search = ""))
        }
    }
    /** X. */
    fun alternate() {
        val scrape = scraper.state.value
        when {
            scrape.visible -> when (scrape.page) {
                ScrapePage.Metadata -> scrape.candidate?.metadata?.keys?.elementAtOrNull(scrape.focus)?.let(scraper::variant)
                ScrapePage.Targets -> scraper.pickAll(scrape.pickedIds.size != scrape.availableGames.size)
                ScrapePage.Consoles -> scraper.pickAllConsoles(scrape.pickedSystems.size != scrape.consoles.size)
                else -> Unit
            }
            top is Destination.TextEntry -> backspace()
            top == null && inputMode() == InputMode.Library -> openDetails()
        }
    }
    /** Y. */
    fun secondary() {
        when {
            scraper.state.value.visible -> Unit
            top is Destination.TextEntry -> type(' ')
            top == Destination.Details -> coordinator.favorite()
            top == null && inputMode() == InputMode.Library -> coordinator.favorite()
        }
    }
    /** L1 / R1. */
    fun shoulder(delta: Int) {
        when {
            scraper.state.value.visible -> Unit
            top == Destination.Settings -> category = SettingsCategory.entries[Math.floorMod(category.ordinal + delta, SettingsCategory.entries.size)]
            top == null && inputMode() == InputMode.Library -> switchShelf(delta)
        }
    }
    /** Start. */
    fun menu() {
        when {
            scraper.state.value.visible -> Unit
            top is Destination.TextEntry -> finishText()
            top == Destination.SystemMenu -> pop()
            top == null && inputMode() == InputMode.Library -> push(Destination.SystemMenu)
        }
    }
    fun switchShelf(delta: Int) {
        val before = state.shelf
        coordinator.switchShelf(delta)
        if (state.shelf != before) host.stageArrive(delta)
    }
    fun openDetails() { if (state.selectedGame != null && state.screen == CollectionScreen.Library && !state.busy) push(Destination.Details) }
    fun openMenu() { if (top == null && inputMode() == InputMode.Library) push(Destination.SystemMenu) }

    // ---- Text entry -------------------------------------------------------------------------------------

    private val spec get() = (top as? Destination.TextEntry)?.spec
    private fun moveKey(dx: Int, dy: Int) {
        val row = key / KEY_COLUMNS; val column = key % KEY_COLUMNS
        val nextRow = (row + dy).coerceIn(0, KEY_ROWS.lastIndex)
        key = nextRow * KEY_COLUMNS + (column + dx).coerceIn(0, KEY_COLUMNS - 1)
    }
    /** The bottom row holds four wide keys, each spanning several columns of the grid above it. */
    fun wideKey(column: Int) = when { column < 2 -> 0; column < 6 -> 1; column < 8 -> 2; else -> 3 }
    private fun pressKey() {
        val row = key / KEY_COLUMNS; val column = key % KEY_COLUMNS
        if (row < KEY_ROWS.lastIndex) type(KEY_ROWS[row][column]) else when (wideKey(column)) {
            0 -> shift = !shift
            1 -> type(' ')
            2 -> backspace()
            else -> finishText()
        }
    }
    fun pressKeyAt(index: Int) { key = index; pressKey() }
    fun type(character: Char) {
        val limit = spec?.limit ?: return
        if (text.length >= limit) return
        text += if (shift) character.uppercaseChar() else character.lowercaseChar()
        shift = spec?.titleCase == true && character == ' '
    }
    fun backspace() { if (text.isNotEmpty()) text = text.dropLast(1); if (text.isEmpty() && spec?.titleCase == true) shift = true }
    /** Touch or a hardware keyboard edits the same value through the visible text field. */
    fun replaceText(value: String) { spec?.let { if (value.length <= it.limit) text = value } }
    fun finishText() { val current = spec ?: return; val value = text.trim(); pop(); current.done(value) }

    // ---- Preferences ------------------------------------------------------------------------------------

    fun setLayout(layout: CollectionLayout) = coordinator.settings(layout = layout)
    fun setReducedMotion(value: Boolean) { coordinator.settings(reducedMotion = value); preferences.edit { putBoolean("reducedMotion", value) } }
    fun setPauseShortcut(alternate: Boolean) { coordinator.settings(alternateShortcut = alternate); preferences.edit { putBoolean("alternateShortcut", alternate) } }
    fun changeSound(value: Boolean) { sound = value; preferences.edit { putBoolean("mediaSound", value) } }
    fun changeAutoScan(value: Boolean) { autoScan = value; preferences.edit { putBoolean("autoScan", value) } }
    fun changeHints(value: HintMode) { hints = value; preferences.edit { putString("hints", value.name) } }
    fun changeShowStatus(value: Boolean) { showStatus = value; preferences.edit { putBoolean("showStatus", value) } }
    fun changeShowPercent(value: Boolean) { showPercent = value; preferences.edit { putBoolean("showPercent", value) } }
    fun changeClock(value: ClockStyle) { clock = value; preferences.edit { putString("clock", value.name) } }
    fun changeAccent(value: Accent) { accent = value; preferences.edit { putString("accent", value.name) } }
    fun changeControls(next: ConsoleSettings) { controls = next; ConsolePreferences(preferences).write(next); host.inputChanged() }
    fun setHome(value: Boolean) { host.setHome(value); homeEnabled = host.homeEnabled }
    val hintsVisible get() = hints == HintMode.Always || hints == HintMode.Auto && launches < LEARNED_AFTER

    fun setConsoleOption(option: GameOption, value: String) { options.setConsole(option, value); optionsRevision++ }
    /** A null value hands the game back to its console's setting. Live options reach a paused game at once. */
    fun setGameOption(game: LibraryGame, option: GameOption, value: String?) {
        options.setGame(game.id, option, value); optionsRevision++
        if (status.state == SessionState.Paused && option.live) coordinator.configureGame(GameOptions.session(options.resolve(game.system, game.id), liveOnly = true)) {}
    }
    fun clearGameOptions(game: LibraryGame) {
        options.clearGame(game.id); optionsRevision++
        if (status.state == SessionState.Paused) coordinator.configureGame(GameOptions.session(options.resolve(game.system, game.id), liveOnly = true)) {}
    }
    fun sessionOptions(system: GameSystem, gameId: String) = GameOptions.session(options.resolve(system, gameId))

    /** Presentation, controls and gameplay options return to their defaults. The library, folders, saves and HOME role are untouched. */
    fun resetSettings() {
        changeControls(ConsoleSettings()); options.resetConsoles(); optionsRevision++
        setReducedMotion(false); setPauseShortcut(false); changeSound(false); changeAutoScan(true); changeHints(HintMode.Auto)
        changeShowStatus(true); changeShowPercent(true); changeClock(ClockStyle.System); changeAccent(Accent.Sand)
        coordinator.settings(layout = CollectionLayout.Horizontal)
        coordinator.notify("Settings are back to their defaults")
    }
    /** After a backup restored stored settings, read them back into the running console. */
    private fun reloadPreferences() {
        controls = ConsolePreferences(preferences).read(); sound = preferences.getBoolean("mediaSound", false); autoScan = preferences.getBoolean("autoScan", true)
        optionsRevision++; host.inputChanged()
        scope.launch {
            coordinator.state.first { !it.busy }
            coordinator.settings(preferences.getBoolean("reducedMotion", false), preferences.getBoolean("alternateShortcut", false),
                runCatching { CollectionLayout.valueOf(preferences.getString("layout", "Horizontal")!!) }.getOrDefault(CollectionLayout.Horizontal))
        }
    }

    // ---- Library operations -----------------------------------------------------------------------------

    private fun refreshRoots() { scope.launch { roots = (repository as? DirectoryRepository)?.directoryRecords().orEmpty() } }
    private fun refreshStorage() {
        scope.launch {
            storage = withContext(Dispatchers.IO) {
                fun size(name: String) = File(context.filesDir, name).walkTopDown().filter(File::isFile).sumOf(File::length)
                "Games ${bytes(size("roms"))} · Artwork ${bytes(size("labels"))} · Saves ${bytes(size("saves"))}"
            }
        }
    }
    private fun bytes(value: Long) = when {
        value >= 1L shl 30 -> "%.1f GB".format(value / (1L shl 30).toDouble())
        value >= 1L shl 20 -> "${value shr 20} MB"
        else -> "${(value shr 10).coerceAtLeast(if (value > 0) 1 else 0)} KB"
    }
    fun loadFacts(gameId: String) {
        scope.launch { runCatching { repository.facts(gameId) }.onSuccess { facts = gameId to it } }
    }

    fun addGames() { home(); host.pickFolder() }
    fun addSingleGame() { home(); pendingLocate = null; host.pickGame() }
    fun locateGame() { val game = state.selectedGame ?: return; home(); pendingLocate = game.id; host.pickGame() }
    fun folderPicked(uri: Uri) = scan(uri)
    fun refreshLibrary(verify: Boolean = false) = scan(null, verify)
    /** Opening the console looks for new games without a dialog; only something new earns a notice. */
    fun scanOnOpen() {
        if (!autoScan) return
        scope.launch { if ((repository as? DirectoryRepository)?.directories()?.isNotEmpty() == true) scan(null, quiet = true) }
    }
    private fun scan(target: Uri?, verify: Boolean = false, quiet: Boolean = false) {
        val first = state.allGames.isEmpty()
        if (!state.loaded || state.busy || state.screen != CollectionScreen.Library) return
        if (!quiet) { home(); import = ImportFlow.Looking(null, first); push(Destination.Import) }
        coordinator.scanDirectory { _ ->
            try {
                val directories = checkNotNull(repository as? DirectoryRepository) { "Folder import is unavailable" }
                if (target != null) host.persistFolder(target)
                val targets = target?.let { listOf(it.toString()) } ?: directories.directories()
                require(targets.isNotEmpty()) { "Add a game folder first" }
                val scanner = DirectoryScanner(context.contentResolver, File(context.filesDir, "roms"), repository, directories)
                var found = Discovery(""); var base = 0; var total: ScanResult? = null
                if (!quiet) {
                    for (tree in targets) scanner.discover(tree.toUri()).let { next ->
                        import = ImportFlow.Looking(next.folder, first)
                        found = Discovery(if (targets.size == 1) next.folder else "${targets.size} folders",
                            (found.games.keys + next.games.keys).associateWith { (found.games[it] ?: 0) + (next.games[it] ?: 0) },
                            (found.otherConsoles.keys + next.otherConsoles.keys).associateWith { (found.otherConsoles[it] ?: 0) + (next.otherConsoles[it] ?: 0) },
                            found.archives + next.archives, found.playlists + next.playlists, found.complete && next.complete)
                    }
                    import = ImportFlow.Adding(found, 0, "", first)
                }
                for (tree in targets) {
                    val result = scanner.scan(tree.toUri(), {}, verify) { name, handled ->
                        if (!quiet) import = ImportFlow.Adding(found, base + handled, name.substringBeforeLast('.'), first)
                    }
                    base += result.added + result.duplicates + result.failed
                    total = total?.plus(result) ?: result
                }
                val result = checkNotNull(total)
                lastScan = result; scansFinished++
                refreshRoots(); refreshStorage()
                val needsBios = (result.addedSystems[GameSystem.PS1] ?: 0) > 0 && !biosReady
                if (quiet) return@scanDirectory if (result.added > 0) importHeadline(result) else ""
                // A clean import after the first one is a single line, not a screen to dismiss.
                if (!first && !needsBios && importNotes(result).isEmpty() && result.added > 0) {
                    revealAfterScan(result); stack.remove(Destination.Import); import = null; host.inputChanged()
                    importHeadline(result)
                } else { import = ImportFlow.Done(result, targets.size, needsBios, first); "" }
            } catch (failure: CancellationException) {
                if (!quiet) { stack.remove(Destination.Import); import = null; host.inputChanged() }
                refreshRoots(); throw failure
            } catch (failure: Exception) {
                if (quiet) "" else { import = ImportFlow.Failed(plain(failure.message), first); "" }
            }
        }
    }
    private fun revealAfterScan(result: ScanResult) {
        val id = result.firstAdded ?: return
        scope.launch { coordinator.state.first { !it.busy }; coordinator.reveal(id) }
    }
    private fun finishImport() {
        (import as? ImportFlow.Done)?.let { revealAfterScan(it.result) }
        import = null
    }
    fun forgetFolder(uri: String) {
        scope.launch {
            try {
                (repository as DirectoryRepository).forgetDirectory(uri)
                runCatching { host.releaseFolder(uri.toUri()) }
                refreshRoots(); pop(); coordinator.notify("Folder removed. Its games stay in your library.")
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; coordinator.notify("This folder could not be removed: ${plain(failure.message)}") }
        }
    }
    fun refreshFolder(uri: String) = scan(uri.toUri())

    fun gamePicked(uri: Uri) {
        val expected = pendingLocate
        pendingLocate = null
        val import: suspend () -> LibraryGame = {
            runCatching { host.persistFolder(uri) }
            withContext(Dispatchers.IO) {
                val taskContext = coroutineContext
                val game = RomImporter(context.contentResolver, File(context.filesDir, "roms")).import(uri) { taskContext.ensureActive() }
                require(expected == null || expected == game.request.id) { "That is a different game file. Choose the same file to repair this entry; its label and saves are kept." }
                LibraryGame(game.request.id, game.title, uri.toString(), system = game.request.system)
            }
        }
        scope.launch {
            coordinator.state.first { it.loaded && !it.busy && it.screen == CollectionScreen.Library }
            coordinator.addGame(import)
        }
    }
    fun gamePickCancelled() { pendingLocate = null }

    fun chooseLabel() {
        if (state.busy || state.screen != CollectionScreen.Library || state.selectedGame == null) return
        home(); host.pickLabel()
    }
    fun labelPicked(uri: Uri) = coordinator.previewLabel {
        runCatching { host.persistFolder(uri) }
        withContext(Dispatchers.IO) { labels.import(context.contentResolver, uri) }
    }

    fun chooseBios() {
        if (status.state != SessionState.Idle) return coordinator.notify("Return to the library before adding a BIOS file")
        if (!state.busy) host.pickBios()
    }
    fun biosPicked(uri: Uri) {
        if (top == Destination.Import || top == Destination.LaunchFailure) pop()
        coordinator.libraryOperation {
            withContext(Dispatchers.IO) { bios().import(context.contentResolver, uri) }
            biosReady = true; coordinator.notify("PlayStation BIOS added. Games will use it from their next start.")
        }
    }

    fun openScraper(single: Boolean = false) {
        if (!state.loaded || state.busy || state.screen != CollectionScreen.Library) return
        home()
        scraper.open(state.allGames, state.selectedGame)
        if (single) scraper.chooseScope(true)
        host.inputChanged()
    }
    fun packPicked(uri: Uri) {
        scope.launch {
            coordinator.state.first { it.loaded && !it.busy && it.screen == CollectionScreen.Library }
            if (!scraper.state.value.visible) openScraper()
            scraper.importPack { packs.import(context.contentResolver, uri) }
        }
    }

    // ---- Game details -----------------------------------------------------------------------------------

    fun editDetails() {
        val game = state.selectedGame ?: return
        draftOriginal = linkedMapOf("title" to (game.metadata["title"]?.value ?: game.title),
            "year" to game.metadata["year"]?.value.orEmpty(), "developer" to game.metadata["developer"]?.value.orEmpty(),
            "publisher" to game.metadata["publisher"]?.value.orEmpty(), "genre" to game.metadata["genre"]?.value.orEmpty(),
            "description" to game.metadata["description"]?.value.orEmpty())
        draft = draftOriginal
        push(Destination.EditDetails)
    }
    fun editField(field: String) = push(Destination.TextEntry(TextSpec(field.replaceFirstChar(Char::uppercase),
        if (field == "title") "Leave empty to use the file name" else "Leave empty to clear", draft[field].orEmpty(),
        if (field == "description") 2000 else 120, "edit-$field", titleCase = field != "description") { value -> draft = draft + (field to value) }))
    val draftChanged get() = draft != draftOriginal
    fun saveDetails() {
        val changed = draft.filter { (field, value) -> value != draftOriginal[field] }
        while (top != null && top != Destination.Details) pop()
        coordinator.editMetadata(changed)
    }
    fun search() = push(Destination.TextEntry(TextSpec("Search", "Title, developer or genre", state.query.search, 60, "library-search-text") { value ->
        home(); coordinator.query(state.query.copy(search = value))
    }))
    fun play() { home(); coordinator.insert() }
    fun hide() { home(); coordinator.hideSelected() }

    // ---- Backup -----------------------------------------------------------------------------------------

    private fun backupStore() = BackupStore(checkNotNull(repository as? BackupRepository), labels, SaveStore(File(context.filesDir, "saves")),
        sessions, File(context.cacheDir, "backup-staging"))
    fun exportBackup() { if (!state.busy) host.createBackup() }
    fun restoreBackup() { if (!state.busy) host.pickBackup() }
    fun backupCreated(uri: Uri) {
        coordinator.libraryOperation {
            withContext(Dispatchers.IO) {
                val file = File.createTempFile("cartridge-backup-", ".zip", context.cacheDir)
                try {
                    file.outputStream().use { backupStore().export(it, BackupPreferences.capture(preferences)) }
                    check(file.length() <= 1024L * 1024 * 1024) { "Backup exceeds 1 GiB" }
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot write the selected backup" }.use { output -> file.inputStream().use { it.copyTo(output) } }
                } finally { file.delete() }
            }
            coordinator.notify("Backup saved. Keep it private: it holds your collection choices and saves.")
        }
    }
    fun backupPicked(uri: Uri) {
        coordinator.libraryOperation {
            val review = withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openInputStream(uri)).use { backupStore().inspect(it) } }
            backupReview?.close(); backupReview = review; restorePreferences = false
            backupSummary = "${plural(review.library.games.size, "game")} · ${plural(review.imageCount, "image")} · ${plural(review.saveCount, "save")}"
            push(Destination.BackupReview)
        }
    }
    fun toggleRestorePreferences() { restorePreferences = !restorePreferences }
    fun mergeBackup() {
        val review = backupReview ?: return
        val settings = restorePreferences
        // Transfer ownership to the serialized operation so Activity cancellation cannot delete
        // reviewed files during acceptance. Its finally block releases staging.
        backupReview = null
        coordinator.libraryOperation {
            try {
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    val message = backupStore().merge(review)
                    if (settings) BackupPreferences.restore(preferences, review.preferences)
                    message
                }
                if (settings) reloadPreferences()
                stack.remove(Destination.BackupReview); host.inputChanged(); coordinator.notify(result)
            } finally { withContext(NonCancellable + Dispatchers.IO) { review.close() } }
        }
    }
    private fun closeReview() { backupReview?.close(); backupReview = null }
    fun close() { closeReview() }

    companion object {
        const val KEY_COLUMNS = 10
        /** The last row is the wide keys: shift, space, delete, done. */
        val KEY_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.-", "")
        /** Hints retire on their own once this many games have been started. */
        const val LEARNED_AFTER = 5

        /** A folder that can no longer be read, as opposed to one that merely held a file Cartridge skipped. */
        fun unreachable(error: String?): String? = plain(error).takeIf { error != null && it != error && ("permission" in it || "can't be reached" in it) }

        /** Storage and permission failures in words a player can act on. Anything already plain passes through. */
        fun plain(reason: String?): String = when {
            reason == null -> "The operation stopped before it finished. Try again."
            listOf("Permission Denial", "SecurityException", "requires that you obtain access").any { it in reason } ->
                "Cartridge no longer has permission to read this folder. Choose it again to reconnect."
            listOf("ENOENT", "No such file", "FileNotFound", "is unavailable", "does not exist").any { it in reason } ->
                "The file or folder can't be reached. If it is on an SD card, check that the card is inserted."
            listOf("ENOSPC", "No space left").any { it in reason } -> "This device is out of storage space."
            "ROM is incomplete" in reason || "is too small" in reason || "header is incomplete" in reason -> reason.substringBefore(": ", "").let { name ->
                (if (name.isEmpty()) "" else "$name: ") + "this file is too small to be a game" }
            else -> reason
        }
    }
}
