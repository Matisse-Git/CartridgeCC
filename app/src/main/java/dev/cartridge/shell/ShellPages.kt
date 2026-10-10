package dev.cartridge.shell

import android.text.format.DateUtils
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.environment.CoreFamily
import dev.cartridge.environment.GameOptions
import dev.cartridge.library.LabelFit
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.LibrarySort
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.CollectionState
import dev.cartridge.session.LaunchFailure
import dev.cartridge.ui.Accent
import dev.cartridge.ui.ClockStyle

internal fun playLabel(ms: Long): String = when {
    ms < 60_000 -> "Not played yet"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
}
internal fun whenLabel(time: Long): String = if (time <= 0) "Never" else if (System.currentTimeMillis() - time in 0 until 60_000) "just now" else
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private fun <T> List<T>.step(current: T, delta: Int): T = this[Math.floorMod(indexOf(current).coerceAtLeast(0) + delta, size)]

/** Every destination's rows, derived from current state. Nothing here stores anything. */
internal fun ConsoleShell.buildPage(state: CollectionState, status: EngineStatus, destination: Destination? = top): Page? {
    val paused = status.state == SessionState.Paused
    val game = state.selectedGame
    return when (destination) {
        null -> when {
            state.screen == CollectionScreen.LabelPreview -> labelPage(state)
            paused -> pausePage(state)
            else -> null
        }
        Destination.SystemMenu -> systemMenu(state)
        Destination.Settings -> Page("settings:${category.name}", "Settings", settingsRows(state, category), PageStyle.Settings)
        Destination.Controls -> Page("controls", "Controls", controlRows(state), PageStyle.Page, "Menus and games keep separate button choices.")
        is Destination.Folder -> folderPage(destination.uri)
        Destination.Hidden -> Page("hidden", "Hidden games", hidden.map { entry ->
            MenuRow("hidden:${entry.id}", entry.title, value = "Restore", note = entry.system.title, enabled = !state.busy) {
                coordinator.restoreHidden(entry.id); reloadHidden()
            }
        }.ifEmpty { listOf(MenuRow("none", "Nothing is hidden", RowKind.Info, note = "Games you hide from their details appear here.")) },
            PageStyle.Page, "Hidden games keep their file, saves and artwork.")
        Destination.About -> aboutPage()
        is Destination.Notice -> Page("notice", destination.name.removeSuffix(".txt"), emptyList(), PageStyle.Reader, body = noticeText)
        Destination.BackupReview -> Page("backup", "Restore from backup", listOf(
            MenuRow("merge", "Add to my collection", enabled = !state.busy, tag = "backup-merge") { mergeBackup() },
            MenuRow("settings", "Also restore settings", RowKind.Toggle, checked = restorePreferences, tag = "backup-settings", adjust = { toggleRestorePreferences() }),
            MenuRow("cancel", "Cancel", tag = "backup-cancel") { pop() }), PageStyle.Page, backupSummary,
            "Your current artwork, details, library and saves are kept. Anything missing is filled in from the backup, and play history keeps the larger value.\n\n" +
                "Game files and folder permissions are not part of a backup. Add your game folders again on a new device.")
        Destination.Details -> game?.let { detailsPage(it, state) }
        Destination.Manage -> game?.let { managePage(it, state) }
        Destination.Options -> game?.let { optionsPage(it, paused) }
        Destination.EditDetails -> Page("edit", "Edit details", listOf("title", "year", "developer", "publisher", "genre", "description").map { field ->
            MenuRow("field:$field", field.replaceFirstChar(Char::uppercase), RowKind.Link, tag = "field-$field",
                value = draft[field].orEmpty().ifBlank { "Not set" }.let { if (it.length > 44) it.take(42) + "…" else it }) { editField(field) }
        } + MenuRow("save", "Save changes", enabled = draftChanged && !state.busy, tag = "metadata-save") { saveDetails() },
            PageStyle.Page, game?.title)
        is Destination.TextEntry -> Page("text", destination.spec.title, emptyList(), PageStyle.Keyboard, destination.spec.hint)
        Destination.Import -> importPage()
        Destination.LaunchFailure -> state.launchFailure?.let(::failurePage)
    }
}

private fun ConsoleShell.systemMenu(state: CollectionState): Page {
    val games = state.allGames
    val resume = state.resumeSession?.let { saved -> games.firstOrNull { it.id == saved.id } }
    val consoles = games.map { it.system }.distinct().size
    val ready = state.loaded && !state.busy
    return Page("menu", "Menu", buildList {
        if (resume != null) add(MenuRow("resume", "Continue ${resume.title}", note = "Paused where you left it", enabled = ready, tag = "system-resume") { home(); coordinator.resumeSavedSession() })
        if (state.removedEntry != null) add(MenuRow("undo-hide", "Restore hidden game", note = "Bring back the game you just hid", enabled = ready, tag = "system-undo-hide") { home(); coordinator.undoHide() })
        add(MenuRow("add", "Add Games", enabled = ready, tag = "system-add") { addGames() })
        if (games.isNotEmpty()) {
            add(MenuRow("search", "Search", value = state.query.search.takeIf(String::isNotBlank), enabled = ready, tag = "system-search") { search() })
            add(MenuRow("artwork", "Artwork & Info", enabled = ready, tag = "system-artwork") { openScraper() })
        }
        if (roots.isNotEmpty()) add(MenuRow("refresh", "Refresh Library", enabled = ready, tag = "system-refresh") { refreshLibrary() })
        add(MenuRow("settings", "Settings", RowKind.Link, tag = "system-settings") { push(Destination.Settings) })
    }, PageStyle.Sheet, if (games.isEmpty()) null else "${plural(games.size, "game")} · ${plural(consoles, "console")}")
}

private fun ConsoleShell.pausePage(state: CollectionState): Page {
    val game = state.selectedGame
    val known = facts?.takeIf { it.first == game?.id }?.second
    val ready = !state.busy
    return Page("pause", game?.title ?: "Paused", listOf(
        MenuRow("resume", "Resume", enabled = ready, tag = "menu-0") { coordinator.menuAction(0) },
        MenuRow("save", "Save State", enabled = ready, tag = "menu-1") { coordinator.menuAction(1) },
        MenuRow("load", "Load State", value = known?.stateAt?.takeIf { it > 0 }?.let { "Saved ${whenLabel(it)}" },
            note = if (known != null && known.stateAt <= 0) "Nothing saved yet" else null, enabled = ready && (known == null || known.stateAt > 0), tag = "menu-2") { coordinator.menuAction(2) },
        MenuRow("options", "Game Options", RowKind.Link, enabled = ready, tag = "menu-4") { push(Destination.Options) },
        MenuRow("eject", "Return to Library", enabled = ready, tag = "menu-3") { coordinator.menuAction(3) }),
        PageStyle.Pause, game?.system?.title)
}

private fun ConsoleShell.labelPage(state: CollectionState): Page {
    val label = state.labelDraft?.label
    val ready = !state.busy
    return Page("label", "Label preview", listOf(
        MenuRow("fit", "Sizing", RowKind.Choice, value = if (label?.fit == LabelFit.Crop) "Fill the label" else "Show whole image", enabled = ready,
            tag = "label-action-0", adjust = { coordinator.labelAction(0) }),
        MenuRow("rotate", "Rotate", value = "${(label?.quarterTurns ?: 0) * 90}°", enabled = ready, tag = "label-action-1") { coordinator.labelAction(1) },
        MenuRow("apply", "Use this label", note = if (state.labelPreviewReady) null else "Preparing the preview…", enabled = ready && state.labelPreviewReady,
            tag = "label-action-2") { coordinator.labelAction(2) },
        MenuRow("cancel", "Cancel", enabled = ready, tag = "label-action-3") { coordinator.labelAction(3) }),
        PageStyle.Sheet, state.selectedGame?.title)
}

private fun ConsoleShell.detailsPage(game: LibraryGame, state: CollectionState): Page {
    val known = facts?.takeIf { it.first == game.id }?.second
    val ready = !state.busy
    return Page("details", game.title, buildList {
        if (known?.cachedRom == false) add(MenuRow("locate", "Reconnect game file", note = "Cartridge can't find this game's file. Choose the same file again.",
            warn = true, enabled = ready, tag = "details-locate") { locateGame() })
        add(MenuRow("play", "Play", enabled = ready && state.rendererReady, tag = "details-play") { play() })
        add(MenuRow("favorite", if (game.favorite) "Remove from Favorites" else "Add to Favorites", enabled = ready, tag = "details-favorite") { coordinator.favorite() })
        add(MenuRow("options", "Game options", RowKind.Link, tag = "details-options") { push(Destination.Options) })
        add(MenuRow("manage", "Manage game", RowKind.Link, tag = "details-manage") { push(Destination.Manage) })
    }, PageStyle.Details, listOfNotNull(game.system.title, game.metadata["year"]?.value?.takeIf(String::isNotBlank),
        game.metadata["developer"]?.value?.takeIf(String::isNotBlank)).joinToString(" · "), game.metadata["description"]?.value?.takeIf(String::isNotBlank))
}

private fun ConsoleShell.managePage(game: LibraryGame, state: CollectionState): Page {
    val ready = !state.busy
    return Page("manage", "Manage game", buildList {
        add(MenuRow("scrape", "Find artwork online", note = "Labels and details from RetroBase", enabled = ready, tag = "manage-scrape") { openScraper(single = true) })
        add(MenuRow("image", "Use my own image", enabled = ready, tag = "manage-image") { chooseLabel() })
        add(MenuRow("edit", "Edit details", RowKind.Link, enabled = ready, tag = "manage-edit") { editDetails() })
        if (game.canUndoEnrichment) add(MenuRow("undo", "Undo last artwork change", enabled = ready, tag = "manage-undo") { coordinator.undoEnrichment() })
        if (game.label != null) add(MenuRow("reset", "Remove label", note = "Returns to the plain printed label", enabled = ready, tag = "manage-reset") { coordinator.removeLabel() })
        add(MenuRow("locate", "Locate game file", note = "If the file moved, choose it again", enabled = ready, tag = "manage-locate") { locateGame() })
        add(MenuRow("hide", "Hide from library", RowKind.Danger, note = "Keeps the game file, saves and artwork", enabled = ready, tag = "manage-hide") { hide() })
    }, PageStyle.Sheet, game.title)
}

private fun ConsoleShell.optionsPage(game: LibraryGame, paused: Boolean): Page {
    optionsRevision // Subscribes this page to stored option changes.
    val family = CoreFamily.of(game.system)
    val console = GameOptions.resolve(game.system, options.console(family))
    val overrides = options.game(game.id)
    return Page("options", "Game options", buildList {
        console.forEach { (option, consoleValue) ->
            val override = overrides[option.id]?.takeIf { value -> option.choices.any { it.value == value } }
            val cycle: List<String?> = listOf<String?>(null) + option.choices.map { it.value }
            add(MenuRow("option:${option.id}", option.title, RowKind.Choice, value = option.title(override ?: consoleValue),
                note = when {
                    override == null -> "Console setting"
                    paused && !option.live -> "Only this game · from its next start"
                    else -> "Only this game"
                }, tag = "option-${option.id}", adjust = { delta -> setGameOption(game, option, cycle.step(override, delta)) }))
        }
        add(MenuRow("reset", "Use console settings", enabled = overrides.isNotEmpty(), tag = "option-reset") { clearGameOptions(game) })
        if (paused) add(MenuRow("controls", "Controls", RowKind.Link, tag = "option-controls") { push(Destination.Controls) })
    }, PageStyle.Sheet, "${game.title} · ${family.title}")
}

private fun ConsoleShell.folderPage(uri: String): Page {
    val root = roots.firstOrNull { it.uri == uri }
    val unreachable = ConsoleShell.unreachable(root?.error)
    return Page("folder", root?.name?.ifBlank { null } ?: "Game folder", buildList {
        if (unreachable != null) add(MenuRow("reconnect", "Choose this folder again", note = "Restores Cartridge's access to it", warn = true) { addGames() })
        add(MenuRow("refresh", "Refresh this folder", note = "Looks for games added since ${whenLabel(root?.lastScan ?: 0)}") { refreshFolder(uri) })
        add(MenuRow("forget", "Stop using this folder", RowKind.Danger, note = "Its games stay in your library") { forgetFolder(uri) })
    }, PageStyle.Dialog, unreachable ?: root?.error?.let { "Some files were skipped last time: ${ConsoleShell.plain(it)}" })
}

private fun ConsoleShell.aboutPage(): Page {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    return Page("about", "About Cartridge", notices.mapIndexed { index, name ->
        MenuRow("notice:$name", name.removeSuffix(".txt").replace('-', ' ').replace('_', ' '), RowKind.Link, tag = "notice-$index") { push(Destination.Notice(name)) }
    }.ifEmpty { listOf(MenuRow("none", "Licence notices are unavailable in this build", RowKind.Info)) }, PageStyle.Page, "Version $version",
        "Games run inside Cartridge with mGBA 0.10.5 and PCSX ReARMed, through LibretroDroid 0.14.0. The collection is drawn with Filament 1.72.1.\n\n" +
            "Your game files, saves and artwork stay on this device and under your control.")
}

private fun ConsoleShell.importPage(): Page {
    val flow = import
    return Page("import", "Adding games", when (flow) {
        is ImportFlow.Looking, is ImportFlow.Adding -> listOf(MenuRow("stop", "Stop", note = "Games already added are kept", tag = "cancel-scan") { coordinator.cancelScan() })
        is ImportFlow.Done -> buildList {
            if (flow.needsBios) add(MenuRow("bios", "Add PlayStation BIOS", note = "Most PlayStation games need it to start", tag = "import-bios") { chooseBios() })
            if (flow.result.added == 0 && flow.result.duplicates == 0) add(MenuRow("again", "Choose another folder", tag = "import-again") { addGames() })
            add(MenuRow("done", if (flow.result.added > 0) "See my games" else "Done", tag = "import-done") { pop() })
        }
        is ImportFlow.Failed -> listOf(MenuRow("again", "Choose a folder", tag = "import-again") { addGames() }, MenuRow("close", "Close", tag = "import-done") { pop() })
        null -> emptyList()
    }, PageStyle.Dialog)
}

private fun ConsoleShell.failurePage(failure: LaunchFailure): Page {
    val reason = failure.reason
    val missing = listOf("missing", "changed", "Permission", "unavailable", "ENOENT", "No such file", "Choose the same", "No content provider", "FileNotFound", "open failed", "content://").any { it in reason }
    val body = when {
        missing -> "Cartridge can't reach this game's file. If it is on an SD card, check the card is inserted, or choose the file again."
        "code" in reason || "did not finish loading" in reason || "did not start" in reason ->
            "The game did not start. Its file may be damaged or not a ${failure.system.title} game." +
                if (failure.system == GameSystem.PS1 && !biosReady) " PlayStation games also often need a BIOS file." else ""
        else -> ConsoleShell.plain(reason)
    }
    return Page("failure", "${failure.title} didn't start", buildList {
        add(MenuRow("retry", "Try again", tag = "failure-retry") { pop(); coordinator.insert() })
        // Offer the repair that fits the cause: a file that cannot be reached is not a firmware problem.
        if (missing) add(MenuRow("locate", "Locate game file", tag = "failure-locate") { pop(); locateGame() })
        else if (failure.system == GameSystem.PS1 && !biosReady) add(MenuRow("bios", "Add PlayStation BIOS", tag = "failure-bios") { chooseBios() })
        add(MenuRow("close", "Back to library", tag = "failure-close") { pop() })
    }, PageStyle.Dialog, "Your saves and artwork are safe.", body)
}

internal fun ConsoleShell.controlRows(state: CollectionState): List<MenuRow> {
    val sensitivity = listOf(.5f to "Low", .35f to "Medium", .2f to "High")
    fun flip(title: String, id: String, on: Boolean, off: String, onTitle: String, tag: String = "controls-$id", set: (Boolean) -> Unit) =
        MenuRow(id, title, RowKind.Choice, value = if (on) onTitle else off, tag = tag, adjust = { set(!on) })
    return listOf(
        flip("Confirm button", "confirm", controls.swapMenuButtons, "A  ·  B goes back", "B  ·  A goes back") { changeControls(controls.copy(swapMenuButtons = it)) },
        flip("In-game A and B", "ab", controls.swapGameButtons, "As labelled", "Swapped") { changeControls(controls.copy(swapGameButtons = it)) },
        flip("In-game L and R", "lr", controls.swapShoulders, "As labelled", "Swapped") { changeControls(controls.copy(swapShoulders = it)) },
        MenuRow("stick", "Stick sensitivity", RowKind.Choice, value = sensitivity.minBy { kotlin.math.abs(it.first - controls.deadZone) }.second, tag = "controls-stick",
            adjust = { delta -> changeControls(controls.copy(deadZone = sensitivity.map { it.first }.step(sensitivity.minBy { kotlin.math.abs(it.first - controls.deadZone) }.first, delta))) }),
        flip("Pause shortcut", "pause", state.alternateShortcut, "L3 + R3", "Start + Select", tag = "shortcut-setting") { setPauseShortcut(it) },
        MenuRow("reset", "Reset controls", tag = "controls-reset") { changeControls(controls.copy(swapMenuButtons = false, swapGameButtons = false, swapShoulders = false, deadZone = .35f)); setPauseShortcut(false) })
}

internal fun ConsoleShell.settingsRows(state: CollectionState, category: SettingsCategory): List<MenuRow> {
    optionsRevision
    val ready = state.loaded && !state.busy
    fun header(title: String) = MenuRow("header:$title", title, RowKind.Header)
    fun toggle(id: String, title: String, checked: Boolean, note: String? = null, enabled: Boolean = true, tag: String = "settings-$id", set: (Boolean) -> Unit) =
        MenuRow(id, title, RowKind.Toggle, note = note, checked = checked, enabled = enabled, tag = tag, adjust = { set(!checked) })
    fun optionRows(family: CoreFamily) = GameOptions.forFamily(family).map { option ->
        val value = GameOptions.resolve(if (family == CoreFamily.PlayStation) GameSystem.PS1 else GameSystem.GBA, options.console(family)).getValue(option)
        MenuRow("option:${option.id}", option.title, RowKind.Choice, value = option.title(value),
            note = "${family.title}: " + option.help + if (option.live) "" else " Applies from a game's next start.", tag = "option-${option.id}",
            adjust = { delta -> setConsoleOption(option, option.next(value, delta)) })
    }
    return when (category) {
        SettingsCategory.Library -> buildList {
            add(MenuRow("add", "Add games", note = "Choose a folder on this device or an SD card", enabled = ready, tag = "settings-add") { addGames() })
            add(MenuRow("add-file", "Add a single game file", enabled = ready, tag = "settings-add-file") { addSingleGame() })
            if (roots.isNotEmpty()) {
                add(MenuRow("refresh", "Refresh library", note = "Looks for new games in your folders", enabled = ready, tag = "settings-refresh") { refreshLibrary() })
                add(toggle("autoscan", "Refresh when Cartridge opens", autoScan) { changeAutoScan(it) })
                add(header("Game folders"))
                roots.forEach { root ->
                    val unreachable = ConsoleShell.unreachable(root.error)
                    add(MenuRow("folder:${root.uri}", root.name.ifBlank { "Game folder" }, RowKind.Link, warn = unreachable != null,
                        note = unreachable ?: "Checked ${whenLabel(root.lastScan)}" + if (root.error != null) " · some files were skipped" else "") { push(Destination.Folder(root.uri)) })
                }
            }
            add(header("Browsing"))
            add(MenuRow("sort", "Sort games by", RowKind.Choice, value = state.query.sort.title, enabled = ready, tag = "settings-sort",
                adjust = { delta -> coordinator.query(state.query.copy(sort = LibrarySort.entries.step(state.query.sort, delta))) }))
            add(MenuRow("hidden", "Hidden games", RowKind.Link, tag = "settings-hidden") { push(Destination.Hidden) })
            add(header("Backup"))
            add(MenuRow("export", "Back up collection", note = "Saves, artwork and details. Game files are not included.", enabled = ready, tag = "settings-export") { exportBackup() })
            add(MenuRow("restore", "Restore from backup", enabled = ready, tag = "settings-restore") { restoreBackup() })
        }
        SettingsCategory.Appearance -> listOf(
            MenuRow("layout", "Layout", RowKind.Choice, value = state.layout.title, enabled = !state.busy, tag = "layout-setting",
                adjust = { delta -> setLayout(CollectionLayout.entries.step(state.layout, delta)) }),
            MenuRow("motion", "Motion", RowKind.Choice, value = if (state.reducedMotion) "Reduced" else "Full", enabled = !state.busy, tag = "motion-setting",
                note = "Reduced removes movement between games and during insertion.", adjust = { setReducedMotion(!state.reducedMotion) }),
            MenuRow("accent", "Accent colour", RowKind.Choice, value = accent.title, tag = "settings-accent", adjust = { delta -> changeAccent(Accent.entries.step(accent, delta)) }),
            MenuRow("hints", "Button hints", RowKind.Choice, value = hints.title, tag = "settings-hints", adjust = { delta -> changeHints(HintMode.entries.step(hints, delta)) }),
            header("Status"),
            toggle("status", "Clock and battery", showStatus) { changeShowStatus(it) },
            toggle("percent", "Battery percentage", showPercent, enabled = showStatus) { changeShowPercent(it) },
            MenuRow("clock", "Time format", RowKind.Choice, value = clock.title, enabled = showStatus, tag = "settings-clock",
                adjust = { delta -> changeClock(ClockStyle.entries.step(clock, delta)) }),
            header("Sound"),
            toggle("sound", "Insert and eject sound", sound, enabled = !state.reducedMotion,
                note = if (state.reducedMotion) "Plays with full motion only" else null) { changeSound(it) })
        SettingsCategory.Gameplay -> buildList {
            add(header(CoreFamily.Handheld.title))
            addAll(optionRows(CoreFamily.Handheld))
            add(header(CoreFamily.PlayStation.title))
            add(MenuRow("bios", "BIOS file", value = if (biosReady) "Added" else "Not added", enabled = ready, tag = "settings-bios",
                note = if (biosReady) "Choose again to replace it." else "Most games need the console's own BIOS. Without it only some will start.") { chooseBios() })
            addAll(optionRows(CoreFamily.PlayStation))
            add(MenuRow("scope", "A game can use its own choices", RowKind.Info, note = "Open a game's details or pause menu, then Game options."))
            if (state.resumeSession != null) {
                add(header("Saved session"))
                add(MenuRow("discard", "Discard saved session", RowKind.Danger, note = "Battery and manual saves are kept", enabled = ready, tag = "settings-discard") { coordinator.discardResume() })
            }
        }
        SettingsCategory.Controls -> controlRows(state)
        SettingsCategory.System -> listOf(
            toggle("home", "Offer Cartridge as home screen", homeEnabled, note = "Android asks before it changes your home app.") { setHome(it) },
            MenuRow("default", "Choose home app", enabled = homeEnabled, tag = "settings-default-home") { host.chooseDefaultHome() },
            MenuRow("android", "Android settings", note = "Wi-Fi, Bluetooth, display and storage", tag = "settings-android") { host.openAndroidSettings() },
            header("This device"),
            MenuRow("storage", "Storage used", RowKind.Info, value = storage ?: "Measuring…"),
            MenuRow("about", "About & licences", RowKind.Link, tag = "settings-about") { push(Destination.About) })
        SettingsCategory.Advanced -> listOf(
            MenuRow("verify", "Check every game file", note = "Re-reads each game to confirm nothing changed. Slow for large libraries.",
                enabled = ready && roots.isNotEmpty(), tag = "settings-verify") { refreshLibrary(verify = true) },
            MenuRow("reset", "Reset settings", RowKind.Danger, note = "Appearance, controls and gameplay return to defaults. Games, saves and folders are not affected.",
                enabled = !state.busy, tag = "settings-reset") { resetSettings() })
    }
}
