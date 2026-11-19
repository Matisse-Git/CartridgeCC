package dev.cartridge.ui.screens

import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import dev.cartridge.R
import dev.cartridge.domain.GameSystem
import dev.cartridge.library.LibraryGame
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.SYSTEM_COLUMNS
import dev.cartridge.shell.Tab
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular

/**
 * Systems (spec 4.2, Systems.dc.html): a card for each console in the library, the most played first. Each card shows
 * the console's maker and year, a row of its games' spines (the last played first, then the rest A to Z, dimmer), how
 * many games it has and the core that plays them. A opens its games on the shelf; Y its core's options.
 */
@Composable fun SystemsScreen(state: CollectionState, shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val page = shell.page(destination = dev.cartridge.shell.Destination.Systems) ?: return
    val focused = if (active) shell.focused(page) else null
    val rows = page.rows
    val bySystem = remember(state.allGames) { state.allGames.groupBy { it.system } }
    // Every spine on one scale, in millimetres, so a short case stands short beside a tall one; the tallest fits the row.
    val scale = remember(state.allGames) { SPINE_ROW / (state.allGames.maxOfOrNull { it.shownAs.shape.height } ?: 190f) }

    Box(modifier.fillMaxSize().background(Palette.Canvas).testTag("systems-screen")) {
        TopBar(Tab.Systems, shell::openTab, device, showStatus = shell.showStatus, showPercent = shell.showPercent)
        Row(Modifier.padding(top = 96.dp).padding(horizontal = Layout.Margin).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Systems", Typo.Title, lines = 1)
            Text(page.subtitle.orEmpty(), Typo.Body.copy(fontWeight = FontWeight.Normal).tabular(), Modifier.padding(start = 14.dp, bottom = 3.dp).weight(1f), Palette.Secondary, lines = 1)
            if (rows.size > 1) Text("Sorted by most played", Typo.Caption, Modifier.padding(bottom = 3.dp), Palette.Tertiary, lines = 1)
        }
        if (rows.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No games yet", Typo.Headline, lines = 1)
                Text("Add a folder of games and each console shows up here", Typo.Callout, color = Palette.Secondary, lines = 1)
            }
        } else {
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            val line = rows.indexOf(focused).coerceAtLeast(0) / SYSTEM_COLUMNS
            // Two lines of cards fill the screen; further lines scroll in under the header as the focus reaches them.
            LaunchedEffect(line) { with(density) { scroll.animateScrollTo(((line - 1).coerceAtLeast(0) * (CARD_H + GAP)).dp.toPx().toInt()) } }
            Column(Modifier.padding(top = 136.dp, bottom = Layout.HintBar).fillMaxSize().verticalScroll(scroll).padding(horizontal = Layout.Margin, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
                rows.chunked(SYSTEM_COLUMNS).forEach { cards ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                        cards.forEach { row ->
                            val system = GameSystem.entries.first { "system:${it.name}" == row.id }
                            Card(system, bySystem[system].orEmpty(), row, row === focused, scale, Modifier.width(CARD_W.dp)
                                .tap(row.tag) { shell.focus(page, row); shell.activate(page, row) })
                        }
                    }
                }
            }
        }
        val hints = buildList {
            if (focused != null) {
                add(Hint(Pad.confirm(swapped), "Browse games", primary = true, tag = "systems-browse") { shell.select() })
                add(Hint(Pad.Y, "Core options", tag = "systems-core") { shell.secondary() })
            }
            add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
        }
        val context = focused?.let { row -> "${row.title} · core ${row.note} · ${row.value}" }
        ScreenHints(hints, active, Modifier.align(Alignment.BottomStart), context)
    }
}

@Composable private fun Card(system: GameSystem, games: List<LibraryGame>, row: MenuRow, focused: Boolean, scale: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val sub = if (focused) Palette.Quiet else Palette.Tertiary
    Column(modifier.height(CARD_H.dp).focusLift(focused, shape, scale = 1.05f, ring = 2.dp, elevation = 48.dp).clip(shape)
        .background(if (focused) Palette.Surface3 else Palette.Surface1).border(1.dp, Color(0x0DFFFFFF), shape).padding(18.dp)
        .semantics { contentDescription = "${system.title}, ${row.value}" }, verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${system.maker} · ${system.released}", Typo.Small.copy(fontWeight = FontWeight.SemiBold, letterSpacing = .24.sp), color = sub, lines = 1)
            Text(system.cardName, Typo.Headline.copy(fontSize = 20.sp), lines = 1)
        }
        Spines(games, scale, if (focused) LocalSpineRects.current else null)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(games.size.toString(), Typo.Title.copy(fontSize = 26.sp).tabular(), lines = 1)
            Text(if (games.size == 1) "game" else "games", Typo.Caption.copy(fontWeight = FontWeight.Medium), Modifier.padding(start = 6.dp, bottom = 3.dp).weight(1f), sub, lines = 1)
            Text(row.note.orEmpty(), Typo.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), Modifier.padding(bottom = 4.dp), sub, lines = 1)
        }
    }
}

/** Up to nine spines: the console's last played games first (up to three), then a gap and the rest A to Z at 62 %. */
@Composable private fun Spines(games: List<LibraryGame>, scale: Float, rects: SpineRects?) {
    val (recent, rest) = remember(games) {
        val recent = games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(RECENT)
        recent to (games - recent.toSet()).sortedBy { displayTitle(it.title).lowercase() }.take(SPINES - recent.size)
    }
    if (rects != null) SideEffect { rects.clear() }
    Row(Modifier.height(SPINE_ROW.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        recent.forEach { Spine(it, 1f, scale, rects) }
        if (recent.isNotEmpty() && rest.isNotEmpty()) Spacer(Modifier.width(3.dp))
        rest.forEach { Spine(it, .62f, scale, rects) }
    }
}

/** A game's spine: its real spine art, or its box's tone with its title running down it. */
@Composable private fun Spine(game: LibraryGame, opacity: Float, scale: Float, rects: SpineRects?) {
    val media = LocalGameMedia.current
    val faces = rememberFaces(media, game.label, game.shownAs)
    val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
    val art = faces?.spine?.takeIf { !it.isRecycled }
    val shape = RoundedCornerShape(2.dp)
    // The case's own spine: its depth by its height, at the screen's shared scale.
    val shape3d = game.shownAs.shape
    val density = LocalDensity.current
    Box(Modifier.size((shape3d.depth * scale).coerceAtLeast(6f).dp, (shape3d.height * scale).dp)
        .then(if (rects != null) Modifier.onGloballyPositioned { rects.put(game.id, it.boundsInRoot(), density.density, opacity) } else Modifier)
        .alpha(opacity).clip(shape).border(1.dp, Color(0x0FFFFFFF), shape)) {
        if (art != null) Image(art.asImageBitmap(), look.title, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else {
            val tone = faces?.edge?.let { darker(it, .78f) } ?: look.tone2
            val typeface = rememberBold()
            Canvas(Modifier.fillMaxSize().background(Color(tone)).background(Brush.horizontalGradient(0f to Color(0x1FFFFFFF), .3f to Color.Transparent, 1f to Color(0x40000000)))) {
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface; textSize = 8.dp.toPx(); color = 0xEBFFFFFF.toInt(); letterSpacing = .02f
                }
                val room = size.height - 12.dp.toPx()
                val title = TextUtils.ellipsize(look.short, paint, room, TextUtils.TruncateAt.END).toString()
                drawContext.canvas.nativeCanvas.apply {
                    save(); translate(size.width / 2f, 6.dp.toPx()); rotate(90f)
                    drawText(title, 0f, -(paint.ascent() + paint.descent()) / 2f, paint); restore()
                }
            }
        }
    }
}

@Composable private fun rememberBold(): Typeface {
    val context = LocalContext.current
    return remember {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 700, false) else Typeface.create(base, Typeface.BOLD)
    }
}

private fun darker(color: Int, by: Float) = (0xFF shl 24) or (((color shr 16 and 0xFF) * by).toInt() shl 16) or
    (((color shr 8 and 0xFF) * by).toInt() shl 8) or ((color and 0xFF) * by).toInt()

/** The year each console first came out, for the card's eyebrow. */
private val GameSystem.released: Int get() = when (this) {
    GameSystem.NES -> 1983; GameSystem.SMS -> 1985; GameSystem.GENESIS -> 1988; GameSystem.GB -> 1989
    GameSystem.SNES -> 1990; GameSystem.GG -> 1990; GameSystem.PS1 -> 1994; GameSystem.SATURN -> 1994
    GameSystem.GBC -> 1998; GameSystem.DREAMCAST -> 1998; GameSystem.PS2 -> 2000; GameSystem.GBA -> 2001; GameSystem.GAMECUBE -> 2001
}

/** The console's name as a card has room for it. */
private val GameSystem.cardName: String get() = if (this == GameSystem.NES) "NES" else title

private const val CARD_W = 224f
private const val CARD_H = 222f
private const val GAP = 20f
private const val SPINES = 9
/** The spine row's height: the tallest case in the library stands this tall. */
private const val SPINE_ROW = 82f
private const val RECENT = 3
