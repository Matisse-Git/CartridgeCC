package dev.cartridge.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cartridge.apps.AppEntry
import dev.cartridge.apps.AppLibrary
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.plural

/** A launcher icon at the size the hand expects of one. The focused icon is drawn at this size, the others a little under. */
private val IconSize = 56.dp
/** The least room a tile needs: its icon, two lines of name and the focus mark. The grid divides what it has by these. */
private val TileWidth = 124.dp
private val TileHeight = 108.dp

/**
 * Installed Android apps, as the library's sibling: the same ground, title, status and hints, with each app
 * shown as its own icon rather than as a cartridge it never was. What the buttons do is decided by the
 * shell; this draws [AppLibrary] and reports taps.
 */
@Composable fun AppsPage(shell: ConsoleShell, device: DeviceStatus, confirm: String, back: String) {
    val apps = shell.apps
    val entries = apps.entries
    val ready = apps.phase == AppLibrary.Phase.Ready
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column {
                Txt("Apps", Type.Title, modifier = Modifier.testTag("apps-title"), lines = 1)
                Txt(when {
                    apps.searching -> "“${apps.query}” · " + if (entries.isEmpty()) "no results" else plural(entries.size, "result")
                    ready && entries.isNotEmpty() -> plural(entries.size, "app")
                    else -> ""
                }, Type.Label, Ink.Faint, Modifier.padding(top = 7.dp).testTag("apps-count"), lines = 1)
            }
            Spacer(Modifier.weight(1f))
            // The same height as the library's status row, so the clock does not move between the two.
            if (shell.showStatus) Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { StatusCluster(device, showBattery = true, showPercent = shell.showPercent) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Space.M)) {
            when {
                entries.isNotEmpty() -> AppGrid(apps, entries, shell::launchApp)
                // The first read takes a moment. Like a game starting, it shows nothing rather than a loading line.
                apps.phase == AppLibrary.Phase.Unread -> Unit
                apps.searching -> EmptyApps("No apps match “${apps.query}”", "Search looks at the names of the apps installed on this device.")
                apps.phase == AppLibrary.Phase.Failed -> EmptyApps("Apps couldn't be listed", "Android didn't answer. Your games are not affected.")
                else -> EmptyApps("No apps to show", "Apps installed on this device appear here.")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HintBar(buildList {
                if (entries.isNotEmpty()) { add(Hint("✥", "Browse")); add(Hint(confirm, "Open")) }
                else if (!apps.searching && apps.phase != AppLibrary.Phase.Unread) add(Hint(confirm, "Try again", "apps-retry", shell::select))
                if (apps.searchable) add(Hint("Y", "Search", "apps-search", shell::searchApps))
                add(Hint(back, if (apps.searching) "Clear search" else "Back", "back", shell::back))
            }, Modifier.testTag("apps-hints"))
            Spacer(Modifier.weight(1f))
            if (entries.size > 1) Txt("${apps.focusIndex + 1} / ${entries.size}", Type.Label, Ink.Faint, Modifier.testTag("apps-position"))
        }
    }
}

/** An empty or failed state in the library's own words: what happened, then what it means. */
@Composable private fun BoxScope.EmptyApps(title: String, body: String) {
    Column(Modifier.align(Alignment.Center).widthIn(max = 420.dp).testTag("apps-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, Type.Title.copy(textAlign = TextAlign.Center), lines = 2)
        Txt(body, Type.Body.copy(textAlign = TextAlign.Center), Ink.Soft, Modifier.padding(top = Space.S))
    }
}

@Composable private fun AppGrid(apps: AppLibrary, entries: List<AppEntry>, open: (AppEntry) -> Unit) {
    val motion = LocalMotion.current
    val iconPixels = with(LocalDensity.current) { IconSize.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / TileWidth).toInt().coerceIn(3, 8)
        val rows = (maxHeight / TileHeight).toInt().coerceAtLeast(1)
        // Whole rows only: the grid's height is shared out, so no row is ever cut by the hints beneath it.
        val tile = maxHeight / rows
        SideEffect { apps.columns = columns }
        val index = apps.focusIndex
        // Opened on the app that was focused last time, already in view: nothing scrolls on arrival.
        val grid = rememberLazyGridState((index / columns - rows + 1).coerceAtLeast(0) * columns)
        LaunchedEffect(index, columns, rows, entries.size) {
            val row = index / columns
            val first = grid.firstVisibleItemIndex / columns
            val target = when {
                row < first || row == first && grid.firstVisibleItemScrollOffset > 0 -> row
                row > first + rows - 1 -> row - rows + 1
                else -> return@LaunchedEffect
            }
            // A new step cancels this one and scrolls from wherever the grid is: held input never queues.
            if (motion.reduced) grid.scrollToItem(target * columns) else grid.animateScrollToItem(target * columns)
        }
        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("apps-grid"), grid) {
            items(entries, key = { it.id }) { entry -> AppTile(apps, entry, entry.id == apps.focused?.id, tile, iconPixels) { open(entry) } }
        }
    }
}

/**
 * One app: its own icon and its own name. Focus is the console's usual one — the raised fill, the accent
 * mark and the name at full strength — and the icon comes forward a little, as a focused object does.
 */
@Composable private fun AppTile(apps: AppLibrary, entry: AppEntry, focused: Boolean, height: Dp, iconPixels: Int, open: () -> Unit) {
    val motion = LocalMotion.current
    val accent = LocalAccent.current
    val fill by animateColorAsState(if (focused) Ink.Raised else Color.Transparent, motion.focus(), label = "app-fill")
    val focus by animateFloatAsState(if (focused) 1f else 0f, motion.focus(), label = "app-focus")
    val epoch = apps.iconEpoch
    var missing by remember(entry.id, epoch) { mutableStateOf(false) }
    val icon by produceState<Bitmap?>(apps.cachedIcon(entry), entry.id, epoch) {
        val loaded = apps.icon(entry, iconPixels)
        value = loaded; missing = loaded == null
    }
    val nameHeight = with(LocalDensity.current) { (Type.Label.lineHeight * 2).toDp() }
    Column(Modifier.height(height).padding(2.dp).clip(RoundedCornerShape(Radius.Row)).background(fill).tap("app:${entry.id}", action = open)
        .semantics { selected = focused }.padding(horizontal = Space.S), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(IconSize).graphicsLayer { val scale = .9f + .1f * focus; scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            val bitmap = icon
            if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, null, Modifier.fillMaxSize())
            // An app Android has no icon for still gets a face: its initial, set in type like a game without artwork.
            else if (missing) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.Sheet)).background(Ink.Surface).border(1.dp, Ink.Line, RoundedCornerShape(Radius.Sheet)), contentAlignment = Alignment.Center) {
                Txt(entry.label.firstOrNull(Char::isLetterOrDigit)?.uppercase().orEmpty(), Type.Title, Ink.Soft)
            }
        }
        Txt(entry.label, Type.Label.copy(textAlign = TextAlign.Center, fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal),
            if (focused) Ink.Text else Ink.Soft, Modifier.padding(top = Space.S).height(nameHeight), lines = 2)
        Box(Modifier.padding(top = Space.XS).size(18.dp, 3.dp).alpha(focus).clip(CircleShape).background(accent))
    }
}
