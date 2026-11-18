package dev.cartridge.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.PadKey
import dev.cartridge.emulation.EngineStatus
import dev.cartridge.library.LibraryGame
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.Destination
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.Page
import dev.cartridge.shell.RowKind
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-game pause menu (spec 4.5, Pause / PauseInput / PauseCore / PauseMenu.dc.html): the game's frame blurred and
 * dimmed behind, the sidebar at the left, the selected section in the glass panel beside it, and its hints under the
 * panel. While the sidebar has the focus the panel shows the section of the focused row; A or Right goes into it.
 */
@Composable fun PauseScreen(shell: ConsoleShell, state: CollectionState, status: EngineStatus, frame: Bitmap?, swapped: Boolean,
    modifier: Modifier = Modifier) {
    val game = state.selectedGame ?: return
    val motion = LocalDesignMotion.current
    val open = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { open.animateTo(1f, motion.fade(220)) }
    val sidebar = shell.page(destination = null)?.takeIf { it.key == "pause" } ?: return
    // The section in the panel: the one open, else the one the sidebar's focus is on, else the last one shown.
    val openSection = shell.stack.lastOrNull { shell.pausePanel(it) }
    val focusedRow = shell.focused(sidebar)
    var shown by remember { mutableStateOf<Section>(Section.States) }
    val section = openSection?.let(::sectionOf) ?: focusedRow?.id?.let(::sectionOf) ?: shown
    SideEffect { shown = section }
    val inPanel = openSection != null
    val question = shell.overPause(shell.top)

    Box(modifier.fillMaxSize().graphicsLayer { alpha = open.value }.testTag("pause-screen")) {
        Backdrop(frame)
        Sidebar(sidebar, focusedRow, game, shell, status, inPanel, Modifier.absoluteOffset(24.dp, 24.dp)
            .graphicsLayer { translationX = (1f - open.value) * -16.dp.toPx() })
        val panelModifier = Modifier.padding(start = 348.dp, end = 24.dp, top = 24.dp, bottom = 88.dp).fillMaxSize()
        val footer = Modifier.align(Alignment.BottomStart).padding(start = 348.dp, end = 24.dp, bottom = 24.dp).fillMaxWidth().height(48.dp)
        when (section) {
            Section.States -> {
                val page = shell.page(destination = Destination.SaveStates)
                if (page != null) StatesPanel(page, shell, inPanel && !question, game, panelModifier, footer, swapped, sidebar, focusedRow)
            }
            Section.Controls -> {
                val destination = openSection as? Destination.ConsoleButtons ?: shell.stack.filterIsInstance<Destination.ConsoleButtons>().lastOrNull()
                    ?: Destination.ConsoleButtons(game.system)
                val page = shell.page(destination = destination)
                val listening = shell.top as? Destination.RecordButton
                val listen = listening?.let { shell.page(destination = it) }
                if (page != null) ControlsPanel(page, shell, inPanel && !question, game.system, listening?.button, listen, panelModifier, footer, swapped, sidebar, focusedRow)
            }
            Section.Core -> {
                val page = shell.page(destination = Destination.CoreSettings)
                if (page != null) CorePanel(page, shell, inPanel && !question, game, frame, panelModifier, footer, swapped, sidebar, focusedRow)
            }
        }
    }
}

private enum class Section { States, Controls, Core }
private fun sectionOf(destination: Destination): Section? = when (destination) {
    Destination.SaveStates -> Section.States
    Destination.CoreSettings -> Section.Core
    is Destination.ConsoleButtons, is Destination.RecordButton -> Section.Controls
    else -> null
}
private fun sectionOf(rowId: String): Section? = when (rowId) {
    "states" -> Section.States
    "controls" -> Section.Controls
    "core", "display" -> Section.Core
    else -> null
}

/** The game's frame at its own aspect, blurred 28 and at 55 % brightness, under a 25 % black wash. */
@Composable private fun Backdrop(frame: Bitmap?) {
    Box(Modifier.fillMaxSize().background(Palette.Canvas)) {
        if (frame != null && !frame.isRecycled) {
            val dim = remember { ColorMatrix(floatArrayOf(.55f, 0f, 0f, 0f, 0f, 0f, .55f, 0f, 0f, 0f, 0f, 0f, .55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)) }
            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier),
                contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.colorMatrix(dim))
        }
        Box(Modifier.fillMaxSize().background(Color(0x40000000)))
    }
}

// ---- Sidebar ----------------------------------------------------------------------------------------------

@Composable private fun Sidebar(page: Page, focused: MenuRow?, game: LibraryGame, shell: ConsoleShell, status: EngineStatus, inPanel: Boolean, modifier: Modifier) {
    val media = LocalGameMedia.current
    val faces = rememberFaces(media, game.label, game.shownAs)
    val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
    Column(modifier.size(300.dp, 672.dp).glass().padding(horizontal = 14.dp, vertical = 20.dp).testTag("pause-sidebar"),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val front = faces?.front?.takeIf { !it.isRecycled }
            Box(Modifier.size(42.dp, 46.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(look.tone1), Color(look.tone2))))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(4.dp))) {
                if (front != null) Image(front.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((look.series ?: game.system.title).uppercase(), Typo.Section.copy(fontSize = 11.sp), color = Palette.Secondary, lines = 1)
                Text(look.short, Typo.Headline, lines = 1)
                // The session's running time, read once a second while the menu is open.
                var now by remember { mutableLongStateOf(shell.sessionMillis()) }
                LaunchedEffect(Unit) { while (true) { now = shell.sessionMillis(); kotlinx.coroutines.delay(1000) } }
                Text(listOf(game.system.shortName, game.system.core.engine.substringBeforeLast(" "), clock(now)).joinToString(" · "),
                    Typo.Small.tabular(), color = Palette.Secondary, lines = 1)
            }
        }
        val quick = page.rows.filter { it.id.startsWith("quick:") }
        if (quick.isNotEmpty()) Row {
            quick.forEach { row ->
                val on = !inPanel && row === focused
                Column(Modifier.width(68.dp).tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(50.dp).focusLift(on, CircleShape, scale = 1.06f).clip(CircleShape)
                        .background(if (on) Palette.Label else if (row.checked == true) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                        Icon(if (row.id == "quick:fast") ICON_FAST else if (row.checked == true) ICON_MUTED else ICON_SOUND, if (on) Palette.Canvas else Palette.Label, 20.dp,
                            filled = row.id == "quick:fast")
                    }
                    Text(row.title, Typo.Small.copy(fontSize = 11.sp), color = Palette.Quiet, lines = 1)
                }
            }
        }
        Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(1.dp).background(Palette.GlassBorder))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            page.rows.filter { !it.id.startsWith("quick:") }.forEach { row ->
                // The sidebar's own focus is white; the row whose section has the focus stays lit, quieter.
                val on = row === focused && !inPanel
                val lit = row === focused && inPanel
                val danger = row.kind == RowKind.Danger
                val ink = when { on -> Palette.Canvas; danger -> Palette.DestructiveText; !row.enabled -> Palette.Tertiary; else -> Palette.Label }
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(Radii.R12)).background(if (on) Palette.Label else if (lit) Color(0x24FFFFFF) else Color.Transparent)
                    .tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(rowIcon(row.id), ink, 18.dp, filled = row.id == "resume")
                    Text(row.title, Typo.Body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), Modifier.weight(1f), ink, lines = 1)
                    row.value?.let { Text(it, Typo.Caption, color = if (on) Palette.OnLabelSecondary else Palette.Tertiary, lines = 1) }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

private fun rowIcon(id: String) = when (id) {
    "resume" -> ICON_RESUME
    "states" -> ICON_STATES
    "controls" -> ICON_CONTROLS
    "core" -> ICON_CORE
    "display" -> ICON_DISPLAY
    "disc" -> ICON_DISC
    "reset" -> ICON_RESET
    else -> ICON_QUIT
}

/** A 24-unit outline icon from the mockups' SVG paths. */
@Composable private fun Icon(path: String, color: Color, size: Dp, filled: Boolean = false) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, Offset.Zero) {
            if (filled) drawPath(parsed, color)
            else drawPath(parsed, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val ICON_RESUME = "M7 4.5v15l12-7.5z"
private const val ICON_STATES = "M5 3h11l3 3v15H5zM8 3v5h7V3M8 21v-7h8v7"
private const val ICON_CONTROLS = "M7 8h10a5 5 0 0 1 4.8 6.3l-1 3.4a2.5 2.5 0 0 1-4.3.9L14.5 16h-5l-2 2.6a2.5 2.5 0 0 1-4.3-.9l-1-3.4A5 5 0 0 1 7 8zM8 11v4M6 13h4M16 12h.01M18 14h.01"
private const val ICON_CORE = "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4zM9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3"
private const val ICON_DISPLAY = "M3 5h18v12H3zM8 21h8M12 17v4"
private const val ICON_DISC = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"
private const val ICON_RESET = "M4 12a8 8 0 1 0 2.3-5.7M4 4v4h4"
private const val ICON_QUIT = "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10"
private const val ICON_FAST = "M3 6v12l8-6zM13 6v12l8-6z"
private const val ICON_SOUND = "M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"
private const val ICON_MUTED = "M4 9h4l5-4v14l-5-4H4zM17 9l4 6M21 9l-4 6"

/** What the hint bar says while the sidebar has the focus. */
private fun sidebarHints(row: MenuRow?, swapped: Boolean, shell: ConsoleShell): List<Hint> = buildList {
    if (row != null && row.enabled) add(Hint(Pad.confirm(swapped), when (row.id) {
        "quick:mute" -> if (row.checked == true) "Sound on" else "Mute"
        "quick:fast" -> "Resume fast"
        "resume" -> "Resume"; "reset" -> "Reset"; "eject" -> "Quit"
        else -> "Open"
    }, primary = true, tag = "pause-select") { shell.select() })
    add(Hint(Pad.back(swapped), "Resume", tag = "back") { shell.back() })
}

// ---- Panel chrome ------------------------------------------------------------------------------------------

@Composable private fun Panel(title: String, subtitle: String?, modifier: Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glass().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Typo.Title.copy(fontSize = 26.sp), Modifier.testTag("pause-panel-title"), lines = 1)
                subtitle?.let { Text(it, Typo.Caption, color = Palette.Secondary, lines = 1) }
            }
            trailing()
        }
        content()
    }
}

@Composable private fun Footer(context: String?, hints: List<Hint>, modifier: Modifier) {
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(context.orEmpty(), Typo.Small.tabular(), Modifier.weight(1f).padding(end = 16.dp).testTag("pause-context"), Palette.Quiet, lines = 2)
        HintRow(hints, gap = 24.dp)
    }
}

/** A segmented control of [options], the selected one a white pill. */
@Composable private fun Segments(options: List<String>, selected: Int, onPick: ((Int) -> Unit)? = null) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, title ->
            val on = index == selected
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Palette.Label else Color.Transparent)
                .then(if (onPick != null) Modifier.tap("segment-$index") { onPick(index) } else Modifier).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(title, Typo.Caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), color = if (on) Palette.Canvas else Palette.Label, lines = 1)
            }
        }
    }
}

/** Keeps the focused item of a scrolling column in view. */
@Composable private fun FollowFocus(scroll: ScrollState, top: Float, height: Float, viewport: Float) {
    val density = LocalDensity.current
    LaunchedEffect(top, height, viewport) {
        with(density) {
            val start = top.dp.toPx(); val end = (top + height).dp.toPx(); val view = viewport.dp.toPx()
            when {
                start < scroll.value -> scroll.animateScrollTo(start.toInt())
                end > scroll.value + view -> scroll.animateScrollTo((end - view).toInt())
            }
        }
    }
}

// ---- Save States ---------------------------------------------------------------------------------------------

@Composable private fun StatesPanel(page: Page, shell: ConsoleShell, active: Boolean, game: LibraryGame, modifier: Modifier, footer: Modifier,
    swapped: Boolean, sidebar: Page, sidebarRow: MenuRow?) {
    val rows = page.rows.filter { it.focusable }
    val focused = if (active) shell.focused(page) else null
    // Every tile is drawn at the game's own picture shape, read from the first thumbnail there is.
    val firstPicture = rows.firstNotNullOfOrNull { it.picture?.file }
    val aspect = rememberAspect(firstPicture) ?: if ((game.system.media == dev.cartridge.domain.Media.HandheldBox)) 1.5f else 4f / 3f
    val tileW = 268f; val tileH = (tileW / aspect).coerceIn(150f, 201f)
    val rowH = tileH + 10f + 18f + 16f
    Panel(page.title, page.subtitle, modifier) {
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.padding(top = 22.dp).fillMaxSize()) {
            val viewport = maxHeight.value
            val index = rows.indexOf(focused).coerceAtLeast(0)
            if (active) FollowFocus(scroll, (index / 3) * rowH, rowH, viewport)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                page.rows.filter { !it.focusable }.forEach { info -> Text(info.title, Typo.Callout, color = Palette.Secondary, lines = 1) }
                rows.chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        line.forEach { row -> StateTile(row, row === focused, tileW, tileH, Modifier.tap(row.tag, row.enabled) {
                            if (!active) shell.activate(sidebar, sidebar.rows.first { it.id == "states" }) else { shell.focus(page, row); shell.activate(page, row) } }) }
                    }
                }
            }
        }
    }
    val row = focused
    val hints = if (!active) sidebarHints(sidebarRow, swapped, shell) else buildList {
        if (row != null && row.enabled) add(Hint(Pad.confirm(swapped), if (row.id == "new") "Save" else "Load", primary = true, tag = "slot-select") { shell.select() })
        if (row?.id?.startsWith("slot:") == true && row.kind == RowKind.Link) {
            add(Hint(Pad.X, "Overwrite", tag = "slot-overwrite") { shell.alternate() })
            add(Hint(Pad.Y, "Delete", tag = "slot-delete") { shell.secondary() })
        }
        add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
    }
    Footer(if (active) row?.let { if (it.id == "new") it.note else "${it.title} · ${it.note}" } else null, hints, footer)
}

@Composable private fun StateTile(row: MenuRow, focused: Boolean, width: Float, height: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.width(width.dp).alpha(if (row.enabled || row.id == "new") 1f else .45f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.id == "new") {
            Box(Modifier.size(width.dp, height.dp).focusLift(focused, shape, ring = 3.dp).clip(shape).background(Color(0x0AFFFFFF)).dashed(focused), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon("M12 5v14M5 12h14", Palette.Quiet, 28.dp)
                    Text(row.title, Typo.Callout.copy(fontWeight = FontWeight.SemiBold), color = Palette.Quiet, lines = 1)
                }
            }
            Text("Empty", Typo.Caption, Modifier.fillMaxWidth(), Palette.Tertiary, lines = 1)
        } else {
            Box(Modifier.size(width.dp, height.dp).focusLift(focused, shape, ring = 3.dp, elevation = 40.dp).clip(shape).background(Color(0xFF2C2C2E))
                .border(1.dp, Color(0x1AFFFFFF), shape)) {
                val picture = rememberThumbnail(row.picture?.file, row.picture?.stamp ?: 0L)
                if (picture != null) Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                badge(row.title)?.let { label ->
                    Box(Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(label, Typo.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .44.sp), lines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, Typo.Caption.copy(fontWeight = FontWeight.SemiBold), Modifier.weight(1f), lines = 1)
                row.value?.let { Text(it.replaceFirstChar(Char::uppercase), Typo.Caption.tabular(), color = Palette.Tertiary, lines = 1) }
            }
        }
    }
}

private fun badge(title: String) = when {
    title == "Quick save" -> "QUICK"
    title.startsWith("Before") -> "BEFORE QUICK SAVE"
    title.startsWith("Disc ") -> title.substringBefore(" ·").uppercase()
    else -> null
}

/** The dashed outline of the New save state tile; solid white once it has the focus ring instead. */
private fun Modifier.dashed(focused: Boolean) = if (focused) this else drawBehind {
    val inset = .75.dp.toPx()
    drawRoundRect(Color(0x47FFFFFF), Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(14.dp.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}

/** A save state's thumbnail, decoded small off the main thread; [stamp] changes when the file is replaced. */
@Composable private fun rememberThumbnail(file: java.io.File?, stamp: Long): Bitmap? {
    val picture by produceState<Bitmap?>(null, file?.path, stamp) {
        value = file?.let { withContext(Dispatchers.IO) { runCatching { decodeSmall(it, 540) }.getOrNull() } }
    }
    return picture
}
@Composable private fun rememberAspect(file: java.io.File?): Float? {
    val aspect by produceState<Float?>(null, file?.path) {
        value = file?.let { withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { o -> BitmapFactory.decodeFile(it.path, o) } }.getOrNull()
                ?.takeIf { it.outWidth > 0 && it.outHeight > 0 }?.let { it.outWidth.toFloat() / it.outHeight } } }
    }
    return aspect
}
private fun decodeSmall(file: java.io.File, width: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

// ---- Controls ------------------------------------------------------------------------------------------------

@Composable private fun ControlsPanel(page: Page, shell: ConsoleShell, active: Boolean, system: GameSystem, listening: PadKey?, listen: Page?,
    modifier: Modifier, footer: Modifier, swapped: Boolean, sidebar: Page, sidebarRow: MenuRow?) {
    val focused = if (active && listening == null) shell.focused(page) else null
    val controller = rememberController()
    Panel("Controls", null, modifier, trailing = { Segments(listOf("Player 1"), 0) }) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (controller != null) Palette.Success else Palette.Tertiary))
            Text(controller ?: "No controller connected", Typo.Caption, color = Palette.Secondary, lines = 1)
        }
        Row(Modifier.padding(top = 26.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(352.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val glowing = listening ?: focused?.id?.removePrefix("button:")?.let { id -> PadKey.entries.firstOrNull { it.name == id } }
                PadDiagram(system, glowing, listening != null)
                page.rows.firstOrNull { it.id == "default" }?.let { row ->
                    val on = row === focused
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Label else Color(0x0FFFFFFF))
                        .tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.title, Typo.Callout.copy(fontWeight = FontWeight.SemiBold), color = if (on) Palette.Canvas else if (row.enabled) Palette.Label else Palette.Tertiary, lines = 1)
                        Text(if (row.enabled) "Back to ${row.note}" else "These are the default buttons", Typo.Small, color = if (on) Palette.OnLabelSecondary else Palette.Secondary, lines = 2)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    Text("GAME BUTTON", Typo.Section, Modifier.weight(1f), Palette.Tertiary, lines = 1)
                    Text("YOUR CONTROLLER", Typo.Section, color = Palette.Tertiary, lines = 1)
                }
                MapRow("D-Pad", "D-Pad", focused = false, listening = false, enabled = false, Modifier)
                page.rows.filter { it.id.startsWith("button:") }.forEach { row ->
                    val key = row.id.removePrefix("button:")
                    val heard = listening?.name == key
                    MapRow(row.title, if (heard) listen?.subtitle?.takeIf { it.startsWith("Pressing") } ?: "Press a button…" else row.value.orEmpty(),
                        row === focused, heard, true, Modifier.tap(row.tag, row.enabled) {
                            if (!active) shell.activate(sidebar, sidebar.rows.first { it.id == "controls" }) else { shell.focus(page, row); shell.activate(page, row) } })
                }
            }
        }
    }
    val hints = when {
        !active -> sidebarHints(sidebarRow, swapped, shell)
        listening != null -> listOf(Hint(Pad.back(swapped), "Keep", tag = "record-cancel") { shell.back() })
        else -> buildList {
            if (focused != null) add(Hint(Pad.confirm(swapped), if (focused.id == "default") "Use defaults" else "Remap", primary = true, tag = "controls-select") { shell.select() })
            if (focused?.adjust != null) add(Hint(Pad.DpadX, "Change"))
            add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
        }
    }
    val context = when {
        !active -> null
        listening != null -> listen?.body
        else -> "Changes apply to all ${system.title} games"
    }
    Footer(context, hints, footer)
}

@Composable private fun MapRow(game: String, device: String, focused: Boolean, listening: Boolean, enabled: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp).height(44.dp).clip(shape)
        .background(when { listening -> Color(0x2E0A84FF); focused -> Palette.Label; else -> Color.Transparent })
        .then(if (listening) Modifier.border(2.dp, Palette.Accent, shape) else Modifier).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(game, Typo.Callout.copy(fontWeight = FontWeight.SemiBold), Modifier.weight(1f), if (focused) Palette.Canvas else Palette.Label, lines = 1)
        Text(device, Typo.Caption, color = when { listening -> Color(0xFF64B5FF); focused -> Palette.OnLabelSecondary; enabled -> Palette.Quiet; else -> Palette.Tertiary }, lines = 1)
    }
}

/**
 * The emulated console's own buttons (spec 4.5), drawn from its pad: a D-pad, its face buttons where that console has
 * them, its shoulders and Start and Select. [glow] is the button being remapped or focused; [listening] makes it blue.
 */
@Composable private fun PadDiagram(system: GameSystem, glow: PadKey?, listening: Boolean) {
    val pad = system.pad
    val handheld = system.media == dev.cartridge.domain.Media.HandheldBox
    Box(Modifier.size(352.dp, 236.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x47000000))) {
        val names = pad.associate { it.key to it.name }
        // Shoulders.
        listOf(PadKey.L1 to 40, PadKey.R1 to 242).forEach { (key, x) ->
            names[key]?.let { Shoulder(it, glow == key, listening, Modifier.absoluteOffset(x.dp, 44.dp)) }
        }
        listOf(PadKey.L2 to 54, PadKey.R2 to 256).forEach { (key, x) ->
            names[key]?.let { Shoulder(it, glow == key, listening, Modifier.absoluteOffset(x.dp, 30.dp), small = true) }
        }
        Box(Modifier.absoluteOffset(30.dp, 56.dp).size(292.dp, 134.dp).clip(RoundedCornerShape(48.dp)).background(Color(0xFF2C2C2E)))
        if (handheld) Box(Modifier.absoluteOffset(116.dp, 76.dp).size(120.dp, 82.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF141416))
            .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(8.dp)))
        // D-pad.
        Box(Modifier.absoluteOffset(66.dp, 94.dp).size(18.dp, 52.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF48484A)))
        Box(Modifier.absoluteOffset(49.dp, 111.dp).size(52.dp, 18.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF48484A)))
        // Start and Select, under the D-pad on a handheld and in the middle on a pad.
        val small = listOf(PadKey.Select, PadKey.Start).filter { it in names }
        small.forEachIndexed { index, key ->
            val x = if (handheld) 52 + index * 26 else 146 + index * 34
            val y = if (handheld) 162 else 150
            val on = glow == key
            Box(Modifier.absoluteOffset(x.dp, y.dp).size(if (handheld) 18.dp else 26.dp, 7.dp).clip(RoundedCornerShape(4.dp))
                .background(if (on) (if (listening) Palette.Accent else Palette.Label) else Color(0xFF48484A)))
        }
        // Face buttons: the console's east, south, north and west, or two on a diagonal.
        val faces = listOf(PadKey.A, PadKey.B, PadKey.X, PadKey.Y).filter { it in names }
        val spots: Map<PadKey, Pair<Int, Int>> = if (faces.size <= 2) mapOf(PadKey.A to (276 to 92), PadKey.B to (246 to 118))
            else mapOf(PadKey.A to (282 to 104), PadKey.B to (254 to 130), PadKey.X to (254 to 78), PadKey.Y to (226 to 104))
        faces.forEach { key ->
            val (x, y) = spots.getValue(key)
            val on = glow == key
            val fill = when { on && listening -> Palette.Accent; on -> Palette.Label; else -> Color(0xFF636366) }
            Box(Modifier.absoluteOffset(x.dp, y.dp).size(28.dp).then(if (on && listening) Modifier.border(4.dp, Color(0x590A84FF), CircleShape) else Modifier)
                .clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Text(names.getValue(key).take(1), Typo.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center),
                    color = if (on && !listening) Palette.Canvas else Palette.Label, lines = 1)
            }
        }
        Text("Game layout · ${system.shortName}", Typo.Small, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), Palette.Secondary, lines = 1)
    }
}

@Composable private fun Shoulder(name: String, on: Boolean, listening: Boolean, modifier: Modifier, small: Boolean = false) {
    Box(modifier.size(if (small) 42.dp else 70.dp, 18.dp).clip(RoundedCornerShape(9.dp, 9.dp, 3.dp, 3.dp))
        .background(when { on && listening -> Palette.Accent; on -> Palette.Label; else -> Color(0xFF3A3A3C) }), contentAlignment = Alignment.Center) {
        Text(name, Typo.Small.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold), color = if (on && !listening) Palette.Canvas else Palette.Quiet, lines = 1)
    }
}

/** The first connected game controller, its name and, where Android reports it, its battery. */
@Composable private fun rememberController(): String? {
    val context = LocalContext.current
    return remember {
        val manager = context.getSystemService(InputManager::class.java)
        manager?.inputDeviceIds?.asSequence()?.mapNotNull { manager.getInputDevice(it) }
            ?.firstOrNull { !it.isVirtual && (it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) }?.let { device ->
                val battery = if (Build.VERSION.SDK_INT >= 31) device.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { "${(it * 100).toInt()}% battery" } else null
                listOfNotNull(device.name, "connected", battery).joinToString(" · ")
            }
    }
}

// ---- Core Settings -------------------------------------------------------------------------------------------

@Composable private fun CorePanel(page: Page, shell: ConsoleShell, active: Boolean, game: LibraryGame, frame: Bitmap?, modifier: Modifier, footer: Modifier,
    swapped: Boolean, sidebar: Page, sidebarRow: MenuRow?) {
    val focused = if (active) shell.focused(page) else null
    val core = game.system.core
    fun open(row: MenuRow) { if (!active) shell.activate(sidebar, sidebar.rows.first { it.id == if (row.id.startsWith("display:")) "display" else "core" }) else { shell.focus(page, row); shell.activate(page, row) } }
    Panel("Core Settings", "Most changes show at once · some from the next start", modifier, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) Glyph(Pad.L1)
            Segments(listOf("This game", "All ${game.system.shortName} games"), if (shell.coreForConsole) 1 else 0) { shell.changeCoreScope(it == 1) }
            if (active) Glyph(Pad.R1)
        }
    }) {
        Row(Modifier.padding(top = 22.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val scroll = rememberScrollState()
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Label), contentAlignment = Alignment.Center) {
                        Icon("M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4zM9 9h6v6H9z", Palette.Canvas, 20.dp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(core.engine.substringBeforeLast(" "), Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), lines = 1)
                            Text(core.engine.substringAfterLast(" "), Typo.Caption, color = Palette.Tertiary, lines = 1)
                        }
                        Text("Plays ${core.title} games", Typo.Small, color = Palette.Secondary, lines = 1)
                    }
                }
                listOf("start" to "Starting", "emu" to "Emulation").forEach { (group, title) ->
                    val rows = page.rows.filter { it.id.startsWith("$group:") }
                    if (rows.isNotEmpty()) Group(title, rows, focused, ::open)
                }
            }
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DISPLAY", Typo.Section, Modifier.padding(start = 16.dp), Palette.Tertiary, lines = 1)
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(292.dp, 195.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Canvas)) {
                        if (frame != null && !frame.isRecycled) Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xA6000000)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text("Paused frame", Typo.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), lines = 1)
                        }
                    }
                    Column {
                        page.rows.filter { it.id.startsWith("display:") }.forEach { row -> SettingRow(row, row === focused, compact = true) { open(row) } }
                    }
                }
            }
        }
    }
    val hints = if (!active) sidebarHints(sidebarRow, swapped, shell) else buildList {
        if (focused?.kind == RowKind.Link) add(Hint(Pad.confirm(swapped), "Choose", primary = true, tag = "core-select") { shell.select() })
        if (focused?.adjust != null) add(Hint(Pad.DpadX, "Adjust"))
        if (focused?.adjust != null) add(Hint(Pad.X, if (shell.coreForConsole) "Reset to default" else "Same as all games", tag = "core-reset") { shell.alternate() })
        add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
    }
    Footer(if (active) focused?.note else null, hints, footer)
}

@Composable private fun Group(title: String, rows: List<MenuRow>, focused: MenuRow?, open: (MenuRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), Typo.Section, Modifier.padding(start = 16.dp), Palette.Tertiary, lines = 1)
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).padding(4.dp)) {
            rows.forEach { row -> SettingRow(row, row === focused) { open(row) } }
        }
    }
}

/** One setting: its value and a chevron, or, focused, a white row with − value + for a choice. */
@Composable private fun SettingRow(row: MenuRow, focused: Boolean, compact: Boolean = false, onTap: () -> Unit) {
    val choice = row.adjust != null
    Row(Modifier.fillMaxWidth().height(if (compact) 40.dp else 44.dp).clip(RoundedCornerShape(12.dp)).background(if (focused) Palette.Label else Color.Transparent)
        .tap(row.tag, row.enabled, onTap).padding(horizontal = if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, Typo.Callout.copy(fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium), Modifier.weight(1f),
            if (focused) Palette.Canvas else if (row.warn) Palette.DestructiveText else Palette.Label, lines = 1)
        if (focused && choice) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Step("−") { row.adjust?.invoke(-1) }
            Text(row.value.orEmpty(), Typo.Callout.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center).tabular(), Modifier.widthIn(min = 56.dp), Palette.Canvas, lines = 1)
            Step("+") { row.adjust?.invoke(1) }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.value.orEmpty(), Typo.Callout, color = if (focused) Palette.OnLabelSecondary else Palette.Secondary, lines = 1)
            Icon("M9 6l6 6-6 6", if (focused) Palette.OnLabelSecondary else Palette.Tertiary, 14.dp)
        }
    }
}

@Composable private fun Step(sign: String, action: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x14000000)).tap("step-$sign", action = action), contentAlignment = Alignment.Center) {
        Text(sign, Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Palette.Canvas, lines = 1)
    }
}
