package dev.cartridge.ui.screens

import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.library.LabelRef
import dev.cartridge.library.LibraryGame
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.Page
import dev.cartridge.storage.SlotKind
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** One item of the "Videos & screenshots" strip: the gameplay video first, then each screenshot. */
private sealed class StripItem {
    class Video(val file: File) : StripItem()
    class Shot(val ref: LabelRef, val index: Int) : StripItem()
}

/**
 * Game detail (spec 4.3, GameDetail.dc.html): the game's trailer full-bleed behind a black gradient, its 3D box at the
 * left, its facts and actions at the right, its videos and screenshots along the bottom. [active] is false while a
 * sheet opened from it (More, Game settings, Manage) covers it.
 */
@Composable fun GameDetailScreen(state: CollectionState, shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, active: Boolean,
    modifier: Modifier = Modifier) {
    val game = state.selectedGame ?: return Box(modifier.fillMaxSize().background(Palette.Canvas))
    val media = LocalGameMedia.current
    val faces = rememberFaces(media, game.label, game.shownAs)
    val strip = remember(game.id, game.media) {
        buildList {
            media?.video(game)?.let { add(StripItem.Video(it)) }
            media?.shots(game).orEmpty().forEachIndexed { index, ref -> add(StripItem.Shot(ref, index)) }
        }
    }
    SideEffect { shell.detailMediaCount = strip.size }
    val selected = strip.getOrNull(shell.detailShot)
    val backdrop = remember { BackdropState() }
    val page = shell.page()?.takeIf { it.key == "details" }

    Box(modifier.fillMaxSize().background(Palette.Canvas).testTag("game-detail")) {
        // The hero: the selected strip item, the trailer by default, at 80 %.
        MediaBackdrop(game, faces, Modifier.fillMaxSize().alpha(.8f), playVideo = active && selected is StripItem.Video,
            sound = shell.trailerSound, dwellMs = 500, shot = (selected as? StripItem.Shot)?.index ?: 0, state = backdrop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xEB000000), .38f to Color(0xC7000000), .72f to Color(0x40000000), 1f to Color(0x59000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C000000), .18f to Color.Transparent, .62f to Color.Transparent, .92f to Palette.Canvas)))

        Header(shell, device, backdrop, swapped)

        // The box, turned toward the words beside it.
        val (baseW, baseH) = boxSize(game.shownAs)
        val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
        GameBox(look, (baseW * 1.24f).dp, (baseH * 1.24f).dp, 28.dp, 24f, Modifier.absoluteOffset(96.dp, 124.dp)
            .semantics { contentDescription = "${look.title}, ${game.system.title}" }, focused = true)

        Column(Modifier.absoluteOffset(400.dp, 112.dp).width(640.dp)) {
            Facts(game, look)
            if (page != null) Actions(page, shell, swapped, Modifier.padding(top = 22.dp))
            Stats(game, shell, Modifier.padding(top = 22.dp))
        }

        if (strip.isNotEmpty()) Strip(strip, game, faces, shell, Modifier.absoluteOffset(96.dp, 506.dp))

        val primary = page?.rows?.firstOrNull()
        HintBar(buildList {
            if (primary != null) add(Hint(Pad.confirm(swapped), primary.title, primary = true, tag = "detail-primary") { shell.activate(page, primary) })
            if (strip.any { it is StripItem.Video }) add(Hint(Pad.R3, if (shell.trailerSound) "Mute trailer" else "Trailer sound", tag = "trailer-sound") { shell.stick(1) })
            add(Hint(Pad.Y, if (game.favorite) "Unfavorite" else "Favorite", tag = "detail-favorite") { shell.secondary() })
            add(Hint(Pad.X, "Game settings", tag = "detail-settings") { shell.alternate() })
            add(Hint(Pad.back(swapped), "Back", tag = "back") { shell.back() })
        }, Modifier.align(Alignment.BottomStart))
    }
}

@Composable private fun Header(shell: ConsoleShell, device: DeviceStatus, backdrop: BackdropState, swapped: Boolean) {
    Box(Modifier.fillMaxWidth().height(Layout.TopBar).padding(horizontal = Layout.Margin)) {
        Row(Modifier.align(Alignment.CenterStart).tap("detail-back") { shell.back() }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(Pad.back(swapped))
            Text("Library", Typo.Callout.copy(fontWeight = FontWeight.SemiBold), lines = 1)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimatedVisibility(backdrop.video, enter = fadeIn(LocalDesignMotion.current.fade()), exit = fadeOut(LocalDesignMotion.current.fade())) {
                TrailerPill(backdrop, shell.trailerSound)
            }
            if (shell.showStatus) Text(device.time, Typo.Callout.copy(fontWeight = FontWeight.SemiBold).tabular(), lines = 1)
        }
    }
}

@Composable private fun TrailerPill(backdrop: BackdropState, sound: Boolean) {
    val ink = Color(0xFFE5E5EA)
    Row(Modifier.height(30.dp).mediaPill().padding(horizontal = 12.dp).testTag("trailer-pill"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(12.dp)) {
            val k = size.width / 24f
            drawPath(Path().apply { moveTo(7f * k, 4.5f * k); lineTo(7f * k, 19.5f * k); lineTo(19f * k, 12f * k); close() }, ink)
        }
        Text("Trailer", Typo.Small.copy(fontWeight = FontWeight.SemiBold), color = ink, lines = 1)
        Box(Modifier.size(56.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((backdrop.progress ?: 0f).coerceIn(0f, 1f)).background(ink))
        }
        Text("${clock(backdrop.positionMs)} / ${clock(backdrop.durationMs)}", Typo.Small.copy(fontWeight = FontWeight.SemiBold).tabular(), color = ink, lines = 1)
        SpeakerIcon(sound, ink, 13.dp)
    }
}

@Composable private fun SpeakerIcon(sound: Boolean, ink: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val line = Stroke(2.2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(4f * k, 9f * k); lineTo(8f * k, 9f * k); lineTo(13f * k, 5f * k); lineTo(13f * k, 19f * k); lineTo(8f * k, 15f * k); lineTo(4f * k, 15f * k); close() }, ink, style = line)
        if (sound) drawArc(ink, -50f, 100f, false, Offset(11f * k, 7f * k), androidx.compose.ui.geometry.Size(8f * k, 10f * k), style = line)
        else {
            drawLine(ink, Offset(17f * k, 9f * k), Offset(21f * k, 15f * k), 2.2f * k, StrokeCap.Round)
            drawLine(ink, Offset(21f * k, 9f * k), Offset(17f * k, 15f * k), 2.2f * k, StrokeCap.Round)
        }
    }
}

/** Chips, the series eyebrow, the title, "Genre · players · developer / publisher" and two lines of description. */
@Composable private fun Facts(game: LibraryGame, look: BoxLook) {
    fun meta(key: String) = game.metadata[key]?.value?.trim()?.takeIf(String::isNotEmpty)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(game.system.shortName, Color(0x24FFFFFF), Palette.Label, Typo.Small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp))
        look.year?.let { Chip(it, Color(0x14FFFFFF), Palette.Soft, Typo.Small.copy(fontWeight = FontWeight.SemiBold)) }
        if (game.favorite) Row(Modifier.clip(RoundedCornerShape(Radii.R8)).background(Color(0x24FFD60A)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Star(true, 11.dp)
            Text("Favorite", Typo.Small.copy(fontWeight = FontWeight.SemiBold), color = Palette.Favorite, lines = 1)
        }
    }
    look.series?.let { Text(it, Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.sp), Modifier.padding(top = 16.dp), Palette.Quiet, lines = 1) }
    // The display size suits a short title; a long one steps down so it never pushes the actions off the screen.
    val size = when { look.short.length <= 18 -> 58; look.short.length <= 28 -> 46; else -> 38 }
    Text(look.short, Typo.Display.copy(fontSize = size.sp, lineHeight = (size * 1.04f).sp), Modifier.padding(top = if (look.series != null) 2.dp else 16.dp).testTag("detail-title"), lines = 2)
    val players = meta("players")?.let { if (it == "1") "1 player" else "$it players" }
    val makers = listOfNotNull(meta("developer"), meta("publisher")).distinct().joinToString(" / ").ifEmpty { null }
    val line = listOfNotNull(meta("genre"), players, makers).joinToString(" · ")
    if (line.isNotEmpty()) Text(line, Typo.Body, Modifier.padding(top = 10.dp), Palette.Quiet, lines = 1)
    meta("description")?.let { Text(it, Typo.Body.copy(fontWeight = FontWeight.Normal, lineHeight = 22.5.sp), Modifier.padding(top = 12.dp).widthIn(max = 540.dp), Palette.Secondary, lines = 2) }
}

@Composable private fun Chip(text: String, fill: Color, ink: Color, style: androidx.compose.ui.text.TextStyle) {
    Box(Modifier.clip(RoundedCornerShape(Radii.R8)).background(fill).padding(horizontal = 10.dp, vertical = 5.dp)) { Text(text, style, color = ink, lines = 1) }
}

/** Resume (or Play) as the white pill, Start over, the favourite star and More. The focused one lifts. */
@Composable private fun Actions(page: Page, shell: ConsoleShell, swapped: Boolean, modifier: Modifier) {
    val focused = if (shell.detailRow == 0) shell.focused(page) else null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        page.rows.forEach { row ->
            val on = row === focused || row.id == focused?.id
            fun tapRow(m: Modifier) = m.tap("detail-${row.id}", row.enabled) { shell.focusDetailAction(page, row); shell.activate(page, row) }
            when (row.id) {
                "primary" -> Row(tapRow(Modifier.height(56.dp).focusLift(on, RoundedCornerShape(Radii.Pill), ring = 3.dp).clip(RoundedCornerShape(Radii.Pill))
                    .background(if (row.enabled) Palette.Label else Palette.Label.copy(alpha = .6f))).padding(start = 14.dp, end = 26.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (row.warn) Palette.Destructive else Palette.Canvas), contentAlignment = Alignment.Center) {
                        Text(Pad.confirm(swapped).label, Typo.Caption.copy(fontWeight = FontWeight.ExtraBold), color = Palette.Label, lines = 1)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(row.title, Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = Palette.Canvas, lines = 1)
                        row.note?.let { Text(it, Typo.Small, color = Palette.OnLabelSecondary, lines = 1) }
                    }
                }
                "start-over" -> {
                    val fill by animateColorAsState(if (on) Palette.Label else Color(0x24FFFFFF), LocalDesignMotion.current.lift(), label = "start-over")
                    Box(tapRow(Modifier.height(56.dp).focusLift(on, RoundedCornerShape(Radii.Pill)).clip(RoundedCornerShape(Radii.Pill)).background(fill))
                        .padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        Text(row.title, Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = if (on) Palette.Canvas else Palette.Label, lines = 1)
                    }
                }
                else -> {
                    val fill by animateColorAsState(if (on) Palette.Label else Color(0x24FFFFFF), LocalDesignMotion.current.lift(), label = row.id)
                    Box(tapRow(Modifier.size(56.dp).focusLift(on, CircleShape).clip(CircleShape).background(fill))
                        .semantics { contentDescription = row.title }, contentAlignment = Alignment.Center) {
                        if (row.id == "favorite") Star(row.title.startsWith("Remove"), 20.dp, outline = if (on) Palette.Canvas else Palette.Label)
                        else Dots(if (on) Palette.Canvas else Palette.Label)
                    }
                }
            }
        }
    }
}

@Composable private fun Star(filled: Boolean, size: Dp, outline: Color = Palette.Label) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val star = Path().apply {
            moveTo(12f * k, 2.5f * k); lineTo(14.9f * k, 8.6f * k); lineTo(21.5f * k, 9.4f * k); lineTo(16.6f * k, 14f * k); lineTo(17.9f * k, 20.6f * k)
            lineTo(12f * k, 17.3f * k); lineTo(6.1f * k, 20.6f * k); lineTo(7.4f * k, 14f * k); lineTo(2.5f * k, 9.4f * k); lineTo(9.1f * k, 8.6f * k); close()
        }
        if (filled) drawPath(star, Palette.Favorite) else drawPath(star, outline, style = Stroke(1.8f * k, join = StrokeJoin.Round))
    }
}

@Composable private fun Dots(ink: Color) {
    Canvas(Modifier.size(20.dp)) {
        val k = size.width / 24f
        listOf(5f, 12f, 19f).forEach { x -> drawCircle(ink, 2f * k, Offset(x * k, 12f * k)) }
    }
}

/** Played, Last played, Save states and Core. */
@Composable private fun Stats(game: LibraryGame, shell: ConsoleShell, modifier: Modifier) {
    val states = shell.stateList?.takeIf { it.first == game.id }?.second?.count { it.kind != SlotKind.Previous }
    val cells = listOf(
        "Played" to if (game.playMs >= 60_000) hoursMinutes(game.playMs) else "Not yet",
        "Last played" to day(game.lastPlayed),
        "Save states" to (states?.toString() ?: "…"),
        "Core" to game.system.core.title)
    Row(modifier.widthIn(max = 600.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.forEach { (label, value) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, Typo.Small, color = Palette.Tertiary, lines = 1)
                Text(value, Typo.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold).tabular(), lines = 1)
            }
        }
    }
}

/** "Videos & screenshots": the trailer first with its play badge and length, then each screenshot. The focused one rings. */
@Composable private fun Strip(items: List<StripItem>, game: LibraryGame, faces: dev.cartridge.storage.CoverFaces?, shell: ConsoleShell, modifier: Modifier) {
    val media = LocalGameMedia.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (items.any { it is StripItem.Video }) "Videos & screenshots" else "Screenshots", Typo.Body.copy(fontWeight = FontWeight.Bold), lines = 1)
            if (items.first() is StripItem.Video) Text("Plays muted in the background while you browse", Typo.Small, color = Palette.Tertiary, lines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEachIndexed { index, item ->
                val on = shell.detailRow == 1 && shell.detailShot == index
                val shape = RoundedCornerShape(Radii.R12)
                val ref = when (item) { is StripItem.Shot -> item.ref; is StripItem.Video -> media?.shots(game)?.firstOrNull() }
                val image = rememberImage(media, ref, 400)
                val width = if (item is StripItem.Video || image == null) 150.dp else (100f * image.width / image.height).coerceIn(75f, 178f).dp
                Box(Modifier.size(width, 100.dp).focusLift(on, shape, scale = 1.05f, ring = 3.dp, elevation = 14.dp).clip(shape).background(Palette.Surface2)
                    .border(1.dp, Color(0x1AFFFFFF), shape).tap("strip-$index") { shell.focusShot(index) }) {
                    when {
                        image != null && !image.isRecycled -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        faces != null && !faces.front.isRecycled -> Image(faces.front.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    if (item is StripItem.Video) {
                        Box(Modifier.fillMaxSize().background(Color(0x40000000)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xEBF5F5F7)), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(14.dp)) {
                                    val k = size.width / 24f
                                    drawPath(Path().apply { moveTo(8f * k, 5f * k); lineTo(8f * k, 19f * k); lineTo(19f * k, 12f * k); close() }, Color.Black)
                                }
                            }
                        }
                        val length by produceState<Long?>(null, item.file.path) { value = withContext(Dispatchers.IO) { videoLength(item.file) } }
                        length?.let {
                            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xA6000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(clock(it), Typo.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold).tabular(), color = Color.White, lines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun videoLength(file: File): Long? = runCatching {
    MediaMetadataRetriever().run {
        try { setDataSource(file.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
    }
}.getOrNull()

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun hoursMinutes(ms: Long): String { val m = ms / 60_000; return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m" }

/** Today, Yesterday, "4 days ago", then the date; "Never" for a game not played yet. */
private fun day(time: Long): String {
    if (time <= 0) return "Never"
    fun start(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val days = ((start(System.currentTimeMillis()) - start(time)) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(time))
    }
}
