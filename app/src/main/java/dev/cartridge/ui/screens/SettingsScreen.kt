package dev.cartridge.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.library.LibraryGame
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.BOX_ART
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.Destination
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.Page
import dev.cartridge.shell.RowKind
import dev.cartridge.shell.SettingsCategory
import dev.cartridge.shell.Tab
import dev.cartridge.shell.appVersion
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular

/**
 * Settings (spec 4.8, Settings.dc.html): the sections down the left, each with its coloured tile, and the chosen
 * section's settings beside them in grouped cards. The Library section puts its Box art card, with a live preview of
 * two of the player's own boxes, in a column of its own. L1 / R1 change section; the focused row's help is at the foot.
 */
@Composable fun SettingsScreen(state: CollectionState, shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val page = shell.page(destination = Destination.Settings) ?: return
    val inRows = shell.pane == 1
    val focused = if (active && inRows) shell.focused(page) else null
    val context = LocalContext.current
    val version = remember { shell.appVersion() }
    val isHome = remember(shell.homeEnabled) {
        runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    Box(modifier.fillMaxSize().background(Palette.Canvas).testTag("settings")) {
        TopBar(Tab.Settings, shell::openTab, device, showStatus = shell.showStatus, showPercent = shell.showPercent)
        Sections(shell, active && !inRows, Modifier.padding(start = Layout.Margin, top = 100.dp).width(280.dp))
        Column(Modifier.padding(start = 352.dp, top = 96.dp, end = Layout.Margin, bottom = Layout.HintBar).fillMaxSize()) {
            Text(shell.category.title, Typo.Title, Modifier.padding(bottom = 20.dp).testTag("settings-title"), lines = 1)
            val scroll = rememberScrollState()
            LaunchedEffect(shell.category) { scroll.scrollTo(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 16.dp)) {
                val groups = remember(page.rows) { groups(page.rows) }
                if (shell.category == SettingsCategory.Library) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            groups.filter { it.title != BOX_ART }.forEach { Group(it, page, focused, shell) }
                        }
                        groups.firstOrNull { it.title == BOX_ART }?.let { art ->
                            Group(art, page, focused, shell, Modifier.width(340.dp)) { Preview(state) }
                        }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { groups.forEach { Group(it, page, focused, shell) } }
            }
        }
        val hints = buildList {
            add(Hint(Pad.confirm(swapped), "Select", primary = true, tag = "settings-select") { shell.select() })
            if (focused?.kind == RowKind.Choice && focused.enabled) add(Hint(Pad.DpadX, "Change"))
            if (focused?.id?.startsWith("folder:") == true) add(Hint(Pad.X, "Remove folder", tag = "settings-remove-folder") { shell.alternate() })
            if (!inRows) add(Hint(Pad.R1, "Section"))
            add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
        }
        val help = focused?.note
        val footer = help ?: listOfNotNull("Cartridge $version".trim(), if (isHome) "default home app" else null).joinToString(" · ")
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(Layout.HintBar).padding(horizontal = Layout.Margin), verticalAlignment = Alignment.CenterVertically) {
            Text(footer, Typo.Caption.tabular(), Modifier.weight(1f).padding(end = 24.dp).testTag("settings-help"),
                if (help != null && focused.warn) Palette.DestructiveText else Palette.Tertiary, lines = 2)
            HintRow(hints)
        }
    }
}

/** A card of rows under its heading; rows before the first heading form a card with none. */
private class Group(val title: String?, val rows: List<MenuRow>)

private fun groups(rows: List<MenuRow>): List<Group> {
    val out = mutableListOf<Group>()
    var title: String? = null
    var current = mutableListOf<MenuRow>()
    fun flush() { if (current.isNotEmpty() || title != null) out += Group(title, current); current = mutableListOf() }
    rows.forEach { row -> if (row.kind == RowKind.Header) { flush(); title = row.title } else current += row }
    flush()
    return out.filter { it.rows.isNotEmpty() }
}

@Composable private fun Sections(shell: ConsoleShell, focusedHere: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsCategory.entries.forEach { category ->
            val current = category == shell.category
            // The list's focus is a white fill; once focus moves into the rows the section stays marked in gray.
            val lit = current && focusedHere
            val fill by animateColorAsState(when { lit -> Palette.Label; current -> Palette.Surface3; else -> Color.Transparent }, label = "section-fill")
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(fill)
                .tap("category-${category.name.lowercase()}") { shell.chooseCategory(category); shell.enterRows() }
                .semantics { selected = current }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (tile, icon) = sectionLook(category)
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tile), contentAlignment = Alignment.Center) {
                    Icon(icon, Color.White, 16.dp, 2.2f)
                }
                Text(category.title, Typo.Body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (lit) Palette.Canvas else Palette.Label, lines = 1)
            }
        }
    }
}

@Composable private fun Group(group: Group, page: Page, focused: MenuRow?, shell: ConsoleShell, modifier: Modifier = Modifier, top: (@Composable () -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.title?.let { Text(it.uppercase(), Typo.Section, Modifier.padding(start = 16.dp), Palette.Tertiary, lines = 1) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2)) {
            top?.invoke()
            Column(Modifier.padding(4.dp)) {
                // Folders read as a list of places, the rest as settings divided by hairlines.
                val lined = group.rows.none { it.id.startsWith("folder:") || it.kind == RowKind.Add }
                group.rows.forEachIndexed { index, row ->
                    val next = group.rows.getOrNull(index + 1)
                    Line(row, row === focused, page, shell)
                    if (lined && next != null && row !== focused && next !== focused)
                        Box(Modifier.padding(start = 12.dp, end = 8.dp).fillMaxWidth().height(1.dp).background(Palette.Separator))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Line(row: MenuRow, focused: Boolean, page: Page, shell: ConsoleShell) {
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bring.bringIntoView() }
    val fill by animateColorAsState(if (focused) Palette.Label else Color.Transparent, label = "row-fill")
    val ink = when {
        focused -> if (row.kind == RowKind.Danger) Palette.Destructive else Palette.Canvas
        row.kind == RowKind.Danger -> Palette.DestructiveText
        row.kind == RowKind.Add -> Palette.AccentText
        else -> Palette.Label
    }
    val sub = if (focused) Palette.OnLabelSecondary else Palette.Tertiary
    val folder = row.id.startsWith("folder:")
    Row(Modifier.fillMaxWidth().heightIn(min = if (row.detail != null) 52.dp else 48.dp).bringIntoViewRequester(bring)
        .clip(RoundedCornerShape(12.dp)).background(fill)
        .tap(row.tag, enabled = row.focusable) { shell.enterRows(); shell.activate(page, row) }
        .semantics { selected = focused }.padding(horizontal = 12.dp, vertical = 6.dp).alpha(if (row.enabled) 1f else .42f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (folder) Icon(FOLDER, ink, 20.dp, 2f)
        if (row.kind == RowKind.Add) Icon(PLUS, if (focused) Palette.Canvas else Palette.AccentText, 20.dp, 2.2f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, Typo.Callout.copy(fontWeight = if (folder || row.kind == RowKind.Add) FontWeight.SemiBold else FontWeight.Medium), color = ink, lines = 1)
            row.detail?.let { Text(it, Typo.Small.tabular(), color = if (row.warn && !focused) Palette.DestructiveText else sub, lines = 1) }
        }
        when (row.kind) {
            RowKind.Toggle -> Switch(row.checked == true)
            RowKind.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (focused) Text("‹", Typo.Callout, color = sub, lines = 1)
                Text(row.value.orEmpty(), Typo.Callout.copy(fontWeight = FontWeight.Normal).tabular(), color = if (focused) Palette.Canvas else Palette.Secondary, lines = 1)
                if (focused) Text("›", Typo.Callout, color = sub, lines = 1)
            }
            RowKind.Link -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.value?.let { Text(it, Typo.Caption.tabular(), color = if (row.warn && !focused) Palette.DestructiveText else sub, lines = 1) }
                Chevron(sub)
            }
            // Other actions open something (a keyboard, a file picker, a download): their value is where they stand.
            RowKind.Action -> if (row.id !in BUTTONS) row.value?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, Typo.Caption.tabular(), color = if (row.warn && !focused) Palette.DestructiveText else sub, lines = 1)
                    Chevron(sub)
                }
            } else row.value?.let { label ->
                // An action that does one thing at once, as Scan now, is drawn as the button it is.
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (focused) Color(0x14000000) else Color(0x1FFFFFFF)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, Typo.Caption.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Palette.Canvas else Palette.Label, lines = 1)
                }
            }
            RowKind.Info -> row.value?.let { Text(it, Typo.Callout.copy(fontWeight = FontWeight.Normal).tabular(), color = if (row.warn && !focused) Palette.DestructiveText else sub, lines = 1) }
            else -> row.value?.let { Text(it, Typo.Callout.copy(fontWeight = FontWeight.Normal), color = sub, lines = 1) }
        }
    }
}

@Composable private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Palette.Success else Color(0x52787880), label = "switch-track")
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, label = "switch-knob")
    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track)) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(Color.White))
    }
}

@Composable private fun Chevron(color: Color) {
    Canvas(Modifier.size(8.dp, 12.dp)) {
        val k = size.width / 8f
        drawLine(color, Offset(2 * k, 2 * k), Offset(6 * k, 6 * k), 2 * k, StrokeCap.Round)
        drawLine(color, Offset(6 * k, 6 * k), Offset(2 * k, 10 * k), 2 * k, StrokeCap.Round)
    }
}

/** Two of the player's own boxes as the shelf draws them, turned toward each other; flat when 3D is off. */
@Composable private fun Preview(state: CollectionState) {
    val games = remember(state.allGames) {
        val played = state.allGames.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
        (played + state.allGames.filter { it.lastPlayed <= 0 }).take(2)
    }
    Box(Modifier.fillMaxWidth().height(168.dp).background(Palette.Canvas).testTag("settings-box-preview"), contentAlignment = Alignment.BottomCenter) {
        if (games.isEmpty()) Text("Your games' boxes appear here", Typo.Caption, Modifier.align(Alignment.Center), Palette.Tertiary, lines = 1)
        else Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
            games.forEachIndexed { index, game -> PreviewBox(game, if (games.size == 1) 0f else if (index == 0) 30f else -30f) }
        }
    }
}

@Composable private fun PreviewBox(game: LibraryGame, angle: Float) {
    val media = LocalGameMedia.current
    val faces = rememberFaces(media, game.label, game.shownAs)
    val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
    val (w, h) = boxSize(game.shownAs)
    GameBox(look, (w * .5f).dp, (h * .5f).dp, 12.dp, angle, Modifier.semantics { contentDescription = look.title }, focused = true, reflection = false)
}

/** A 24-unit outline icon from the mockup's SVG paths. */
@Composable private fun Icon(path: String, color: Color, size: Dp, stroke: Float) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, Offset.Zero) { drawPath(parsed, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** Each section's tile colour and icon, as Settings.dc.html draws them. */
private fun sectionLook(category: SettingsCategory): Pair<Color, String> = when (category) {
    SettingsCategory.Library -> Color(0xFF0A84FF) to "M4 5h4v14H4zM10 5h4v14h-4zM16 6l3.5-1 2.5 13.5-3.5 1z"
    SettingsCategory.Systems -> Color(0xFF5E5CE6) to "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4zM9 9h6v6H9z"
    SettingsCategory.Controllers -> Color(0xFF30D158) to "M7 8h10a5 5 0 0 1 4.8 6.3l-1 3.4a2.5 2.5 0 0 1-4.3.9L14.5 16h-5l-2 2.6a2.5 2.5 0 0 1-4.3-.9l-1-3.4A5 5 0 0 1 7 8zM8 11v4M6 13h4"
    SettingsCategory.Display -> Color(0xFFFF9F0A) to "M3 5h18v12H3zM8 21h8M12 17v4"
    SettingsCategory.Appearance -> Color(0xFFBF5AF2) to "M12 21a9 9 0 1 1 0-18c5 0 9 3.6 9 8 0 2.8-2.2 4-4.5 4H15a2 2 0 0 0-1.5 3.3A1.6 1.6 0 0 1 12 21zM7.5 11h.01M10 7h.01M15 7h.01"
    SettingsCategory.Sound -> Color(0xFFFF375F) to "M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7"
    SettingsCategory.Storage -> Color(0xFF636366) to "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"
    SettingsCategory.Network -> Color(0xFF0A84FF) to "M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19h.01"
    SettingsCategory.Android -> Color(0xFF34C759) to "M5 10h14v9H5zM8 10V8a4 4 0 0 1 8 0v2M9 6.5L7.5 4.5M15 6.5l1.5-2"
    SettingsCategory.About -> Color(0xFF8E8E93) to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v6M12 7.5h.01"
}

private const val FOLDER = "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2h8.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"
private const val PLUS = "M12 5v14M5 12h14"
/** The rows whose value is a verb, drawn as a button: Library's Scan now and Check. */
private val BUTTONS = setOf("refresh", "verify")
