package dev.cartridge.shell

import android.text.format.DateUtils
import dev.cartridge.domain.ButtonMap
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.Core
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.LaunchTransition
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.environment.ConsoleSettings
import dev.cartridge.environment.GameOptions
import dev.cartridge.input.ButtonCombo
import dev.cartridge.input.QuickCombo
import dev.cartridge.library.LabelFit
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.LibrarySort
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.CollectionState
import dev.cartridge.session.LaunchFailure
import dev.cartridge.shaders.ResolvedShader
import dev.cartridge.shaders.ShaderChoice
import dev.cartridge.shaders.ShaderLevel
import dev.cartridge.shaders.ShaderResolution
import dev.cartridge.shaders.ShaderTarget
import dev.cartridge.storage.SlotKind
import dev.cartridge.storage.StateSlot
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
            paused && !status.frozen -> pausePage(state)
            else -> null
        }
        Destination.SystemMenu -> systemMenu(state)
        Destination.Settings -> Page("settings:${category.name}", "Settings", settingsRows(state, category, status), PageStyle.Settings)
        Destination.Controls -> Page("controls", "Controls", controlRows(state), PageStyle.Page, "Menu buttons are the same everywhere. Each console has its own game buttons.")
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
        is Destination.Setup -> setupPage(destination.core)
        Destination.Apps -> Page("apps", "Apps", emptyList(), PageStyle.Apps)
        is Destination.ShaderPicker -> shaderPickerPage(destination)
        Destination.SaveStates -> game?.let { saveStatesPage(it, state) }
        is Destination.SlotActions -> game?.let { slotPage(it, destination.name, state) }
        is Destination.ConfirmSlot -> confirmPage(destination, state)
        is Destination.RecordCombo -> recordPage(destination)
        is Destination.ConsoleButtons -> buttonsPage(destination)
        is Destination.RecordButton -> recordButtonPage(destination)
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
    // A console whose state cannot be captured is not offered one; its games keep their own saves.
    val states = game?.system?.core?.snapshots != dev.cartridge.domain.Snapshots.Never
    val saved = stateList?.takeIf { it.first == game?.id }?.second?.count { it.kind == SlotKind.Saved }
    return Page("pause", game?.title ?: "Paused", listOfNotNull(
        MenuRow("resume", "Resume", enabled = ready, tag = "menu-0") { coordinator.menuAction(0) },
        if (states) MenuRow("save", "Quick Save", enabled = ready, tag = "menu-1") { coordinator.quickSave(resume = controls.returnToGame) } else null,
        if (states) MenuRow("load", "Quick Load", value = known?.stateAt?.takeIf { it > 0 }?.let { "Saved ${whenLabel(it)}" },
            note = if (known != null && known.stateAt <= 0) "Nothing saved yet" else null, enabled = ready && (known == null || known.stateAt > 0), tag = "menu-2") {
            coordinator.quickLoad(resume = controls.returnToGame) } else null,
        if (states) MenuRow("states", "Save States", RowKind.Link, value = saved?.let { if (it == 0) null else plural(it, "save") }, enabled = ready, tag = "menu-5") {
            push(Destination.SaveStates) } else null,
        MenuRow("options", "Game Options", RowKind.Link, enabled = ready, tag = "menu-4") { push(Destination.Options) },
        MenuRow("eject", "Return to Library", enabled = ready, tag = "menu-3") { coordinator.menuAction(3) }),
        PageStyle.Pause, game?.system?.title)
}

private fun ConsoleShell.labelPage(state: CollectionState): Page {
    val label = state.labelDraft?.label
    val ready = !state.busy
    // A case is wrapped in its cover (ADR 0029): a picture of another shape is fitted or fills the front, and is never turned.
    val cover = state.selectedGame?.shownAs?.cover != null
    return Page("label", if (cover) "Cover preview" else "Label preview", listOfNotNull(
        MenuRow("fit", "Sizing", RowKind.Choice, value = if (label?.fit == LabelFit.Crop) (if (cover) "Fill the front" else "Fill the label") else "Show whole image", enabled = ready,
            tag = "label-action-0", adjust = { coordinator.labelAction(0) }),
        if (cover) null else MenuRow("rotate", "Rotate", value = "${(label?.quarterTurns ?: 0) * 90}°", enabled = ready, tag = "label-action-1") { coordinator.labelAction(1) },
        MenuRow("apply", if (cover) "Use this cover" else "Use this label", note = if (state.labelPreviewReady) null else "Preparing the preview…", enabled = ready && state.labelPreviewReady,
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
        if (!firmwareReady(game.system.core)) add(MenuRow("setup", "Add ${game.system.core.title} BIOS", note = "Needed before this game can start",
            warn = true, enabled = ready, tag = "details-setup") { chooseFirmware(game.system.core) })
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
        add(MenuRow("scrape", "Find artwork online", note = "Case art and details, reviewed match by match", enabled = ready, tag = "manage-scrape") { openScraper(single = true) })
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
    val family = game.system.core
    val console = GameOptions.resolve(game.system, options.console(family))
    val overrides = options.game(game.id)
    val shader = resolveShader(game.system, game.id)
    val target = ShaderTarget.Game(game.id, game.system, game.title)
    return Page("options", "Game options", buildList {
        console.forEach { (option, consoleValue) ->
            val override = overrides[option.id]?.takeIf { value -> option.choices.any { it.value == value } }
            val cycle: List<String?> = listOf<String?>(null) + option.choices.map { it.value }
            val replaced = option.key == GameOptions.SCREEN_EFFECT && shader.path != null
            add(MenuRow("option:${option.id}", option.title, RowKind.Choice, value = if (replaced) "Replaced by shader" else option.title(override ?: consoleValue),
                note = when {
                    replaced -> "${ShaderResolution.title(shader.path!!)} is drawn instead. Without a shader: ${option.title(override ?: consoleValue)}"
                    override == null -> "Console setting"
                    paused && !option.live -> "Only this game · from its next start"
                    else -> "Only this game"
                }, tag = "option-${option.id}", adjust = { delta -> setGameOption(game, option, cycle.step(override, delta)) }))
        }
        add(MenuRow("shader", "Shader", RowKind.Link, value = shaderValue(shader.path),
            note = shaderNote(shader, shaderChoices.get(target) != ShaderChoice.Inherit) + if (paused) " · shows when the game resumes" else "",
            warn = shaderMissing(shader.path), tag = "option-shader") { openShaderPicker(target) })
        add(MenuRow("reset", "Use console settings", enabled = overrides.isNotEmpty(), tag = "option-reset") { clearGameOptions(game) })
        if (paused) {
            add(MenuRow("buttons", "${game.system.title} buttons", RowKind.Link, value = buttonsValue(game.system), tag = "option-buttons") {
                push(Destination.ConsoleButtons(game.system)) })
            add(MenuRow("controls", "Controls", RowKind.Link, tag = "option-controls") { push(Destination.Controls) })
        }
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
        "Games run inside Cartridge with ${Core.entries.joinToString { it.engine }}, through LibretroDroid 0.14.0. The collection is drawn with Filament 1.72.1.\n\n" +
            "Your game files, saves and artwork stay on this device and under your control.")
}

private fun ConsoleShell.importPage(): Page {
    val flow = import
    return Page("import", "Adding games", when (flow) {
        is ImportFlow.Looking, is ImportFlow.Adding -> listOf(MenuRow("stop", "Stop", note = "Games already added are kept", tag = "cancel-scan") { coordinator.cancelScan() })
        is ImportFlow.Done -> buildList {
            flow.setup.forEach { core ->
                add(MenuRow("bios:${core.name}", "Add ${core.title} BIOS", note = core.firmwareHelp, tag = if (core == Core.Pcsx) "import-bios" else "import-bios-${core.name.lowercase()}") { chooseFirmware(core) })
            }
            if (flow.result.added == 0 && flow.result.duplicates == 0) add(MenuRow("again", "Choose another folder", tag = "import-again") { addGames() })
            add(MenuRow("done", if (flow.result.added > 0) "See my games" else "Done", tag = "import-done") { pop() })
        }
        is ImportFlow.Failed -> listOf(MenuRow("again", "Choose a folder", tag = "import-again") { addGames() }, MenuRow("close", "Close", tag = "import-done") { pop() })
        null -> emptyList()
    }, PageStyle.Dialog)
}

/** What a console still needs from its owner, with the one thing that supplies it. */
private fun ConsoleShell.setupPage(core: Core): Page = Page("setup", "${core.title} needs its BIOS", buildList {
    core.firmware.filter { it.file !in firmwarePresent }.forEach { firmware ->
        add(MenuRow("bios:${firmware.file}", "Add ${firmware.title}", tag = "setup-${firmware.file.substringAfterLast('/').substringBefore('.')}") { chooseFirmware(core) })
    }
    add(MenuRow("close", "Not now", tag = "setup-close") { pop() })
}, PageStyle.Dialog, "Your games are in the library and ready for it.",
    "${core.firmwareHelp}\n\nCartridge never downloads console files. Choose the one you own; it stays on this device and is left out of backups.")

private fun ConsoleShell.failurePage(failure: LaunchFailure): Page {
    val reason = failure.reason
    val core = failure.system.core
    // One of the console's own files is missing: it cannot start at all, it has the file for another region only,
    // or it is running on a stand-in that fails some games.
    val unconfigured = core.firmware.any { it.file !in firmwarePresent }
    val missing = listOf("missing", "changed", "Permission", "unavailable", "ENOENT", "No such file", "Choose the same", "No content provider", "FileNotFound", "open failed", "content://").any { it in reason }
    val body = when {
        missing -> "Cartridge can't reach this game's file. If it is on an SD card, check the card is inserted, or choose the file again."
        "code" in reason || "did not finish loading" in reason || "did not start" in reason ->
            "The game did not start. Its file may be damaged or not a ${failure.system.title} game." +
                when {
                    !unconfigured -> ""
                    firmwareAdded(core) -> " It may also need the ${failure.system.title} BIOS file for another region."
                    else -> " ${failure.system.title} games also ${if (core.firmwareRequired) "need" else "often need"} a BIOS file."
                }
        else -> ConsoleShell.plain(reason)
    }
    return Page("failure", "${failure.title} didn't start", buildList {
        add(MenuRow("retry", "Try again", tag = "failure-retry") { pop(); coordinator.insert() })
        // Offer the repair that fits the cause: a file that cannot be reached is not a firmware problem.
        if (missing) add(MenuRow("locate", "Locate game file", tag = "failure-locate") { pop(); locateGame() })
        else if (unconfigured) add(MenuRow("bios", "Add ${core.title} BIOS", tag = "failure-bios") { chooseFirmware(core) })
        add(MenuRow("close", "Back to library", tag = "failure-close") { pop() })
    }, PageStyle.Dialog, "Your saves and artwork are safe.", body)
}

internal fun ConsoleShell.controlRows(state: CollectionState): List<MenuRow> {
    val sensitivity = listOf(.5f to "Low", .35f to "Medium", .2f to "High")
    fun flip(title: String, id: String, on: Boolean, off: String, onTitle: String, tag: String = "controls-$id", set: (Boolean) -> Unit) =
        MenuRow(id, title, RowKind.Choice, value = if (on) onTitle else off, tag = tag, adjust = { set(!on) })
    return listOf(
        flip("Confirm button", "confirm", controls.swapMenuButtons, "A  ·  B goes back", "B  ·  A goes back") { changeControls(controls.copy(swapMenuButtons = it)) },
        MenuRow("stick", "Stick sensitivity", RowKind.Choice, value = sensitivity.minBy { kotlin.math.abs(it.first - controls.deadZone) }.second, tag = "controls-stick",
            adjust = { delta -> changeControls(controls.copy(deadZone = sensitivity.map { it.first }.step(sensitivity.minBy { kotlin.math.abs(it.first - controls.deadZone) }.first, delta))) }),
        flip("Pause shortcut", "pause", state.alternateShortcut, "L3 + R3", "Start + Select", tag = "shortcut-setting") { setPauseShortcut(it) },
        comboRow(QuickCombo.Save, "quick-save", "Hold them together during a game. Choose to press new ones."),
        comboRow(QuickCombo.Load, "quick-load", "Hold them together during a game. Choose to press new ones."),
        comboRow(QuickCombo.Freeze, "freeze", "Stops the game where it is, with no menu. Again to go on."),
        comboRow(QuickCombo.FastForward, "fast-forward", "Again for normal speed. The sound is off while it is fast." +
            GameSystem.entries.filter { !it.core.fastForward }.takeIf { it.isNotEmpty() }?.joinToString(", ", " Not on ", " yet.") { it.title }.orEmpty()),
        MenuRow("fast-forward-speed", "Fast-forward speed", RowKind.Choice, value = "${controls.fastForwardSpeed}×", tag = "controls-fast-forward-speed",
            adjust = { delta -> changeControls(controls.copy(fastForwardSpeed = (controls.fastForwardSpeed + delta).coerceIn(ConsoleSettings.SPEEDS))) }),
        MenuRow("quick-reset", "Reset quick buttons", note = QuickCombo.entries.joinToString(" · ") { "${ButtonCombo.title(it.default)} ${it.title.lowercase()}" },
            enabled = QuickCombo.entries.any { controls.combo(it) != it.default }, tag = "controls-quick-reset") { resetQuickButtons() },
        flip("After Quick Save, Quick Load or Load", "after", !controls.returnToGame, "Return to game", "Stay in menu", tag = "controls-after") {
            changeControls(controls.copy(returnToGame = !it)) }) +
        // One row per console in the registry's order, whether or not it has games yet.
        MenuRow("header:consoles", "Game buttons", RowKind.Header) +
        GameSystem.entries.map { system ->
            MenuRow("console:${system.name}", system.title, RowKind.Link, value = buttonsValue(system), tag = "controls-console-${system.name.lowercase()}") {
                push(Destination.ConsoleButtons(system)) }
        } +
        MenuRow("reset", "Reset controls", note = "Also gives every console its default game buttons", tag = "controls-reset") {
            changeControls(ConsoleSettings(audioFilter = controls.audioFilter, frameSkip = controls.frameSkip)); setPauseShortcut(false) }
}

private fun ConsoleShell.buttonsValue(system: GameSystem) = if (system in controls.buttons) "Custom" else "Default"

/** Every button of the console's own pad and the physical button that presses it. Choosing a row listens for a new one. */
private fun ConsoleShell.buttonsPage(destination: Destination.ConsoleButtons): Page {
    val system = destination.system
    val map = controls.buttons(system)
    return Page(destination.key, "${system.title} buttons", buildList {
        system.pad.forEach { button ->
            add(MenuRow("button:${button.key.name}", button.name, RowKind.Choice, value = map.getValue(button.key).title,
                tag = "buttons-${button.key.name.lowercase()}", adjust = { delta -> changeButtons(system, ButtonMap.step(map, button.key, delta)) }) {
                recordButton(system, button.key) })
        }
        add(MenuRow("default", "Use default buttons", note = ButtonMap.default(system).entries.joinToString(" · ") { (key, physical) ->
            "${system.pad.first { it.key == key }.name} ${physical.title}" }, enabled = system in controls.buttons, tag = "buttons-default") { resetButtons(system) })
    }, PageStyle.Page, buttonsValue(system),
        "Choose a button, then press the one you want for it. Left and right step through them; two buttons never share one, so the other one " +
            "trades places. A button that is not listed does nothing in ${system.title} games.\n\n" +
            "The D-pad and sticks stay the console's. The pause shortcut and the quick save and load buttons are not changed by this page.")
}

private fun ConsoleShell.recordButtonPage(destination: Destination.RecordButton): Page {
    recordTick // Follows each button pressed.
    val listener = buttonRecorder
    val name = destination.system.pad.first { it.key == destination.button }.name
    val current = controls.buttons(destination.system).getValue(destination.button)
    val held = listener?.captured?.let { dev.cartridge.input.GameButtons.key(it) }
    return Page("record-button", "${destination.system.title} $name",
        listOf(MenuRow("cancel", "Cancel", tag = "record-cancel") { pop() }), PageStyle.Dialog,
        if (held == null) "Now ${current.title}" else "Pressing ${held.title}",
        listener?.problem ?: "Press the button you want for $name, then let go. Wait five seconds to keep ${current.title}.")
}

internal fun ConsoleShell.settingsRows(state: CollectionState, category: SettingsCategory, status: EngineStatus): List<MenuRow> {
    optionsRevision
    val ready = state.loaded && !state.busy
    fun header(title: String) = MenuRow("header:$title", title, RowKind.Header)
    fun toggle(id: String, title: String, checked: Boolean, note: String? = null, enabled: Boolean = true, tag: String = "settings-$id", set: (Boolean) -> Unit) =
        MenuRow(id, title, RowKind.Toggle, note = note, checked = checked, enabled = enabled, tag = tag, adjust = { set(!checked) })
    // A null family is the options every console shares.
    fun optionRows(family: Core?) = GameOptions.all.filter { it.family == family }.map { option ->
        val value = GameOptions.resolve(GameSystem.entries.first { family == null || it.core == family }, options.console(family ?: Core.Mgba)).getValue(option)
        val shaded = if (option.key != GameOptions.SCREEN_EFFECT) emptyList()
            else GameSystem.entries.filter { (family == null || it.core == family) && resolveShader(it, null).path != null }
        MenuRow("option:${option.id}", option.title, RowKind.Choice, value = option.title(value),
            note = (family?.let { "${it.title}: " } ?: "") + option.help + (if (option.live || option.variable == null) "" else " Applies from a game's next start.") +
                if (shaded.isEmpty()) "" else " Replaced by a shader on ${shaded.joinToString { it.title }}.",
            tag = "option-${option.id}", adjust = { delta -> setConsoleOption(option, option.next(value, delta)) })
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
            artworkRows()?.let(::addAll)
            add(header("Browsing"))
            add(MenuRow("sort", "Sort games by", RowKind.Choice, value = state.query.sort.title, enabled = ready, tag = "settings-sort",
                adjust = { delta -> coordinator.query(state.query.copy(sort = LibrarySort.entries.step(state.query.sort, delta))) }))
            add(MenuRow("hidden", "Hidden games", RowKind.Link, tag = "settings-hidden") { push(Destination.Hidden) })
            add(header("Backup"))
            add(MenuRow("export", "Back up collection", note = "Saves, artwork and details. Game files are not included.", enabled = ready, tag = "settings-export") { exportBackup() })
            add(MenuRow("restore", "Restore from backup", enabled = ready, tag = "settings-restore") { restoreBackup() })
            add(toggle("backup-states", "Include every save state", backupAllStates,
                note = "Adds each game's list of saves and their pictures. The quick save is always included. A backup holds at most 1 GB.") { changeBackupAllStates(it) })
        }
        SettingsCategory.Appearance -> listOf(
            MenuRow("layout", "Layout", RowKind.Choice, value = state.layout.title, enabled = !state.busy, tag = "layout-setting",
                adjust = { delta -> setLayout(CollectionLayout.entries.step(state.layout, delta)) }),
            MenuRow("motion", "Motion", RowKind.Choice, value = if (state.reducedMotion) "Reduced" else "Full", enabled = !state.busy, tag = "motion-setting",
                note = "Reduced removes movement between games and during insertion.", adjust = { setReducedMotion(!state.reducedMotion) }),
            MenuRow("transition", "Game start", RowKind.Choice, value = if (state.reducedMotion) "Cut" else transition.title, enabled = !state.reducedMotion, tag = "settings-transition",
                note = if (state.reducedMotion) "With reduced motion the screen closes and opens without animation."
                    else "How the screen closes when a game starts, and opens once the game has a picture.",
                adjust = { delta -> changeTransition(LaunchTransition.entries.step(transition, delta)) }),
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
            add(header("Every console"))
            addAll(optionRows(null))
            add(MenuRow("start-slot", "Save to start from", RowKind.Choice, value = if (startLatest) "Newest save" else "Quick save", tag = "settings-start-slot",
                note = "Where Start from save state begins: the quick save, or whichever save is newest. If that one can't be loaded the game starts from the beginning.",
                adjust = { changeStartLatest(!startLatest) }))
            // One heading per core, in the registry's order: the file it needs from the owner, then its options.
            for (core in Core.entries) {
                add(header(core.title))
                core.firmware.forEach { firmware ->
                    val added = firmware.file in firmwarePresent
                    add(MenuRow("bios:${firmware.file}", firmware.title, value = if (added) "Added" else "Not added", enabled = ready, warn = !added && core.firmwareRequired,
                        tag = if (core == Core.Pcsx) "settings-bios" else "settings-bios-${firmware.file.substringAfterLast('/').substringBefore('.')}",
                        note = if (added) "Choose again to replace it." else core.firmwareHelp) { chooseFirmware(core) })
                }
                addAll(optionRows(core))
            }
            add(MenuRow("scope", "A game can use its own choices", RowKind.Info, note = "Open a game's details or pause menu, then Game options."))
            if (state.resumeSession != null) {
                add(header("Saved session"))
                add(MenuRow("discard", "Discard saved session", RowKind.Danger, note = "Battery and manual saves are kept", enabled = ready, tag = "settings-discard") { coordinator.discardResume() })
            }
        }
        SettingsCategory.Shaders -> shaderRows(status)
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
            toggle("framerate", "Show frame rate", showFrameRate, note = "Frames the library drew in the last second, beside the clock.") { changeShowFrameRate(it) },
            MenuRow("reset", "Reset settings", RowKind.Danger, note = "Appearance, controls and gameplay return to defaults. Games, saves and folders are not affected.",
                enabled = !state.busy, tag = "settings-reset") { resetSettings() })
    }
}

// ---- Artwork (ADR 0029) ---------------------------------------------------------------------------------

/** Where case art and details come from, the owner's login, and what the last automatic scrape did. */
private fun ConsoleShell.artworkRows(): List<MenuRow>? {
    val settings = artwork ?: return null
    artworkRevision
    val source = settings.source
    val login = settings.login
    val run = autoScraper?.status?.value
    return buildList {
        add(MenuRow("header:Artwork", "Artwork", RowKind.Header))
        add(MenuRow("artwork-source", "Artwork source", RowKind.Choice, value = source.title, tag = "settings-artwork-source",
            note = "Case art and details for games you add, and for Artwork & Info.",
            adjust = { delta -> changeArtworkSource(dev.cartridge.scraping.ArtworkSource.entries.step(source, delta)) }))
        if (source == dev.cartridge.scraping.ArtworkSource.ScreenScraper) {
            val kept = "Kept on this device only. Never in a backup."
            add(MenuRow("login-user", "ScreenScraper user", value = login.user.ifBlank { "Not set" }, warn = login.user.isBlank(), tag = "settings-login-user", note = kept) { editLogin("user") })
            add(MenuRow("login-password", "ScreenScraper password", value = if (login.password.isBlank()) "Not set" else "Set", warn = login.password.isBlank(), tag = "settings-login-password", note = kept) { editLogin("password") })
            add(MenuRow("login-dev", "Developer ID", value = login.devId.ifBlank { "Not set" }, warn = login.devId.isBlank(), tag = "settings-login-dev",
                note = "ScreenScraper asks every program for one. Request yours on the screenscraper.fr forum.") { editLogin("devId") })
            add(MenuRow("login-dev-password", "Developer password", value = if (login.devPassword.isBlank()) "Not set" else "Set", warn = login.devPassword.isBlank(), tag = "settings-login-dev-password", note = kept) { editLogin("devPassword") })
        }
        add(MenuRow("artwork-status", "Automatic artwork", RowKind.Info, tag = "settings-artwork-status", warn = run?.problem != null,
            value = when {
                run == null -> null
                run.running -> "${run.done} of ${run.total}"
                run.problem != null -> "Waiting"
                run.done > 0 -> "Done"
                else -> "Idle"
            },
            note = when {
                run?.running == true -> "Fetching ${run.current ?: "artwork"}…"
                run?.problem != null -> "${run.problem} Games that were not reached are tried again the next time you add a game or scrape."
                run != null && run.done > 0 -> "Last run: ${run.summary}."
                else -> "Games you add get case art and details. Artwork & Info can fill or replace them for many games at once."
            // Focusable so its note, the run's progress or the reason it stopped, is shown.
            }) {})
    }
}

// ---- Shaders (ADR 0027) ---------------------------------------------------------------------------------

private fun megabytes(bytes: Long) = if (bytes < 1L shl 20) "${(bytes shr 10).coerceAtLeast(1)} KB" else "%.1f MB".format(bytes / (1L shl 20).toDouble())
private fun ConsoleShell.shaderValue(path: String?) = when {
    path == null -> "None"
    shaderMissing(path) -> "${ShaderResolution.title(path)} (missing)"
    else -> ShaderResolution.title(path)
}
/** A chosen preset the installed library no longer has; the game is then shown without it. */
private fun ConsoleShell.shaderMissing(path: String?) = path != null && !java.io.File(shaderRoot, path).isFile
private fun shaderNote(resolved: ResolvedShader, own: Boolean) = when {
    own -> "Only this game"
    resolved.level == ShaderLevel.Console -> "Console setting"
    resolved.level == ShaderLevel.Global -> "Every console"
    else -> "No shader is chosen"
}

private fun ConsoleShell.shaderRows(status: EngineStatus): List<MenuRow> = buildList {
    val version = shaderVersion
    val update = shaderUpdate
    val idle = status.state == SessionState.Idle
    fun header(title: String) = MenuRow("header:$title", title, RowKind.Header)
    add(MenuRow("update", if (update == null) "Update shaders" else "Stop updating",
        value = when (update) {
            null -> if (version != null) "Installed" else "Not downloaded"
            ShaderUpdate.Checking -> "Checking…"
            is ShaderUpdate.Downloading -> if (update.total > 0) "${megabytes(update.bytes)} of ${megabytes(update.total)}" else "${megabytes(update.bytes)} downloaded"
        },
        note = when {
            !idle && update == null -> "Return to the library to update."
            version != null -> "RetroArch GLSL shaders · ${version.commit.take(7)} of ${version.date.take(10)} · ${plural(version.presets, "preset")}"
            else -> "Downloads RetroArch's GLSL shader library from GitHub, about 30 MB."
        }, enabled = update != null || idle, tag = "shaders-update") { if (update != null) cancelShaderUpdate() else updateShaders() })
    add(header("Every console"))
    val global = shaderChoices.get(ShaderTarget.Global)
    val globalPath = (global as? ShaderChoice.Preset)?.path
    add(MenuRow("global", "Shader", RowKind.Link, value = shaderValue(globalPath), warn = shaderMissing(globalPath),
        note = "Used by every console without a shader of its own.", tag = "shaders-global") { openShaderPicker(ShaderTarget.Global) })
    add(header("Consoles"))
    GameSystem.entries.forEach { system ->
        val resolved = resolveShader(system, null)
        add(MenuRow("console:${system.name}", system.title, RowKind.Link, value = shaderValue(resolved.path), warn = shaderMissing(resolved.path),
            note = when (resolved.level) {
                ShaderLevel.Console -> if (resolved.path == null) "No shader on this console" else "This console's own"
                ShaderLevel.Global -> "From every console"
                else -> "No shader is chosen"
            }, tag = "shaders-console-${system.name.lowercase()}") { openShaderPicker(ShaderTarget.Console(system)) })
    }
    add(MenuRow("scope", "A game can use its own shader", RowKind.Info, note = "Open a game's details or pause menu, then Game options."))
}

private fun ConsoleShell.shaderPickerPage(destination: Destination.ShaderPicker): Page {
    optionsRevision // Subscribes the page to saved choices and failed checks.
    val target = destination.target
    val chosen = (shaderChoices.get(target) as? ShaderChoice.Preset)?.path
    val current = shaderChoices.get(target)
    val listing = shaderListings[destination.folder]
    val failed = shaderChoices.failed(shaderVersion?.commit)
    val checking = shaderChecking
    return Page(destination.key, "Shader for ${shaderTitle(target).let { if (target is ShaderTarget.Global) it.lowercase() else it }}", buildList {
        if (destination.folder.isEmpty()) {
            if (target !is ShaderTarget.Global) {
                val above = when (target) {
                    is ShaderTarget.Game -> resolveShader(target.system, null)
                    is ShaderTarget.Console -> ShaderResolution.resolve(ShaderChoice.Inherit, ShaderChoice.Inherit, shaderChoices.get(ShaderTarget.Global))
                    else -> ResolvedShader(null, ShaderLevel.None)
                }
                add(MenuRow("inherit", if (target is ShaderTarget.Game) "Use the console's shader" else "Use the every-console shader",
                    value = if (current == ShaderChoice.Inherit) "Chosen" else null, note = "Now: ${shaderValue(above.path)}",
                    enabled = checking == null, tag = "shader-inherit") { chooseShader(target, ShaderChoice.Inherit) })
            }
            add(MenuRow("off", "No shader", value = if (current == ShaderChoice.Off || target is ShaderTarget.Global && current == ShaderChoice.Inherit) "Chosen" else null,
                note = "The picture as its Screen effect draws it", enabled = checking == null, tag = "shader-off") { chooseShader(target, ShaderChoice.Off) })
            add(MenuRow("header:library", "RetroArch shaders", RowKind.Header))
        }
        when {
            listing == null -> add(MenuRow("reading", "Reading the shader library…", RowKind.Info))
            listing.folders.isEmpty() && listing.presets.isEmpty() -> add(MenuRow("empty", "No shaders yet", RowKind.Info,
                note = "Download them under Settings › Shaders, then Update shaders."))
        }
        listing?.folders?.forEach { folder ->
            val name = folder.substringAfterLast('/')
            add(MenuRow("folder:$folder", name, RowKind.Link, value = if (chosen?.startsWith("$folder/") == true) "Chosen inside" else null,
                tag = "shader-folder-$name") { push(Destination.ShaderPicker(target, folder)) })
        }
        listing?.presets?.forEach { path ->
            val name = ShaderResolution.title(path)
            add(MenuRow("preset:$path", name, value = when { checking == path -> "Checking…"; chosen == path -> "Chosen"; else -> null },
                note = if (path in failed) "Didn't work on this device" else null, warn = path in failed, enabled = checking == null,
                tag = "shader-preset-$name") { chooseShader(target, ShaderChoice.Preset(path)) })
        }
    }, PageStyle.Page, destination.folder.ifEmpty { null } ?: "Choosing a preset checks it on this device first.")
}

// ---- Saved states (docs/SAVE_RELIABILITY) -----------------------------------------------------------------

private fun sizeLabel(bytes: Long) = if (bytes < 1L shl 20) "${(bytes shr 10).coerceAtLeast(1)} KB" else "%.1f MB".format(bytes / (1L shl 20).toDouble())
private fun ConsoleShell.dateLabel(time: Long) = DateUtils.formatDateTime(context, time,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
private fun slotTitle(slot: StateSlot) = when (slot.kind) {
    SlotKind.Quick -> "Quick save"
    SlotKind.Previous -> "Before the last quick save"
    SlotKind.Saved -> null
}
private const val OTHER_BUILD = "Made by a different core build. It can't be loaded; the file is kept."

private fun ConsoleShell.saveStatesPage(game: LibraryGame, state: CollectionState): Page {
    val ready = !state.busy
    val list = stateList?.takeIf { it.first == game.id }?.second
    fun row(slot: StateSlot) = MenuRow("slot:${slot.name}", slotTitle(slot) ?: dateLabel(slot.savedAt), RowKind.Link,
        value = whenLabel(slot.savedAt), note = if (slot.compatible) sizeLabel(slot.size) else OTHER_BUILD, warn = !slot.compatible, enabled = ready,
        tag = "slot-${slot.name}", picture = RowPicture(slot.thumbnail, slot.thumbnail?.lastModified() ?: slot.savedAt)) { push(Destination.SlotActions(slot.name)) }
    return Page("states", "Save states", buildList {
        add(MenuRow("new", "New save", note = "Keeps this moment beside your others", enabled = ready, tag = "slots-new") { newSlot() })
        when {
            list == null -> add(MenuRow("reading", "Reading saves…", RowKind.Info))
            else -> {
                list.filter { it.kind != SlotKind.Saved }.forEach { add(row(it)) }
                val saved = list.filter { it.kind == SlotKind.Saved }
                add(MenuRow("header:saves", if (saved.isEmpty()) "Saves" else "Saves · ${saved.size}", RowKind.Header))
                if (saved.isEmpty()) add(MenuRow("none", "No saves yet", RowKind.Info, note = "New save keeps the moment you paused on."))
                saved.forEach { add(row(it)) }
            }
        }
    }, PageStyle.Page, game.title,
        "Quick Save replaces the quick save; the one it replaced stays here until the next. Your saves are kept until you delete them.\n\n" +
            "During a game, ${ButtonCombo.title(controls.quickSave)} quick saves and ${ButtonCombo.title(controls.quickLoad)} quick loads.")
}

private fun ConsoleShell.slotPage(game: LibraryGame, name: String, state: CollectionState): Page? {
    val slot = stateList?.takeIf { it.first == game.id }?.second?.firstOrNull { it.name == name } ?: return null
    val ready = !state.busy
    return Page("slot", slotTitle(slot) ?: "Save", buildList {
        add(MenuRow("load", "Load", note = if (slot.compatible) "Continue from this moment" else OTHER_BUILD, warn = !slot.compatible,
            enabled = ready && slot.compatible, tag = "slot-load") { push(Destination.ConfirmSlot(SlotAction.Load, name)) })
        if (slot.kind == SlotKind.Saved) {
            add(MenuRow("overwrite", "Overwrite", note = "Replace it with the moment you paused on", enabled = ready, tag = "slot-overwrite") {
                push(Destination.ConfirmSlot(SlotAction.Overwrite, name)) })
            add(MenuRow("delete", "Delete", RowKind.Danger, enabled = ready, tag = "slot-delete") { push(Destination.ConfirmSlot(SlotAction.Delete, name)) })
        }
    }, PageStyle.Sheet, "${dateLabel(slot.savedAt)} · ${sizeLabel(slot.size)}")
}

private fun ConsoleShell.confirmPage(destination: Destination.ConfirmSlot, state: CollectionState): Page {
    val (title, body, confirm) = when (destination.action) {
        SlotAction.Load -> Triple("Load this save?", "Progress since you last saved is lost, unless an in-game save kept it.", "Load")
        SlotAction.Overwrite -> Triple("Overwrite this save?", "It is replaced by the moment you paused on.", "Overwrite")
        SlotAction.Delete -> Triple("Delete this save?", "It can't be brought back.", "Delete")
    }
    return Page("confirm", title, listOf(
        MenuRow("confirm", confirm, if (destination.action == SlotAction.Delete) RowKind.Danger else RowKind.Action, enabled = !state.busy, tag = "slot-confirm") {
            confirmSlot(destination.action, destination.name) },
        MenuRow("cancel", "Cancel", tag = "slot-cancel") { pop() }), PageStyle.Dialog, body = body)
}

private fun ConsoleShell.comboRow(action: QuickCombo, id: String, note: String) =
    MenuRow(id, "${action.title} buttons", RowKind.Link, value = ButtonCombo.title(controls.combo(action)), note = note, tag = "controls-$id") { recordCombo(action) }

private fun ConsoleShell.recordPage(destination: Destination.RecordCombo): Page {
    recordTick // Follows each button pressed.
    val listener = recorder
    val held = listener?.captured.orEmpty()
    val current = controls.combo(destination.action)
    return Page("record", "${destination.action.title} buttons",
        listOf(MenuRow("cancel", "Cancel", tag = "record-cancel") { pop() }), PageStyle.Dialog,
        if (held.isEmpty()) "Now ${ButtonCombo.title(current)}" else "Holding ${ButtonCombo.title(held)}",
        listener?.problem ?: "Hold the buttons together, then let go. Wait five seconds to keep the ones you have.")
}
