package dev.cartridge

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.view.KeyEvent
import dev.cartridge.apps.AppLibrary
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.PadKey
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EmulationEngine
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.emulation.GameRequest
import dev.cartridge.environment.ConsolePreferences
import dev.cartridge.input.InputMode
import dev.cartridge.input.QuickCombo
import dev.cartridge.library.*
import dev.cartridge.scraping.ArtworkCandidate
import dev.cartridge.scraping.ArtworkProvider
import dev.cartridge.scraping.GameCandidate
import dev.cartridge.scraping.ScrapeCoordinator
import dev.cartridge.scraping.ScrapePackStore
import dev.cartridge.session.CollectionCoordinator
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.RendererHandoff
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.Tab
import dev.cartridge.shell.Destination
import dev.cartridge.shell.HintMode
import dev.cartridge.shell.Page
import dev.cartridge.shell.PageStyle
import dev.cartridge.shell.controlRows
import dev.cartridge.shell.ShellHost
import dev.cartridge.storage.LabelStore
import dev.cartridge.storage.SessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the back button means, decided by the real console shell over the real coordinator. The Android
 * apps are reached from the library at rest and from nowhere else; everything that was open closes first,
 * and a game keeps its own B.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellNavigationTest {
    @get:Rule val files = TemporaryFolder()

    private class Preferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, default: String?) = values[key] as? String ?: default
        @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, default: MutableSet<String>?) = values[key] as? MutableSet<String> ?: default
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableListOf<() -> Unit>()
            private fun put(key: String, value: Any?): SharedPreferences.Editor { pending += { values[key] = value }; return this }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, value: MutableSet<String>?) = put(key, value)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String): SharedPreferences.Editor { pending += { values.remove(key) }; return this }
            override fun clear(): SharedPreferences.Editor { pending += { values.clear() }; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { pending.forEach { it() }; pending.clear() }
        }
    }
    private class Host : ShellHost {
        var modeChanges = 0
        override fun pickFolder() = Unit
        override fun pickGame() = Unit
        override fun pickLabel() = Unit
        override fun pickFirmware() = Unit
        override fun pickBackup() = Unit
        override fun pickPack() = Unit
        override fun createBackup() = Unit
        override fun openAndroidSettings() = Unit
        override val homeEnabled = false
        override fun setHome(enabled: Boolean) = Unit
        override fun chooseDefaultHome() = Unit
        override fun persistFolder(uri: Uri) = Unit
        override fun releaseFolder(uri: Uri) = Unit
        override fun stageArrive(direction: Int) = Unit
        override fun inputChanged() { modeChanges++ }
    }
    private class Library : CollectionRepository {
        val games = listOf("First", "Second", "Third").mapIndexed { index, title -> LibraryGame("${'a' + index}".repeat(64), title, "content://$index") }
        override val snapshots = MutableStateFlow(LibrarySnapshot(games, 1))
        override suspend fun initialize() = snapshots.value
        override suspend fun assign(slot: Int, game: LibraryGame) = Unit
        override suspend fun add(game: LibraryGame, restoreRemoved: Boolean) = false
        override suspend fun remove(gameId: String) = Unit
        override suspend fun restore(gameId: String, position: Int) = Unit
        override suspend fun select(slot: Int) { snapshots.value = snapshots.value.copy(selectedSlot = slot) }
        override suspend fun setLabel(gameId: String, label: LabelRef?) = Unit
        override suspend fun setMetadata(gameId: String, values: Map<String, MetadataValue>) = Unit
        override suspend fun fillMissingMetadata(gameId: String, values: Map<String, MetadataValue>) = 0
        override suspend fun setMedia(gameId: String, media: MediaSelection) = Unit
        override suspend fun undoEnrichment(gameId: String) = false
        override suspend fun setFavorite(gameId: String, favorite: Boolean) = Unit
        override suspend fun setQuery(query: LibraryQuery) { snapshots.value = snapshots.value.copy(query = query) }
        override suspend fun recordPlay(gameId: String, elapsedMs: Long, startedAt: Long?) = Unit
        override suspend fun facts(gameId: String) = LibraryFacts(true, "Fixture", 0, 0)
        override suspend fun gameRequest(game: LibraryGame, disc: Int?) = GameRequest(File("${game.id}.gba"), game.id)
    }
    private class Engine : EmulationEngine {
        override val status = MutableStateFlow(EngineStatus())
        val keys = mutableListOf<Int>()
        override suspend fun prepare(game: GameRequest) { status.value = EngineStatus(SessionState.Running) }
        // As the real engine: the menu opens over a running or a frozen game at normal speed; a freeze and a resume keep the speed.
        override suspend fun pause() { if (status.value.state == SessionState.Running || status.value.frozen) status.value = EngineStatus(SessionState.Paused) }
        override suspend fun freeze() { if (status.value.state == SessionState.Running) status.value = status.value.copy(state = SessionState.Paused, frozen = true) }
        override suspend fun speed(frames: Int) { if (status.value.state == SessionState.Running) status.value = status.value.copy(speed = frames) }
        override suspend fun resume() { status.value = EngineStatus(SessionState.Running, speed = status.value.speed) }
        override suspend fun stop() { status.value = EngineStatus() }
        override fun key(action: Int, androidKeyCode: Int) { keys += androidKeyCode }
        override fun motion(x: Float, y: Float) = Unit
        override suspend fun saveState() = Unit
        override suspend fun loadState() = Unit
        override suspend fun persistSaveRam() = Unit
        override suspend fun configure(options: Map<String, String>) = Unit
    }
    private object NoArtwork : ArtworkProvider {
        override val name = "Original fixture"
        override suspend fun search(query: String, rom: File?, system: GameSystem): List<GameCandidate> = emptyList()
        override suspend fun image(candidate: ArtworkCandidate) = error("No images in navigation tests")
    }

    /** The production shell and coordinator, loaded and at rest in the library, told about every change as the Activity tells them. */
    private inner class Console(scope: TestScope, val preferences: Preferences = Preferences(), val source: FixtureApps = FixtureApps("Tide Clock", "Field Notes", "Atlas", "Harbor Mail")) {
        val engine = Engine()
        val library = Library()
        val host = Host()
        val coordinator = CollectionCoordinator(engine, library, object : RendererHandoff {
            override fun releaseLibrary() = Unit
            override fun releaseEmulator() = Unit
        }, scope.backgroundScope, reducedMotion = true)
        private val labels = LabelStore(files.newFolder())
        private val scraper = ScrapeCoordinator(NoArtwork, library, labels, files.newFolder(), scope.backgroundScope) { _, _ -> }
        val shell = ConsoleShell(object : ContextWrapper(null) {}, scope.backgroundScope, host, coordinator, engine, library, scraper, labels,
            SessionStore(files.newFolder()), ScrapePackStore(files.newFolder()), preferences, files.newFolder(), source, StandardTestDispatcher(scope.testScheduler))
        val state get() = coordinator.state.value
        init {
            scope.backgroundScope.launch { combine(coordinator.state, engine.status, scraper.state) { state, status, _ -> state to status }.collect { (state, status) -> shell.sync(state, status) } }
            scope.runCurrent(); coordinator.rendererReady(0); scope.runCurrent()
            check(state.loaded && !state.busy && shell.inputMode() == InputMode.Library) { "The fixture library is not at rest" }
        }
    }

    @Test fun r2FromTheLibraryOpensAppsAndBackReturnsToTheSameGame() = runTest {
        val console = Console(this); val shell = console.shell
        console.coordinator.navigate(1); runCurrent()
        val before = console.state
        assertEquals("Third", before.selectedGame!!.title); assertNull(shell.top); assertFalse(shell.appsOpen)
        // B at the library's root stays where it is; Apps is a tab, on the triggers.
        shell.back(); assertNull(shell.top); assertEquals(Tab.Library, shell.tab)
        shell.trigger(1); assertEquals(Destination.Systems, shell.top); assertEquals(Tab.Systems, shell.tab)
        shell.trigger(1)
        assertEquals(Destination.Apps, shell.top); assertTrue(shell.appsOpen); assertEquals(Tab.Apps, shell.tab)
        // An opaque page over the library: its buttons are the page's, and the stage waits beneath it.
        assertEquals(InputMode.Menu, shell.inputMode()); assertEquals(Destination.Apps, shell.base)
        runCurrent()
        assertEquals(listOf("Atlas", "Field Notes", "Harbor Mail", "Tide Clock"), shell.apps.entries.map { it.label })
        shell.back()
        assertNull(shell.top); assertEquals(InputMode.Library, shell.inputMode())
        // Nothing about the collection moved: same shelf, same game, same stage, nothing reloaded.
        val after = console.state
        assertEquals(before.selection, after.selection); assertEquals(before.shelf, after.shelf); assertEquals(before.selectedGame, after.selectedGame)
        assertEquals(before.viewportGeneration, after.viewportGeneration); assertTrue(after.rendererReady); assertEquals(CollectionScreen.Library, after.screen)
        // Opening it again is not a second copy of it, however fast the button is pressed.
        shell.back(); shell.openApps(); shell.openApps()
        assertEquals(listOf<Destination>(Destination.Apps), shell.stack.toList())
        console.coordinator.destroy()
    }

    @Test fun whateverIsOpenClosesBeforeBackEverMeansApps() = runTest {
        val console = Console(this); val shell = console.shell
        // The system menu.
        shell.menu(); assertEquals(Destination.SystemMenu, shell.top)
        shell.back(); assertNull(shell.top)
        // Game details, and a sheet opened from them.
        shell.alternate(); assertEquals(Destination.Details, shell.top)
        shell.push(Destination.Manage); shell.back(); assertEquals(Destination.Details, shell.top)
        shell.back(); assertNull(shell.top)
        // Settings two levels deep: rows, then sections, then the menu it came from.
        shell.menu(); shell.push(Destination.Settings); shell.select(); assertEquals(1, shell.pane)
        shell.back(); assertEquals(0, shell.pane); assertEquals(Destination.Settings, shell.top)
        shell.back(); assertEquals(Destination.SystemMenu, shell.top)
        shell.back(); assertNull(shell.top)
        // A dialog.
        shell.push(Destination.Folder("content://tree")); shell.back(); assertNull(shell.top)
        // A library search is cleared by the first press, as before; only the next one leaves for Apps.
        console.coordinator.query(console.state.query.copy(search = "second")); runCurrent()
        assertTrue(console.state.searching); assertEquals("Second", console.state.selectedGame!!.title)
        shell.openApps(); assertNull("Nothing reaches Apps past a search that is still showing", shell.top)
        shell.back(); runCurrent()
        assertFalse(console.state.searching); assertNull(shell.top)
        shell.back(); assertNull("B never leaves the library for Apps", shell.top)
        console.coordinator.destroy()
    }

    @Test fun theTriggersStepThroughTheBuiltTabsAndWrapAround() = runTest {
        val console = Console(this); val shell = console.shell
        shell.trigger(1); assertEquals(Tab.Systems, shell.tab); assertEquals(listOf<Destination>(Destination.Systems), shell.stack.toList())
        shell.trigger(1); assertEquals(Tab.Apps, shell.tab)
        shell.trigger(1); assertEquals(Tab.Settings, shell.tab); assertEquals(listOf<Destination>(Destination.Settings), shell.stack.toList())
        shell.trigger(1); assertEquals(Tab.Library, shell.tab); assertNull(shell.top)
        shell.trigger(-1); assertEquals(Tab.Settings, shell.tab)
        shell.trigger(-1); assertEquals(Tab.Apps, shell.tab)
        shell.trigger(-1); assertEquals(Tab.Systems, shell.tab)
        // Over anything that is not a tab, such as a menu or a dialog, they do nothing.
        shell.trigger(-1); shell.menu(); assertEquals(Destination.SystemMenu, shell.top)
        shell.trigger(1); assertEquals(Destination.SystemMenu, shell.top)
        console.coordinator.destroy()
    }

    @Test fun systemsListsEachConsoleBrowsesItsGamesAndOpensItsCore() = runTest {
        val console = Console(this); val shell = console.shell
        shell.openTab(Tab.Systems)
        val page = shell.page()!!
        assertEquals(console.state.allGames.map { it.system }.distinct().size, page.rows.size)
        val system = dev.cartridge.domain.GameSystem.entries.first { "system:${it.name}" == shell.focused(page)!!.id }
        // Y: the console's core, its options for every game, its buttons and shader.
        shell.secondary(); assertEquals(Destination.ConsoleCore(system), shell.top)
        shell.back(); assertEquals(Destination.Systems, shell.top)
        // A: the shelf, on that console's chip.
        shell.select(); runCurrent()
        assertNull(shell.top); assertEquals(dev.cartridge.library.Shelf.Console(system), console.state.shelf)
        console.coordinator.destroy()
    }

    @Test fun theShouldersStepThroughTheChipsInTheSpecsOrder() = runTest {
        val console = Console(this); val shell = console.shell
        val chips = shell.chips
        // Recently Played and Favorites come first when they show anything, All Games next, then each console.
        assertEquals(chips.filterNot { it is Shelf.Console }, chips.take(chips.count { it !is Shelf.Console }))
        val start = chips.indexOf(console.state.shelf)
        shell.shoulder(1); runCurrent()
        assertEquals(chips[(start + 1) % chips.size], console.state.shelf)
        shell.shoulder(-1); shell.shoulder(-1); runCurrent()
        assertEquals(chips[Math.floorMod(start - 1, chips.size)], console.state.shelf)
        console.coordinator.destroy()
    }

    @Test fun gameDetailMovesBetweenItsActionsAndItsMediaWithTheDpad() = runTest {
        val console = Console(this); val shell = console.shell
        shell.alternate(); assertEquals(Destination.Details, shell.top)
        // A game never started and without save states offers Play, the star and More; nothing to resume or start over.
        val page = shell.page()!!
        assertEquals(listOf("primary", "favorite", "more"), page.rows.map { it.id }); assertEquals("Play", page.rows.first().title)
        assertEquals("primary", shell.focused(page)?.id)
        // Without media there is no strip to move down to.
        shell.navigate(1); assertEquals(0, shell.detailRow)
        // With two items: down to the strip, along it and no further, back up to the actions where focus was.
        shell.detailMediaCount = 2
        shell.navigate(1); assertEquals(1, shell.detailRow)
        shell.adjust(1); shell.adjust(1); assertEquals(1, shell.detailShot)
        shell.navigate(-1); assertEquals(0, shell.detailRow); assertEquals("primary", shell.focused(shell.page()!!)?.id)
        shell.adjust(1); assertEquals("favorite", shell.focused(shell.page()!!)?.id)
        // R3 is the trailer's sound here, X the game's settings, B back to the shelf.
        assertFalse(shell.trailerSound); shell.stick(1); assertTrue(shell.trailerSound)
        shell.alternate(); assertEquals(Destination.Options, shell.top)
        shell.back(); assertEquals(Destination.Details, shell.top)
        // More holds Game settings and Manage game.
        shell.adjust(1); shell.select(); assertEquals(Destination.GameMore, shell.top)
        assertTrue(shell.page()!!.rows.map { it.id }.containsAll(listOf("options", "manage")))
        shell.back(); shell.back(); assertNull(shell.top)
        // Opening it again starts at its first action, with the trailer muted.
        shell.alternate(); assertEquals(0, shell.detailRow); assertFalse(shell.trailerSound)
        console.coordinator.destroy()
    }

    @Test fun androidBackKeepsItsMeaningAndNeverOpensApps() = runTest {
        val console = Console(this); val shell = console.shell
        // At the library's root it has nowhere to go, exactly as before this feature.
        shell.systemBack(); assertNull(shell.top)
        // Everywhere else it closes what the controller's button closes, Apps included.
        shell.menu(); shell.systemBack(); assertNull(shell.top)
        shell.openTab(Tab.Apps); assertEquals(Destination.Apps, shell.top)
        shell.systemBack(); assertNull(shell.top)
        console.coordinator.query(console.state.query.copy(search = "third")); runCurrent()
        shell.systemBack(); runCurrent(); assertFalse(console.state.searching); assertNull(shell.top)
        console.coordinator.destroy()
    }

    @Test fun aGameKeepsItsOwnBackButtonRunningAndPaused() = runTest {
        val console = Console(this); val shell = console.shell
        console.coordinator.insert(); runCurrent()
        assertEquals(SessionState.Running, console.engine.status.value.state)
        // In this mode the router hands B to the game and never asks the shell what it means.
        assertEquals(InputMode.Gameplay, shell.inputMode())
        // Even asked directly, the shell has no Apps to offer over a running game.
        shell.back(); shell.openApps()
        assertNull(shell.top); assertEquals(SessionState.Running, console.engine.status.value.state); assertEquals(CollectionScreen.Emulator, console.state.screen)
        // Paused, B resumes, as it always has.
        console.coordinator.pause(); runCurrent()
        assertEquals(SessionState.Paused, console.engine.status.value.state); assertEquals(InputMode.Menu, shell.inputMode())
        shell.openApps(); assertNull(shell.top)
        shell.back(); runCurrent()
        assertEquals(SessionState.Running, console.engine.status.value.state); assertNull(shell.top)
        console.coordinator.destroy()
    }

    @Test fun aFrozenGameHasNoMenuAndItsButtonsStayTheCombos() = runTest {
        val console = Console(this); val shell = console.shell
        console.coordinator.insert(); runCurrent()
        console.coordinator.toggleFreeze(); runCurrent()
        assertTrue(console.engine.status.value.frozen)
        // No pause page is drawn over it and the router keeps the game's mode, where only the combos and Back mean anything.
        assertNull(shell.page()); assertNull(shell.top); assertEquals(InputMode.Gameplay, shell.inputMode())
        // Back opens the pause menu over the same frame.
        console.coordinator.pause(); runCurrent()
        assertEquals("pause", shell.page()?.key); assertEquals(InputMode.Menu, shell.inputMode())
        console.coordinator.destroy()
    }

    @Test fun fourCombosAreRecordedAndNoneMayPressAnother() = runTest {
        val console = Console(this); val shell = console.shell
        assertEquals(QuickCombo.entries.associateWith { it.default }, shell.controls.combos)
        shell.recordCombo(QuickCombo.Freeze); assertEquals(InputMode.Record, shell.inputMode())
        // L3 + Y is the quick save's: refused, and still listening.
        shell.recordKey(KeyEvent.KEYCODE_BUTTON_THUMBL, true); shell.recordKey(KeyEvent.KEYCODE_BUTTON_Y, true); shell.recordKey(KeyEvent.KEYCODE_BUTTON_THUMBL, false)
        assertTrue(shell.top is Destination.RecordCombo); assertNotNull(shell.recorder?.problem)
        shell.recordKey(KeyEvent.KEYCODE_BUTTON_Y, false)
        shell.recordKey(KeyEvent.KEYCODE_BUTTON_SELECT, true); shell.recordKey(KeyEvent.KEYCODE_BUTTON_L1, true); shell.recordKey(KeyEvent.KEYCODE_BUTTON_SELECT, false)
        assertNull(shell.top)
        val freeze = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(freeze, shell.controls.combo(QuickCombo.Freeze))
        assertEquals(freeze, ConsolePreferences(console.preferences).read().combo(QuickCombo.Freeze))
        assertEquals("Freeze: L1 + Select", console.state.message)
        // The speed steps within 2x to 8x and is kept; Reset quick buttons gives all four combos back and leaves the speed.
        fun speed() = shell.controlRows(console.state).first { it.id == "fast-forward-speed" }
        assertEquals("2×", speed().value)
        speed().adjust!!(-1); assertEquals(2, shell.controls.fastForwardSpeed)
        repeat(9) { speed().adjust!!(1) }
        assertEquals(8, shell.controls.fastForwardSpeed); assertEquals(8, ConsolePreferences(console.preferences).read().fastForwardSpeed)
        shell.resetQuickButtons()
        assertEquals(QuickCombo.entries.associateWith { it.default }, shell.controls.combos); assertEquals(8, shell.controls.fastForwardSpeed)
        console.coordinator.destroy()
    }

    @Test fun appsAreBrowsedSearchedAndStartedThroughTheShellsOwnRoles() = runTest {
        val console = Console(this); val shell = console.shell; val apps = shell.apps
        shell.openTab(Tab.Apps); runCurrent()
        apps.columns = 2 // Atlas, Field Notes / Harbor Mail, Tide Clock
        assertEquals("Atlas", apps.focused!!.label)
        shell.adjust(1); assertEquals("Field Notes", apps.focused!!.label)
        shell.navigate(1); assertEquals("Tide Clock", apps.focused!!.label)
        shell.adjust(-1); shell.navigate(-1); assertEquals("Atlas", apps.focused!!.label)
        // Buttons that belong to the library do nothing here.
        shell.menu(); shell.alternate(); shell.shoulder(1)
        assertEquals(Destination.Apps, shell.top); assertEquals("Atlas", apps.focused!!.label)
        // A starts the focused app as a home screen would, and the console stays where it is behind it.
        shell.navigate(1); shell.select()
        assertEquals(listOf("example.app3/Main" to false), console.source.launched) // Reduced motion: no transition asked for.
        assertEquals(Destination.Apps, shell.top); assertEquals("Harbor Mail", apps.focused!!.label)
        assertEquals("example.app3/Main", console.preferences.getString("appsFocus", null))
        // Y searches with the console's own keyboard; B then clears the search before it leaves.
        shell.secondary(); assertTrue(shell.top is Destination.TextEntry)
        "tide".forEach(shell::type); shell.finishText()
        assertEquals(Destination.Apps, shell.top); assertEquals(listOf("Tide Clock"), apps.entries.map { it.label })
        shell.back(); assertEquals(Destination.Apps, shell.top); assertFalse(apps.searching); assertEquals(4, apps.entries.size)
        assertEquals("Tide Clock", apps.focused!!.label)
        // A cancelled search changes nothing.
        shell.secondary(); "zzz".forEach(shell::type); shell.back()
        assertEquals(Destination.Apps, shell.top); assertFalse(apps.searching)
        shell.back(); assertNull(shell.top)
        // The app left focused is the app found focused next time, in this session and the next.
        assertEquals("example.app0/Main", console.preferences.getString("appsFocus", null))
        shell.openTab(Tab.Apps); runCurrent(); assertEquals("Tide Clock", apps.focused!!.label)
        val reopened = Console(this, console.preferences)
        reopened.shell.openTab(Tab.Apps); runCurrent(); assertEquals("Tide Clock", reopened.shell.apps.focused!!.label)
        console.coordinator.destroy(); reopened.coordinator.destroy()
    }

    @Test fun anAppThatCannotBeStartedIsExplainedAndTheListIsReadAgain() = runTest {
        val console = Console(this); val shell = console.shell; val apps = shell.apps
        shell.openTab(Tab.Apps); runCurrent(); shell.navigate(1); shell.adjust(1)
        val focused = apps.focused!!; val reads = console.source.reads
        // Uninstalled by another app while its icon was still on screen.
        console.source.installed.remove(focused)
        shell.select(); runCurrent()
        assertTrue(console.source.launched.isEmpty())
        val message = console.state.message!!
        assertTrue(message.startsWith(focused.label)); assertTrue(message.contains("couldn't be opened"))
        // Said in the player's words: no package, no class, no exception.
        assertFalse(message.contains("example.")); assertFalse(message.contains("Exception"))
        assertEquals(reads + 1, console.source.reads); assertFalse(apps.entries.contains(focused))
        // Focus has a valid neighbour and Apps is still open and usable.
        assertNotNull(apps.focused); assertEquals(Destination.Apps, shell.top)
        shell.select(); assertEquals(1, console.source.launched.size)
        // Nothing installed at all, then a failed read: A asks again instead of doing nothing.
        console.source.installed.clear(); console.source.report!!(null); runCurrent()
        assertTrue(apps.entries.isEmpty()); val before = console.source.reads
        shell.select(); runCurrent(); assertEquals(before + 1, console.source.reads)
        shell.secondary(); assertEquals(Destination.Apps, shell.top)
        shell.back(); assertNull(shell.top)
        console.coordinator.destroy()
    }

    @Test fun returningToTheConsoleReadsTheAppsAgainOnlyOnceTheyHaveBeenShown() = runTest {
        val console = Console(this); val shell = console.shell
        // Never opened: coming back to the foreground costs nothing.
        shell.resumed(); runCurrent(); assertEquals(0, console.source.reads); assertEquals(AppLibrary.Phase.Unread, shell.apps.phase)
        shell.openTab(Tab.Apps); runCurrent(); assertEquals(1, console.source.reads); assertTrue(console.source.watching)
        // Another app installed something while the console was behind it.
        console.source.installed += dev.cartridge.apps.AppEntry("example.new", "Main", "Beacon")
        shell.resumed(); runCurrent()
        assertEquals(2, console.source.reads); assertTrue(shell.apps.entries.any { it.label == "Beacon" })
        assertEquals(Destination.Apps, shell.top) // Still where the player left it.
        shell.close(); assertFalse(console.source.watching)
        console.coordinator.destroy()
    }

    @Test fun aRecreatedConsoleReturnsToAppsAndTheHintRetiresOnItsOwnCount() = runTest {
        val preferences = Preferences()
        val console = Console(this, preferences)
        assertTrue(console.shell.appsHintVisible)
        repeat(ConsoleShell.LEARNED_AFTER) { console.shell.openTab(Tab.Apps); console.shell.back() }
        assertNull(console.shell.top); assertFalse(console.shell.appsHintVisible)
        assertEquals(ConsoleShell.LEARNED_AFTER, preferences.getInt("appsOpened", 0))
        console.shell.changeHints(HintMode.Always); assertTrue(console.shell.appsHintVisible)
        console.shell.changeHints(HintMode.Hidden); assertFalse(console.shell.appsHintVisible)
        console.coordinator.destroy()
        // Android destroyed the Activity while Apps was open and brought it back with its saved state.
        val engine = Engine(); val library = Library(); val host = Host(); val source = FixtureApps("Atlas", "Beacon")
        val coordinator = CollectionCoordinator(engine, library, object : RendererHandoff {
            override fun releaseLibrary() = Unit
            override fun releaseEmulator() = Unit
        }, backgroundScope, reducedMotion = true)
        val labels = LabelStore(files.newFolder())
        val scraper = ScrapeCoordinator(NoArtwork, library, labels, files.newFolder(), backgroundScope) { _, _ -> }
        val shell = ConsoleShell(object : ContextWrapper(null) {}, backgroundScope, host, coordinator, engine, library, scraper, labels,
            SessionStore(files.newFolder()), ScrapePackStore(files.newFolder()), preferences, files.newFolder(), source, StandardTestDispatcher(testScheduler))
        shell.reopenApps = true
        backgroundScope.launch { combine(coordinator.state, engine.status, scraper.state) { state, status, _ -> state to status }.collect { (state, status) -> shell.sync(state, status) } }
        runCurrent()
        assertEquals(Destination.Apps, shell.top); assertFalse(shell.reopenApps)
        runCurrent(); assertEquals(2, shell.apps.entries.size)
        // Once only: leaving Apps afterwards is not undone by the next library change.
        shell.back(); coordinator.rendererReady(0); coordinator.navigate(1); runCurrent()
        assertNull(shell.top)
        coordinator.destroy()
    }

    @Test fun eachConsoleKeepsItsOwnGameButtonsAndTheMenusKeepTheirs() = runTest {
        val preferences = Preferences()
        val console = Console(this, preferences); val shell = console.shell
        fun stored() = ConsolePreferences(preferences).read()
        // Controls lists every console of the registry, each on its default; the every-console swaps are gone.
        val rows = shell.controlRows(console.state)
        assertEquals(GameSystem.entries.map { "console:${it.name}" }, rows.filter { it.id.startsWith("console:") }.map { it.id })
        assertTrue(rows.none { it.id == "ab" || it.id == "lr" }); assertTrue(rows.filter { it.id.startsWith("console:") }.all { it.value == "Default" })
        shell.push(Destination.ConsoleButtons(GameSystem.SATURN))
        val page = shell.page()!!
        assertEquals(GameSystem.SATURN.pad.map { it.name }, page.rows.filter { it.id.startsWith("button:") }.map { it.title })
        assertFalse(page.rows.first { it.id == "default" }.enabled)
        // Choosing Saturn A listens for one physical button, and every controller button goes to the listener.
        shell.activate(page, page.rows.first { it.id == "button:B" })
        assertTrue(shell.top is Destination.RecordButton); assertEquals(InputMode.Record, shell.inputMode())
        shell.recordKey(KeyEvent.KEYCODE_DPAD_UP, true); shell.recordKey(KeyEvent.KEYCODE_DPAD_UP, false)
        assertNotNull(shell.buttonRecorder?.problem); assertTrue(shell.top is Destination.RecordButton)
        shell.recordKey(KeyEvent.KEYCODE_BUTTON_X, true); assertTrue("It is assigned when let go", shell.top is Destination.RecordButton)
        shell.recordKey(KeyEvent.KEYCODE_BUTTON_X, false)
        assertEquals(Destination.ConsoleButtons(GameSystem.SATURN), shell.top)
        // Saturn Y, which had the X button, trades places with A. No other console is touched.
        var saturn = stored().buttons(GameSystem.SATURN)
        assertEquals(PadKey.X, saturn[PadKey.B]); assertEquals(PadKey.B, saturn[PadKey.X])
        assertEquals(setOf(GameSystem.SATURN), stored().buttons.keys); assertEquals(ButtonMap.default(GameSystem.GBA), stored().buttons(GameSystem.GBA))
        assertEquals("Custom", shell.controlRows(console.state).first { it.id == "console:SATURN" }.value)
        // Right on the focused row steps A on to the next button, trading again; no two buttons ever share one.
        shell.adjust(1); saturn = stored().buttons(GameSystem.SATURN)
        assertEquals(PadKey.Y, saturn[PadKey.B]); assertEquals(saturn.size, saturn.values.toSet().size)
        // The menus' confirm button is its own setting and leaves every console's game buttons as they are.
        shell.changeControls(shell.controls.copy(swapMenuButtons = true)); assertEquals(saturn, stored().buttons(GameSystem.SATURN))
        // The swaps these replaced are dropped, not converted.
        preferences.values["swapGameButtons"] = true; preferences.values["swapShoulders"] = true
        shell.changeControls(shell.controls); assertFalse("swapGameButtons" in preferences.values || "swapShoulders" in preferences.values)
        // Use default buttons forgets the console's map; the console then follows its default.
        shell.activate(shell.page()!!, shell.page()!!.rows.first { it.id == "default" })
        assertNull(preferences.getString(ConsolePreferences.buttonsKey(GameSystem.SATURN), null)); assertTrue(stored().buttons.isEmpty())
        // Five quiet seconds keep the button that was there.
        shell.recordButton(GameSystem.GB, PadKey.A); assertTrue(shell.top is Destination.RecordButton)
        advanceTimeBy(ConsoleShell.RECORD_TIMEOUT_MS + 1); runCurrent()
        assertEquals(Destination.ConsoleButtons(GameSystem.SATURN), shell.top); assertTrue(stored().buttons.isEmpty())
        // Reset controls gives every console its defaults again.
        shell.assignButton(GameSystem.GENESIS, PadKey.Y, PadKey.B); assertEquals(setOf(GameSystem.GENESIS), stored().buttons.keys)
        shell.home(); shell.activate(Page("controls", "Controls", shell.controlRows(console.state), PageStyle.Page), shell.controlRows(console.state).first { it.id == "reset" })
        assertTrue(stored().buttons.isEmpty())
        console.coordinator.destroy()
    }
}
