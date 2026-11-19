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
import androidx.compose.ui.graphics.graphicsLayer
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
        // Another section's content slides in a little from the direction the focus moved, as it fades in.
        val motion = LocalDesignMotion.current
        val category = shell.category
        var shownCategory by remember { mutableStateOf(category) }
        var direction by remember { mutableStateOf(1f) }
        val arrival = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(category) {
            if (category == shownCategory) return@LaunchedEffect
            direction = if (category.ordinal > shownCategory.ordinal) 1f else -1f
            shownCategory = category
            arrival.snapTo(0f); arrival.animateTo(1f, motion.progress(DesignMotion.FADE_MS + 40))
        }
        val sectionTravel = motion.travel(24f)
        Column(Modifier.padding(start = 352.dp, top = 96.dp, end = Layout.Margin, bottom = Layout.HintBar).fillMaxSize()
            .graphicsLayer { alpha = arrival.value; translationY = ((1f - arrival.value) * sectionTravel * direction).dp.toPx() }) {
            Text(shell.category.title, Typo.Title, Modifier.padding(bottom = 20.dp).testTag("settings-title"), lines = 1)
            val scroll = rememberScrollState()
            LaunchedEffect(shell.category) { scroll.scrollTo(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 16.dp)) {
                val groups = remember(page.rows) { rowGroups(page.rows) }
                val open = { row: MenuRow -> shell.enterRows(); shell.activate(page, row) }
                if (shell.category == SettingsCategory.Library) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            groups.filter { it.title != BOX_ART }.forEach { RowCard(it, focused, buttons = BUTTONS, onClick = open) }
                        }
                        groups.firstOrNull { it.title == BOX_ART }?.let { art ->
                            RowCard(art, focused, Modifier.width(340.dp), buttons = BUTTONS, top = { Preview(state) }, onClick = open)
                        }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { groups.forEach { RowCard(it, focused, buttons = BUTTONS, onClick = open) } }
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
            // The hints themselves are the stage's; the help beside them is this screen's own.
            ScreenHints(hints, active)
        }
    }
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
                    OutlineIcon(icon, Color.White, 16.dp, 2.2f)
                }
                Text(category.title, Typo.Body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (lit) Palette.Canvas else Palette.Label, lines = 1)
            }
        }
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
    GameBox(look, (w * .5f).dp, (h * .5f).dp, angle, Modifier.semantics { contentDescription = look.title }, focused = true, reflection = false)
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

/** The rows whose value is a verb, drawn as a button: Library's Scan now and Check. */
private val BUTTONS = setOf("refresh", "verify")
