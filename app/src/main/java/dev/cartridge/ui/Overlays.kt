package dev.cartridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.*

/** Arrives once per key with the shared enter motion; under reduced motion it is simply there. */
@Composable private fun Entering(key: Any, slide: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    val motion = LocalMotion.current
    val progress = remember(key) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(key) { progress.animateTo(1f, motion.enter()) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value; translationX = (1f - progress.value) * slide.toPx() }, content = content)
}

private fun pageHints(page: Page, focused: MenuRow?, confirm: String, back: String, shell: ConsoleShell) = buildList {
    if (page.rows.any { it.focusable }) add(Hint(confirm, "Select"))
    if (focused?.adjust != null) add(Hint("◀ ▶", "Change"))
    add(Hint(back, "Back", "back", shell::back))
}

/** Draws whatever the shell's stack says is open: the nearest opaque page, then the sheet resting on it. */
@Composable fun BoxScope.ShellOverlays(shell: ConsoleShell, state: CollectionState, status: EngineStatus, device: DeviceStatus, confirm: String, back: String) {
    val top = shell.top
    // The pause screen draws its own sidebar and sections; only a question asked over it is drawn here.
    val pause = shell.pauseOpen
    if (pause && !shell.overPause(top)) return
    // The Quick menu is its own screen (ui/screens/QuickMenuScreen).
    if (top == Destination.SystemMenu) return
    if (!pause) shell.base?.let { destination -> shell.page(state, status, destination)?.let { page -> Entering(destination.key) { OpaquePage(page, shell, state, device, top == destination, confirm, back) } } }
    val floating = if (top == null || !top.opaque) shell.page(state, status, top) else null
    if (floating != null) {
        val focused = shell.focused(floating)
        when (floating.style) {
            PageStyle.Pause -> Entering("pause") { PauseMenu(floating, focused, shell, device, confirm, back) }
            // Game detail is a screen of its own (ui/screens/GameDetailScreen), drawn beneath these overlays.
            PageStyle.Details -> Unit
            PageStyle.Dialog -> { Scrim(dismiss = shell::back); Entering(floating.key) { Dialog(floating, focused, shell) } }
            else -> {
                // Sheets about the focused game sit beside its object, like its details. Nothing dims the object:
                // not there, and not in the label preview, whose whole subject is the object.
                val beside = status.state == dev.cartridge.domain.SessionState.Idle && floating.key == "label"
                if (!beside) Scrim(dismiss = shell::back)
                Entering(floating.key, if (beside) 0.dp else 28.dp) {
                    SidePanel(when { beside || floating.key in setOf("manage", "options", "more") -> 400.dp; floating.key == "menu" -> 300.dp; else -> 340.dp }, if (floating.key == "menu") "system-menu-panel" else "sheet-${floating.key}") {
                        Txt(floating.title, Type.Heading, modifier = Modifier.padding(start = 14.dp, top = Space.XS))
                        floating.subtitle?.let { Txt(it, Type.Label, Ink.Faint, Modifier.padding(start = 14.dp, top = 2.dp), lines = 1) }
                        RowList(floating.rows, focused, Modifier.weight(1f).padding(top = Space.M)) { shell.activate(floating, it) }
                        HintBar(pageHints(floating, focused, confirm, back, shell), Modifier.padding(start = 14.dp, top = Space.M))
                    }
                }
            }
        }
    }
}

@Composable private fun BoxScope.OpaquePage(page: Page, shell: ConsoleShell, state: CollectionState, device: DeviceStatus, active: Boolean, confirm: String, back: String) {
    Box(Modifier.fillMaxSize().background(Ink.Stage).testTag(when (page.style) { PageStyle.Settings -> "settings"; else -> "page-${page.key}" })
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Space.Edge, vertical = Space.L)) {
        when (page.style) {
            PageStyle.Settings -> SettingsPage(page, shell, state, confirm, back)
            PageStyle.Keyboard -> Keyboard(page, shell, confirm, back)
            PageStyle.Reader -> Reader(page, shell, back)
            PageStyle.Apps -> AppsPage(shell, device, confirm, back)
            else -> {
                val focused = shell.focused(page)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.XL)) {
                    Column(Modifier.weight(.4f).fillMaxHeight()) {
                        Txt(page.title, Type.Title)
                        page.subtitle?.let { Txt(it, Type.Body, Ink.Soft, Modifier.padding(top = Space.XS)) }
                        Column(Modifier.weight(1f).padding(top = Space.L).verticalScroll(rememberScrollState())) {
                            page.body?.let { Txt(it, Type.Body, Ink.Soft) }
                        }
                        if (active) HintBar(pageHints(page, focused, confirm, back, shell))
                    }
                    RowList(page.rows, focused.takeIf { active }, Modifier.weight(.6f).fillMaxHeight()) { shell.activate(page, it) }
                }
            }
        }
    }
}

@Composable private fun SettingsPage(page: Page, shell: ConsoleShell, state: CollectionState, confirm: String, back: String) {
    val focused = shell.focused(page)
    val inRows = shell.pane == 1
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.XL)) {
        Column(Modifier.width(210.dp).fillMaxHeight()) {
            Txt(page.title, Type.Title, modifier = Modifier.padding(start = 14.dp, bottom = Space.M))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsCategory.entries.forEach { category ->
                    val current = category == shell.category
                    // The current section stays marked while focus is among its rows.
                    RowItem(MenuRow(category.name, category.title, tag = "category-${category.name.lowercase()}", action = {}), focused = current && !inRows, current = current) {
                        shell.chooseCategory(category); shell.enterRows()
                    }
                }
            }
            HintBar(listOf(Hint("L1 R1", "Section"), Hint(back, "Back", "back", shell::back)), Modifier.padding(start = 14.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            RowList(page.rows, focused.takeIf { inRows }, Modifier.weight(1f).padding(top = 40.dp), showNotes = false) { row -> shell.enterRows(); shell.activate(page, row) }
            // One help line for the focused row keeps every row a single line tall.
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, top = Space.S), contentAlignment = Alignment.CenterStart) {
                val row = focused.takeIf { inRows }
                if (row?.note != null) Txt(row.note, Type.Label, if (row.warn) Ink.Warning else Ink.Soft, Modifier.testTag("settings-help"), lines = 2)
                else if (inRows && row?.adjust != null) HintBar(listOf(Hint("◀ ▶", "Change")))
            }
        }
    }
}

@Composable private fun Reader(page: Page, shell: ConsoleShell, back: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(shell.readerLine) { scroll.animateScrollTo(shell.readerLine * 220) }
    Column(Modifier.fillMaxSize()) {
        Txt(page.title, Type.Title)
        Txt(page.body.orEmpty().ifEmpty { "Opening…" }, Type.Label.copy(lineHeight = Type.Body.lineHeight), Ink.Soft,
            Modifier.weight(1f).padding(top = Space.M).verticalScroll(scroll).testTag("reader-text"))
        HintBar(listOf(Hint("▲ ▼", "Scroll"), Hint(back, "Back", "back", shell::back)), Modifier.padding(top = Space.M))
    }
}

/** A grid the D-pad can type on. The field above it is real, so touch and hardware keyboards work too. */
@Composable private fun Keyboard(page: Page, shell: ConsoleShell, confirm: String, back: String) {
    val accent = LocalAccent.current
    val spec = (shell.top as? Destination.TextEntry)?.spec
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Txt(page.title, Type.Heading)
            Box(Modifier.fillMaxWidth().padding(top = Space.S).clip(RoundedCornerShape(Radius.Row)).background(Ink.Surface).border(1.dp, Ink.Line, RoundedCornerShape(Radius.Row))
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                BasicTextField(shell.text, shell::replaceText, Modifier.fillMaxWidth().heightIn(max = 66.dp).testTag(spec?.tag ?: "text-entry"),
                    textStyle = Type.Heading.copy(color = Ink.Text), cursorBrush = SolidColor(accent), maxLines = 3,
                    visualTransformation = if (spec?.secret == true) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = if (spec?.secret == true) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false)
                        else androidx.compose.foundation.text.KeyboardOptions.Default)
                if (shell.text.isEmpty()) Txt(page.subtitle.orEmpty(), Type.Heading, Ink.Faint, lines = 1)
            }
            Column(Modifier.padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val focusRow = shell.key / ConsoleShell.KEY_COLUMNS; val focusColumn = shell.key % ConsoleShell.KEY_COLUMNS
                ConsoleShell.KEY_ROWS.forEachIndexed { rowIndex, keys ->
                    Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (keys.isNotEmpty()) keys.forEachIndexed { column, character ->
                            Key(if (shell.shift) ConsoleShell.shifted(character).toString() else character.toString(), rowIndex == focusRow && column == focusColumn, 1f, "key-$character") {
                                shell.pressKeyAt(rowIndex * ConsoleShell.KEY_COLUMNS + column)
                            }
                        } else listOf(Triple("Shift", 2f, 0), Triple("Space", 4f, 2), Triple("Delete", 2f, 6), Triple("Done", 2f, 8)).forEachIndexed { index, (label, weight, column) ->
                            Key(label, rowIndex == focusRow && shell.wideKey(focusColumn) == index, weight, "key-${label.lowercase()}", index == 3 || index == 0 && shell.shift) {
                                shell.pressKeyAt(rowIndex * ConsoleShell.KEY_COLUMNS + column)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        HintBar(listOf(Hint(confirm, "Type"), Hint("X", "Delete"), Hint("Y", "Space"), Hint("START", "Done", "text-done", shell::finishText), Hint(back, "Cancel", "back", shell::back)))
    }
}

@Composable private fun RowScope.Key(label: String, focused: Boolean, weight: Float, tag: String, primary: Boolean = false, press: () -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.weight(weight).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (focused) accent else Ink.Surface).tap(tag, action = press),
        contentAlignment = Alignment.Center) {
        Txt(label, if (label.length == 1) Type.Heading else Type.Label, when { focused -> Ink.Stage; primary -> accent; else -> Ink.Text })
    }
}

@Composable private fun BoxScope.PauseMenu(page: Page, focused: MenuRow?, shell: ConsoleShell, device: DeviceStatus, confirm: String, back: String) {
    // The menu's side is close to solid, so the focused row reads the same over a dark game and a bright one;
    // the rest of the frozen frame stays visible through the ordinary scrim.
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.Stage.copy(alpha = .95f), .40f to Ink.Stage.copy(alpha = .95f), .64f to Ink.Scrim, 1f to Ink.Scrim)))
    if (shell.showStatus) StatusCluster(device, showBattery = true, showPercent = shell.showPercent,
        modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Space.Edge, vertical = Space.L))
    Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(330.dp).windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(start = Space.Edge - 14.dp, top = Space.XL, bottom = Space.L).testTag("pause-menu")) {
        Txt("PAUSED", Type.Caption, Ink.Faint, Modifier.padding(start = 14.dp))
        Txt(page.title, Type.Title, modifier = Modifier.padding(start = 14.dp, top = Space.XS), lines = 2)
        page.subtitle?.let { Txt(it, Type.Label, Ink.Soft, Modifier.padding(start = 14.dp, top = 2.dp)) }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            page.rows.forEach { row ->
                // Leaving the game stands apart from everything that keeps you in it.
                if (row.id == "eject") Spacer(Modifier.height(Space.M))
                RowItem(row, row.id == focused?.id) { shell.activate(page, row) }
            }
        }
        Spacer(Modifier.weight(1f))
        HintBar(listOf(Hint(confirm, "Select"), Hint(back, "Resume", "back", shell::back)), Modifier.padding(start = 14.dp))
    }
}

@Composable private fun BoxScope.DetailsSheet(page: Page, focused: MenuRow?, shell: ConsoleShell, state: CollectionState, confirm: String, back: String) {
    val game = state.selectedGame
    val facts = shell.facts?.takeIf { it.first == game?.id }?.second
    SidePanel(400.dp, "details-panel") {
        Txt(page.title, Type.Title, modifier = Modifier.padding(start = 14.dp).testTag("details-title"), lines = 2)
        page.subtitle?.takeIf(String::isNotBlank)?.let { Txt(it, Type.Label, Ink.Soft, Modifier.padding(start = 14.dp, top = 3.dp).testTag("details-subtitle"), lines = 1) }
        if (game != null) Row(Modifier.padding(start = 14.dp, top = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            Fact("Played", playLabel(game.playMs), "details-history")
            if (game.lastPlayed > 0) Fact("Last played", whenLabel(game.lastPlayed), "details-last")
            facts?.let { known -> maxOf(known.batteryAt, known.stateAt).takeIf { it > 0 }?.let { Fact("Last saved", whenLabel(it), "details-saved") } }
        }
        Column(Modifier.weight(1f).padding(top = Space.M).verticalScroll(rememberScrollState())) {
            page.body?.let { Txt(it, Type.Label.copy(lineHeight = Type.Body.lineHeight), Ink.Soft, Modifier.padding(horizontal = 14.dp).padding(bottom = Space.M).testTag("details-description"), lines = 4) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { page.rows.forEach { row -> RowItem(row, row.id == focused?.id) { shell.activate(page, row) } } }
        }
        HintBar(listOf(Hint(confirm, "Select"), Hint("Y", "Favorite"), Hint(back, "Back", "back", shell::back)), Modifier.padding(start = 14.dp, top = Space.S))
    }
}

@Composable private fun Fact(label: String, value: String, tag: String) {
    Column {
        Txt(label.uppercase(), Type.Caption, Ink.Faint)
        Txt(value, Type.Body, modifier = Modifier.padding(top = 2.dp).testTag(tag), lines = 1)
    }
}

@Composable private fun BoxScope.Dialog(page: Page, focused: MenuRow?, shell: ConsoleShell) {
    Column(Modifier.align(Alignment.Center).width(460.dp).clip(RoundedCornerShape(Radius.Sheet)).background(Ink.Surface)
        .padding(horizontal = Space.L, vertical = Space.L).testTag("dialog-${page.key}")) {
        val flow = shell.import
        if (page.key == "import" && flow != null) ImportBody(flow, if (shell.controls.swapMenuButtons) "B" else "A") else Column(Modifier.padding(horizontal = 14.dp)) {
            Txt(page.title, Type.Heading)
            page.body?.let { Txt(it, Type.Body, Ink.Soft, Modifier.padding(top = Space.S)) }
            page.subtitle?.let { Txt(it, Type.Label, Ink.Faint, Modifier.padding(top = Space.S)) }
        }
        Column(Modifier.padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            page.rows.forEach { row -> RowItem(row, row.id == focused?.id) { shell.activate(page, row) } }
        }
        // While a combo is recorded every button belongs to it, so no button is offered for anything else.
        if (flow !is ImportFlow.Looking && flow !is ImportFlow.Adding && page.key != "record") HintBar(listOf(Hint(if (shell.controls.swapMenuButtons) "B" else "A", "Select"),
            Hint(if (shell.controls.swapMenuButtons) "A" else "B", "Back", "back", shell::back)), Modifier.padding(start = 14.dp, top = Space.M))
    }
}

@Composable private fun ImportBody(flow: ImportFlow, confirm: String) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        when (flow) {
            is ImportFlow.Looking -> {
                Txt(flow.folder?.let { "Looking through $it" } ?: "Looking for games", Type.Heading, modifier = Modifier.testTag("import-title"))
                ProgressLine(null, Modifier.padding(top = Space.M))
            }
            is ImportFlow.Adding -> {
                val total = flow.discovery.total
                Txt(if (total == 0) "Looking through ${flow.discovery.folder}" else "Found ${plural(total, "game")}" +
                    if (flow.discovery.games.size > 1) " across ${flow.discovery.games.size} consoles" else "", Type.Heading, modifier = Modifier.testTag("import-title"))
                ConsoleCounts(flow.discovery.games.mapKeys { it.key.title })
                ProgressLine(if (total > 0) flow.handled / total.toFloat() else null, Modifier.padding(top = Space.M))
                Txt(if (flow.current.isEmpty()) "Getting ready" else "Adding ${flow.current}", Type.Label, Ink.Soft, Modifier.padding(top = Space.S), lines = 1)
                Txt("Your files stay where they are. Cartridge keeps its own copy of each game so it always starts.", Type.Label, Ink.Faint, Modifier.padding(top = Space.S))
            }
            is ImportFlow.Done -> {
                Txt(importHeadline(flow.result, flow.cancelled), Type.Heading, modifier = Modifier.testTag("import-title"))
                ConsoleCounts(flow.result.addedSystems.filterValues { it > 0 }.mapKeys { it.key.title })
                val known = flow.result.duplicates
                if (known > 0 && flow.result.added > 0) Txt("${plural(known, "game")} from this folder ${if (known == 1) "was" else "were"} already in your library.",
                    Type.Label, Ink.Soft, Modifier.padding(top = Space.S))
                if (flow.result.added == 0 && known == 0 && flow.result.failed == 0 && flow.result.otherConsoles.isEmpty() && flow.result.archives == 0)
                    Txt("Cartridge plays ${dev.cartridge.domain.GameSystem.entries.map { it.title }.let { it.dropLast(1).joinToString() + " and " + it.last() }} games. Try the folder that holds those files.",
                        Type.Body, Ink.Soft, Modifier.padding(top = Space.S))
                importNotes(flow.result).forEach { Txt(it, Type.Label, Ink.Warning, Modifier.padding(top = Space.S).testTag("import-note"), lines = 3) }
                if (flow.firstRun && flow.result.added > 0) HintBar(buildList {
                    add(Hint(confirm, "Play")); if (flow.result.addedSystems.count { it.value > 0 } > 1) add(Hint("L1 R1", "Change console")); add(Hint("START", "Menu"))
                }, Modifier.padding(top = Space.M), Ink.Soft)
            }
            is ImportFlow.Failed -> {
                Txt("This folder couldn't be read", Type.Heading, modifier = Modifier.testTag("import-title"))
                Txt(flow.reason, Type.Body, Ink.Soft, Modifier.padding(top = Space.S))
            }
        }
    }
}

@Composable private fun ConsoleCounts(counts: Map<String, Int>) {
    if (counts.isEmpty()) return
    Column(Modifier.padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        counts.forEach { (console, count) ->
            Row(Modifier.fillMaxWidth()) {
                Txt(console, Type.Body, Ink.Soft, Modifier.weight(1f))
                Txt("$count", Type.Body, Ink.Text)
            }
        }
    }
}

/** Confirmations pass; warnings stay. Both sit clear of the title and the hints. */
@Composable fun BoxScope.Notices(message: String?, warning: String?, beside: Dp = 0.dp, lingerMs: Long = 4200) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { shown = message; if (message != null) { kotlinx.coroutines.delay(lingerMs); shown = null } }
    Column(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 58.dp, end = beside), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (warning != null) Notice(warning, true, "message")
        shown?.let { Notice(it, false, if (warning == null) "message" else "toast") }
    }
}
