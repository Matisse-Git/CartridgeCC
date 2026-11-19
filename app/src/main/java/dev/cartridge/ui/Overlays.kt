package dev.cartridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.*
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Hint
import dev.cartridge.ui.design.HintBar
import dev.cartridge.ui.design.Typo.tabular

/**
 * Arrives once per key: it fades in while sliding [slide] from the right and growing from [from], and leaves the same
 * way back while [leaving]. Under reduced motion it only crossfades.
 */
@Composable private fun Entering(key: Any, slide: Dp = 0.dp, from: Float = 1f, ms: Int = DesignMotion.SHEET_MS, leaving: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val motion = LocalDesignMotion.current
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, leaving) { progress.animateTo(if (leaving) 0f else 1f, motion.progress(ms)) }
    val travel = motion.travel(slide.value)
    val grow = if (motion.reduced) 1f else from
    Box(Modifier.fillMaxSize().graphicsLayer {
        val p = progress.value
        alpha = p; translationX = ((1f - p) * travel).dp.toPx()
        val k = grow + (1f - grow) * p; scaleX = k; scaleY = k
    }, content = content)
}

private fun hints(page: Page, focused: MenuRow?, swapped: Boolean, shell: ConsoleShell) = buildList {
    if (page.rows.any { it.focusable }) add(Hint(Pad.confirm(swapped), "Select", primary = true, tag = "select") { shell.select() })
    if (focused?.kind == RowKind.Choice && focused.enabled) add(Hint(Pad.DpadX, "Change"))
    add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
}

/**
 * Draws whatever the shell's stack says is open and no screen of its own draws: the nearest opaque page, then the
 * sheet or dialog resting on it, in the design's own 1280×720 canvas. Sheets are glass panels at the right like the
 * Quick menu, dialogs a glass card in the middle; both rest on whatever is beneath, blurred where it can be.
 */
@Composable fun BoxScope.ShellOverlays(shell: ConsoleShell, state: CollectionState, status: EngineStatus, device: DeviceStatus, confirm: String, back: String) {
    val top = shell.top
    // The pause screen draws its own sidebar and sections; only a question asked over it is drawn here.
    val pause = shell.pauseOpen
    if (pause && !shell.overPause(top)) return
    // The Quick menu is its own screen (ui/screens/QuickMenuScreen).
    if (top == Destination.SystemMenu) return
    val swapped = shell.controls.swapMenuButtons
    DesignCanvas(state.reducedMotion) {
        Box(Modifier.fillMaxSize()) {
            // Systems, Apps and Settings are screens of their own (ui/screens); what is opened over them is still drawn here.
            if (!pause && shell.base != Destination.Systems && shell.base != Destination.Apps && shell.base != Destination.Settings) shell.base?.let { destination ->
                // A page opened from another slides in from the right, as a step deeper.
                shell.page(state, status, destination)?.let { page ->
                    Entering(destination.key, DesignMotion.PAGE_TRAVEL.dp, ms = DesignMotion.TAB_MS) { FullPage(page, shell, top == destination, swapped) }
                }
            }
            val open = if (top == null || !top.opaque) shell.page(state, status, top) else null
            // A sheet or dialog that closes leaves the way it came, so it is kept drawn while it goes.
            var kept by remember { mutableStateOf<Page?>(null) }
            if (open != null && open !== kept) SideEffect { kept = open }
            val leaving = open == null && kept != null
            LaunchedEffect(leaving) { if (leaving) { kotlinx.coroutines.delay(DesignMotion.SHEET_MS.toLong()); kept = null } }
            val floating = open ?: kept
            val scrim by androidx.compose.animation.core.animateFloatAsState(if (open != null) 1f else 0f, LocalDesignMotion.current.fade(), label = "scrim")
            if (floating != null) {
                val focused = shell.focused(floating)
                when (floating.style) {
                    // The pause menu and Game detail are screens of their own, drawn beneath these overlays.
                    PageStyle.Pause, PageStyle.Details -> Unit
                    PageStyle.Dialog -> {
                        Scrim(shell::back, scrim, open != null)
                        Entering(floating.key, from = .96f, ms = DesignMotion.FADE_MS + 60, leaving = leaving) { Dialog(floating, focused, shell, swapped) }
                    }
                    else -> {
                        // The label preview's whole subject is the box beside it, so nothing dims it.
                        val beside = status.state == SessionState.Idle && floating.key == "label"
                        if (!beside) Scrim(shell::back, scrim, open != null)
                        // A sheet is a panel at the right edge: it slides in from that edge and back out to it.
                        Entering(floating.key, 64.dp, leaving = leaving) { Sheet(floating, focused, shell, swapped) }
                    }
                }
            }
        }
    }
}

/** Dims what is beneath; a tap on it closes what is open, as B does. */
@Composable private fun Scrim(dismiss: () -> Unit, alpha: Float = 1f, enabled: Boolean = true) {
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Color(0x8C000000)).tap("scrim", enabled, action = dismiss))
}

/** A glass panel at the right, as the Quick menu is drawn: a short list about one thing, each row with its help. */
@Composable private fun BoxScope.Sheet(page: Page, focused: MenuRow?, shell: ConsoleShell, swapped: Boolean) {
    Column(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp, bottom = 24.dp).width(SHEET_WIDTH).fillMaxHeight()
        .glass(alpha = .9f).tap("sheet-panel") {}.padding(22.dp).testTag("sheet-${page.key}")) {
        Text(page.title, Typo.Title.copy(fontSize = 22.sp), lines = 2)
        page.subtitle?.let { Text(it, Typo.Caption, Modifier.padding(top = 4.dp), Palette.Secondary, lines = 1) }
        Column(Modifier.weight(1f).padding(top = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            page.body?.let { Text(it, Typo.Caption, Modifier.padding(horizontal = 12.dp), Palette.Secondary) }
            rowGroups(page.rows).forEach { group -> RowCard(group, focused, card = false, showNotes = true) { shell.activate(page, it) } }
        }
        HintRow(hints(page, focused, swapped, shell), Modifier.padding(top = 16.dp), gap = 20.dp)
    }
}

/** A glass card in the middle for one question or one result: its words, then its few answers. */
@Composable private fun BoxScope.Dialog(page: Page, focused: MenuRow?, shell: ConsoleShell, swapped: Boolean) {
    val flow = shell.import
    Column(Modifier.align(Alignment.Center).width(520.dp).glass(alpha = .92f).tap("dialog-panel") {}.padding(28.dp).testTag("dialog-${page.key}")) {
        if (page.key == "import" && flow != null) ImportBody(flow, swapped) else {
            Text(page.title, Typo.Title.copy(fontSize = 22.sp), lines = 3)
            page.body?.let { Text(it, Typo.Callout, Modifier.padding(top = 8.dp), Palette.Secondary) }
            page.subtitle?.let { Text(it, Typo.Caption, Modifier.padding(top = 8.dp), Palette.Tertiary) }
        }
        if (page.rows.isNotEmpty()) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            page.rows.forEach { row -> SettingRow(row, row === focused, showNote = true) { shell.activate(page, row) } }
        }
        // While a combo or a button is recorded every button belongs to it, so no button is offered for anything else.
        if (flow !is ImportFlow.Looking && flow !is ImportFlow.Adding && page.key != "record" && page.key != "record-button")
            HintRow(hints(page, focused, swapped, shell), Modifier.padding(top = 20.dp), gap = 20.dp)
    }
}

/** A page of its own over everything: its title and words at the left, its rows in grouped cards beside them. */
@Composable private fun FullPage(page: Page, shell: ConsoleShell, active: Boolean, swapped: Boolean) {
    Box(Modifier.fillMaxSize().background(Palette.Canvas).testTag("page-${page.key}")) {
        when (page.style) {
            PageStyle.Keyboard -> Keyboard(page, shell, swapped)
            PageStyle.Reader -> Reader(page, shell, swapped)
            PageStyle.Settings, PageStyle.Apps -> Unit
            else -> {
                val focused = shell.focused(page).takeIf { active }
                Row(Modifier.padding(start = Layout.Margin, end = Layout.Margin, top = 40.dp, bottom = Layout.HintBar).fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Column(Modifier.width(340.dp).fillMaxHeight()) {
                        Text(page.title, Typo.Title, lines = 3)
                        page.subtitle?.let { Text(it, Typo.Body.copy(fontWeight = FontWeight.Normal), Modifier.padding(top = 8.dp), Palette.Secondary, lines = 3) }
                        page.body?.let { Text(it, Typo.Callout.copy(fontWeight = FontWeight.Normal, lineHeight = 21.sp), Modifier.padding(top = 20.dp).weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()), Palette.Secondary) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        rowGroups(page.rows).forEach { group -> RowCard(group, focused) { shell.activate(page, it) } }
                    }
                }
                if (active) Footer(focused?.note, focused?.warn == true, hints(page, focused, swapped, shell))
            }
        }
    }
}

/** The hint bar, with the focused row's help at its left where a page has one. */
@Composable private fun BoxScope.Footer(help: String?, warn: Boolean, hints: List<Hint>) {
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(Layout.HintBar).padding(horizontal = Layout.Margin), verticalAlignment = Alignment.CenterVertically) {
        Text(help.orEmpty(), Typo.Caption, Modifier.weight(1f).padding(end = 24.dp).testTag("page-help"), if (warn) Palette.DestructiveText else Palette.Tertiary, lines = 2)
        HintRow(hints)
    }
}

@Composable private fun BoxScope.Reader(page: Page, shell: ConsoleShell, swapped: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(shell.readerLine) { scroll.animateScrollTo(shell.readerLine * 220) }
    // Licence texts are wrapped at about 72 columns, so the page reads as a column of that width.
    Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp, bottom = Layout.HintBar).width(540.dp).fillMaxHeight()) {
        Text(page.title, Typo.Title, lines = 1)
        Box(Modifier.padding(top = 20.dp).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2)) {
            Text(page.body.orEmpty().ifEmpty { "Opening…" }, Typo.Caption.copy(fontWeight = FontWeight.Normal, lineHeight = 20.sp),
                Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).testTag("reader-text"), Palette.Soft)
        }
    }
    HintBar(listOf(Hint(Pad.DpadY, "Scroll"), Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() }), Modifier.align(Alignment.BottomStart))
}

/** A grid the D-pad can type on. The field above it is real, so touch and hardware keyboards work too. */
@Composable private fun BoxScope.Keyboard(page: Page, shell: ConsoleShell, swapped: Boolean) {
    val spec = (shell.top as? Destination.TextEntry)?.spec
    Column(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).width(720.dp)) {
        Text(page.title, Typo.Title.copy(fontSize = 22.sp), lines = 1)
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2)
            .border(1.dp, Palette.Separator, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 14.dp)) {
            BasicTextField(shell.text, shell::replaceText, Modifier.fillMaxWidth().heightIn(max = 72.dp).testTag(spec?.tag ?: "text-entry"),
                textStyle = Typo.Headline.copy(fontWeight = FontWeight.Medium, color = Palette.Label), cursorBrush = SolidColor(Palette.Accent), maxLines = 3,
                visualTransformation = if (spec?.secret == true) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (spec?.secret == true) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false)
                    else androidx.compose.foundation.text.KeyboardOptions.Default)
            if (shell.text.isEmpty()) Text(page.subtitle.orEmpty(), Typo.Headline.copy(fontWeight = FontWeight.Medium), color = Palette.Tertiary, lines = 1)
        }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val focusRow = shell.key / ConsoleShell.KEY_COLUMNS; val focusColumn = shell.key % ConsoleShell.KEY_COLUMNS
            ConsoleShell.KEY_ROWS.forEachIndexed { rowIndex, keys ->
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    HintBar(listOf(Hint(Pad.confirm(swapped), "Type", primary = true), Hint(Pad.X, "Delete"), Hint(Pad.Y, "Space"),
        Hint(Pad.Menu, "Done", tag = "text-done") { shell.finishText() }, Hint(Pad.back(swapped), "Cancel", tag = "back") { shell.back() }), Modifier.align(Alignment.BottomStart))
}

@Composable private fun RowScope.Key(label: String, focused: Boolean, weight: Float, tag: String, primary: Boolean = false, press: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.weight(weight).fillMaxHeight().clip(shape).background(if (focused) Palette.Label else Palette.Surface2).tap(tag, action = press),
        contentAlignment = Alignment.Center) {
        Text(label, if (label.length == 1) Typo.Headline.copy(fontWeight = FontWeight.Medium) else Typo.Callout,
            color = when { focused -> Palette.Canvas; primary -> Palette.AccentText; else -> Palette.Label }, lines = 1)
    }
}

@Composable private fun ImportBody(flow: ImportFlow, swapped: Boolean) {
    val title = Typo.Title.copy(fontSize = 22.sp)
    Column {
        when (flow) {
            is ImportFlow.Looking -> {
                Text(flow.folder?.let { "Looking through $it" } ?: "Looking for games", title, Modifier.testTag("import-title"), lines = 2)
                Progress(null, Modifier.padding(top = 20.dp))
            }
            is ImportFlow.Adding -> {
                val total = flow.discovery.total
                Text(if (total == 0) "Looking through ${flow.discovery.folder}" else "Found ${plural(total, "game")}" +
                    if (flow.discovery.games.size > 1) " across ${flow.discovery.games.size} consoles" else "", title, Modifier.testTag("import-title"), lines = 2)
                ConsoleCounts(flow.discovery.games.mapKeys { it.key.title })
                Progress(if (total > 0) flow.handled / total.toFloat() else null, Modifier.padding(top = 20.dp))
                Text(if (flow.current.isEmpty()) "Getting ready" else "Adding ${flow.current}", Typo.Caption, Modifier.padding(top = 10.dp), Palette.Secondary, lines = 1)
                Text("Your files stay where they are. Cartridge keeps its own copy of each game so it always starts.", Typo.Small, Modifier.padding(top = 8.dp), Palette.Tertiary)
            }
            is ImportFlow.Done -> {
                Text(importHeadline(flow.result, flow.cancelled), title, Modifier.testTag("import-title"), lines = 2)
                ConsoleCounts(flow.result.addedSystems.filterValues { it > 0 }.mapKeys { it.key.title })
                val known = flow.result.duplicates
                if (known > 0 && flow.result.added > 0) Text("${plural(known, "game")} from this folder ${if (known == 1) "was" else "were"} already in your library.",
                    Typo.Caption, Modifier.padding(top = 10.dp), Palette.Secondary)
                if (flow.result.added == 0 && known == 0 && flow.result.failed == 0 && flow.result.otherConsoles.isEmpty() && flow.result.archives == 0)
                    Text("Cartridge plays ${dev.cartridge.domain.GameSystem.entries.map { it.title }.let { it.dropLast(1).joinToString() + " and " + it.last() }} games. Try the folder that holds those files.",
                        Typo.Callout, Modifier.padding(top = 10.dp), Palette.Secondary)
                importNotes(flow.result).forEach { note ->
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(Palette.Secondary))
                        Text(note, Typo.Caption, Modifier.testTag("import-note"), Palette.Quiet, lines = 3)
                    }
                }
                if (flow.firstRun && flow.result.added > 0) HintRow(buildList {
                    add(Hint(Pad.confirm(swapped), "Play", primary = true))
                    if (flow.result.addedSystems.count { it.value > 0 } > 1) add(Hint(Pad.R1, "Change console"))
                    add(Hint(Pad.Menu, "Menu"))
                }, Modifier.padding(top = 20.dp), gap = 20.dp)
            }
            is ImportFlow.Failed -> {
                Text("This folder couldn't be read", title, Modifier.testTag("import-title"), lines = 2)
                Text(flow.reason, Typo.Callout, Modifier.padding(top = 8.dp), Palette.Secondary)
            }
        }
    }
}

@Composable private fun ConsoleCounts(counts: Map<String, Int>) {
    if (counts.isEmpty()) return
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        counts.forEach { (console, count) ->
            Row(Modifier.fillMaxWidth()) {
                Text(console, Typo.Callout, Modifier.weight(1f), Palette.Secondary, lines = 1)
                Text("$count", Typo.Callout.tabular(), color = Palette.Label, lines = 1)
            }
        }
    }
}

/** A thin bar that fills as work is done; with no fraction it is an empty track, the work's size not yet known. */
/** Confirmations pass; warnings stay. Both are glass pills under the top bar, clear of a sheet at the right. */
@Composable fun BoxScope.Notices(message: String?, warning: String?, beside: Dp = 0.dp, lingerMs: Long = 4200) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { shown = message; if (message != null) { kotlinx.coroutines.delay(lingerMs); shown = null } }
    Column(Modifier.align(Alignment.TopCenter).padding(top = 84.dp, end = beside), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (warning != null) Notice(warning, true, "message")
        shown?.let { Notice(it, false, if (warning == null) "message" else "toast") }
    }
}

@Composable private fun Notice(text: String, warning: Boolean, tag: String) {
    Row(Modifier.widthIn(max = 560.dp).glass(RoundedCornerShape(22.dp), alpha = .92f).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (warning) Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Destructive))
        Text(text, Typo.Callout.copy(lineHeight = 20.sp), Modifier.testTag(tag), if (warning) Palette.DestructiveText else Palette.Label, lines = 4)
    }
}

/** A sheet's width with its margin, which a notice keeps clear of. */
val SHEET_CLEARANCE = 448.dp
private val SHEET_WIDTH = 400.dp
