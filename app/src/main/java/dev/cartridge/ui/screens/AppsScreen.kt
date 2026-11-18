package dev.cartridge.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.apps.AppEntry
import dev.cartridge.apps.AppLibrary
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.Tab
import dev.cartridge.shell.plural
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*

/**
 * Apps (spec 4.7, Apps.dc.html): the apps installed on this device, with their own icons and names. The player's
 * pinned apps lead in a row of larger icons, then every other app eight across. A opens one full screen, X pins or
 * unpins it, Menu opens Android's page for it and Y searches.
 */
@Composable fun AppsScreen(shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val apps = shell.apps
    val entries = apps.entries
    val ready = apps.phase == AppLibrary.Phase.Ready
    SideEffect { apps.columns = COLUMNS }
    val focused = if (active) apps.focused else null
    val homeNote = remember { runCatching { shell.host.homeEnabled }.getOrDefault(false) }

    Box(modifier.fillMaxSize().background(Palette.Canvas).testTag("apps-screen")) {
        TopBar(Tab.Apps, shell::openTab, device, showStatus = shell.showStatus, showPercent = shell.showPercent)
        Row(Modifier.padding(top = 92.dp).padding(horizontal = Layout.Margin).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Apps", Typo.Title, Modifier.testTag("apps-title"), lines = 1)
            Text(when {
                apps.searching -> "“${apps.query}” · " + if (entries.isEmpty()) "no results" else plural(entries.size, "result")
                ready -> "${entries.size} installed"
                else -> ""
            }, Typo.Body.copy(fontWeight = FontWeight.Normal), Modifier.padding(start = 14.dp, top = 4.dp).weight(1f).testTag("apps-count"), Palette.Secondary, lines = 1)
            SearchField(apps.query, apps.searchable) { shell.searchApps() }
        }
        Box(Modifier.padding(top = 148.dp, bottom = Layout.HintBar).fillMaxSize()) {
            when {
                entries.isNotEmpty() -> Grid(apps, shell, focused)
                // The first read takes a moment. Like a game starting, it shows nothing rather than a loading line.
                apps.phase == AppLibrary.Phase.Unread -> Unit
                apps.searching -> Empty("No apps match “${apps.query}”", "Search looks at the names of the apps installed on this device.")
                apps.phase == AppLibrary.Phase.Failed -> Empty("Apps couldn't be listed", "Android didn't answer. Your games are not affected.")
                else -> Empty("No apps to show", "Apps installed on this device appear here.")
            }
        }
        val hints = buildList {
            if (focused != null) {
                add(Hint(Pad.confirm(swapped), "Open", primary = true, tag = "apps-open") { shell.select() })
                if (!apps.searching) add(Hint(Pad.X, if (apps.pinned(focused)) "Unpin" else "Pin", tag = "apps-pin") { shell.alternate() })
                add(Hint(Pad.Menu, "App info", tag = "apps-info") { shell.menu() })
            } else if (active && !apps.searching && apps.phase != AppLibrary.Phase.Unread)
                add(Hint(Pad.confirm(swapped), "Try again", primary = true, tag = "apps-retry") { shell.select() })
            add(Hint(Pad.back(swapped), if (apps.searching) "Clear search" else "Back", tag = "back") { shell.back() })
        }
        HintBar(hints, Modifier.align(Alignment.BottomStart).testTag("apps-hints"),
            context = if (entries.isEmpty()) null else if (homeNote) "Apps open full screen · press Home to return to Cartridge" else "Apps open full screen")
    }
}

/** The search pill at the right of the title, with Y in it; it shows what is being searched for. */
@Composable private fun SearchField(query: String, enabled: Boolean, open: () -> Unit) {
    Row(Modifier.width(280.dp).height(38.dp).clip(RoundedCornerShape(Radii.Pill)).background(Color(0x14FFFFFF))
        .tap("apps-search", enabled, open).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = 1.6.dp.toPx()
            drawCircle(Palette.Secondary, size.width * .36f, Offset(size.width * .42f, size.height * .42f), style = Stroke(stroke))
            drawLine(Palette.Secondary, Offset(size.width * .7f, size.height * .7f), Offset(size.width * .95f, size.height * .95f), stroke)
        }
        Text(query.ifEmpty { "Search apps" }, Typo.Callout, Modifier.weight(1f), if (query.isEmpty()) Palette.Tertiary else Palette.Label, lines = 1)
        Glyph(Pad.Y, size = 22.dp)
    }
}

@Composable private fun BoxScope.Empty(title: String, body: String) {
    Column(Modifier.align(Alignment.Center).widthIn(max = 460.dp).testTag("apps-empty"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Typo.Headline.copy(textAlign = TextAlign.Center), lines = 2)
        Text(body, Typo.Callout.copy(textAlign = TextAlign.Center), color = Palette.Secondary, lines = 3)
    }
}

/**
 * The Pinned row and then all apps, as fixed-height lines so the focused one can be kept in view: a line that would
 * fall under the hints scrolls up, and one above the top scrolls down, its section's label with it.
 */
@Composable private fun Grid(apps: AppLibrary, shell: ConsoleShell, focused: AppEntry?) {
    val pinned = apps.pinnedCount
    val pinnedLines = apps.entries.take(pinned).chunked(COLUMNS)
    val otherLines = apps.entries.drop(pinned).chunked(COLUMNS)
    val labels = pinned > 0
    // Where each line starts, in the order drawn.
    val tops = buildList {
        var y = 0f
        if (labels) y += LABEL_H
        pinnedLines.forEach { add(y); y += PINNED_H }
        if (labels) y += SECTION_GAP + LABEL_H
        otherLines.forEach { add(y); y += LINE_H }
    }
    val lines = pinnedLines + otherLines
    val line = lines.indexOfFirst { focused in it }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val iconPixels = with(density) { PINNED_ICON.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight.value
        LaunchedEffect(line, viewport) {
            if (line < 0) return@LaunchedEffect
            // A section's first line brings its label into view as well.
            val first = line == 0 || line == pinnedLines.size
            val top = tops[line] - if (first && labels) LABEL_H else 0f
            val bottom = tops[line] + if (line < pinnedLines.size) PINNED_H else LINE_H
            with(density) {
                when {
                    top.dp.toPx() < scroll.value -> scroll.animateScrollTo(top.dp.toPx().toInt())
                    bottom.dp.toPx() > scroll.value + viewport.dp.toPx() -> scroll.animateScrollTo((bottom - viewport).dp.toPx().toInt())
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Layout.Margin).testTag("apps-grid")) {
            if (labels) SectionLabel("PINNED")
            pinnedLines.forEach { row -> Line(row, apps, shell, focused, PINNED_ICON, PINNED_H, iconPixels) }
            if (labels) { Spacer(Modifier.height(SECTION_GAP.dp)); SectionLabel("ALL APPS") }
            otherLines.forEach { row -> Line(row, apps, shell, focused, ICON, LINE_H, iconPixels) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun SectionLabel(title: String) {
    Text(title, Typo.Section, Modifier.height(LABEL_H.dp), Palette.Secondary, lines = 1)
}

@Composable private fun Line(row: List<AppEntry>, apps: AppLibrary, shell: ConsoleShell, focused: AppEntry?, icon: Dp, height: Float, iconPixels: Int) {
    Row(Modifier.fillMaxWidth().height(height.dp)) {
        row.forEach { entry ->
            Tile(apps, entry, entry == focused, icon, iconPixels, Modifier.weight(1f)) { apps.focus(entry); shell.launchApp(entry) }
        }
        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** One app: its own icon and name. Focused, the icon comes forward with a white ring and the name becomes a white pill. */
@Composable private fun Tile(apps: AppLibrary, entry: AppEntry, focused: Boolean, size: Dp, iconPixels: Int, modifier: Modifier, open: () -> Unit) {
    val epoch = apps.iconEpoch
    var missing by remember(entry.id, epoch) { mutableStateOf(false) }
    val icon by produceState<Bitmap?>(apps.cachedIcon(entry), entry.id, epoch) {
        val loaded = apps.icon(entry, iconPixels)
        value = loaded; missing = loaded == null
    }
    val shape = RoundedCornerShape(size * .26f)
    Column(modifier.padding(top = 8.dp).tap("app:${entry.id}", action = open).semantics { selected = focused; contentDescription = entry.label },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).focusLift(focused, shape, scale = 1.14f, ring = 3.dp, elevation = 30.dp), contentAlignment = Alignment.Center) {
            val bitmap = icon
            if (bitmap != null && !bitmap.isRecycled) Image(remember(bitmap) { bitmap.asImageBitmap() }, null, Modifier.fillMaxSize())
            // An app Android has no icon for still gets a face: its initial, set like a game without artwork.
            else if (missing) Box(Modifier.fillMaxSize().clip(shape).background(Palette.Surface3), contentAlignment = Alignment.Center) {
                Text(entry.label.firstOrNull(Char::isLetterOrDigit)?.uppercase().orEmpty(), Typo.Title, color = Palette.Soft, lines = 1)
            }
        }
        Box(Modifier.padding(top = if (focused) 14.dp else 10.dp).widthIn(max = 140.dp), contentAlignment = Alignment.Center) {
            if (focused) Box(Modifier.clip(RoundedCornerShape(Radii.Pill)).background(Palette.Label).padding(horizontal = 10.dp, vertical = 2.dp)) {
                Text(entry.label, Typo.Caption.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), color = Palette.Canvas, lines = 1)
            } else Text(entry.label, Typo.Callout.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, textAlign = TextAlign.Center), color = Palette.Quiet, lines = 1)
        }
    }
}

private const val COLUMNS = 8
private val PINNED_ICON = 84.dp
private val ICON = 72.dp
private const val PINNED_H = 150f
private const val LINE_H = 122f
private const val LABEL_H = 22f
private const val SECTION_GAP = 18f
