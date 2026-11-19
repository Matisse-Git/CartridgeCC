package dev.cartridge.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.MediaMotion
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.LibrarySort
import dev.cartridge.library.Shelf
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.HintMode
import dev.cartridge.shell.Tab
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** The shelf's geometry at the 1280×720 canvas (§4.1, Main.dc.html). */
private object ShelfGeometry {
    const val AREA_TOP = 150f
    const val AREA_HEIGHT = 330f
    const val BOTTOM_GAP = 22f
    const val FOCUS_SCALE = 1.24f
    const val FOCUS_ANGLE = 16f
    const val SIDE_ANGLE = 50f
    const val FOCUS_LIFT = 30f
    const val FIRST_STEP = 260f
    const val NEXT_STEP = 150f
    /** Boxes further than this from the focus are not composed at all. */
    const val REACH = 4.5f
}

/**
 * The Library (§4.1): the focused game's own media behind everything, the top bar, the filter chips, the 3D shelf
 * of real boxes, the focused game's facts and the hint bar. [browsing] is false while anything covers the shelf.
 */
@Composable fun LibraryScreen(state: CollectionState, shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, browsing: Boolean,
    modifier: Modifier = Modifier, detail: Float = 0f, flying: Set<String> = emptySet()) {
    val motion = LocalDesignMotion.current
    val media = LocalGameMedia.current
    val game = state.selectedGame
    val empty = state.loaded && state.allGames.isEmpty()
    val ready = state.loaded && !state.busy
    val draft = state.labelDraft?.takeIf { it.gameId == game?.id }
    val focusLabel = draft?.label ?: game?.label
    val focusFaces = game?.let { rememberFaces(media, focusLabel, it.shownAs) }

    // The label preview waits for the drawn label; here it is drawn as soon as its faces are decoded.
    LaunchedEffect(draft, focusFaces, state.viewportGeneration) {
        val selected = state.selectedGame ?: return@LaunchedEffect
        if (draft != null && (focusFaces != null || media?.boxArt(draft.label) == null))
            shell.coordinator.labelsReady(state.viewportGeneration, listOf(draft.label.textureKey(selected.shownAs)), state.selection.index)
    }

    // Lift toward the camera on the way into a game and settle back on the way out (the full launch is its own screen).
    val launch = remember { Animatable(if (state.screen == CollectionScreen.Ejecting) 1f else 0f) }
    LaunchedEffect(state.screen, state.reducedMotion, state.curtain) {
        if (state.curtain && state.screen != CollectionScreen.Inserting) return@LaunchedEffect
        val target = if (state.screen == CollectionScreen.Inserting && !state.reducedMotion) 1f else 0f
        if (state.reducedMotion) launch.snapTo(target)
        else launch.animateTo(target, tween(if (target == 0f) MediaMotion.EJECT_MS else MediaMotion.INSERT_MS, easing = if (target == 0f) LinearEasing else DesignMotion.Ease))
    }
    val chromeAlpha by animateFloatAsState(if (browsing && state.screen == CollectionScreen.Library) 1f else 0f, motion.fade(250), label = "chrome")
    val backdrop = remember { BackdropState() }
    val frameRate = if (shell.showFrameRate) rememberFrameRate() else null

    BoxWithConstraints(modifier.fillMaxSize().background(Palette.Canvas)) {
        val width = maxWidth
        val center = width.value / 2

        // The focused game's media: a still, then its muted gameplay video once the focus rests.
        if (shell.previewEnabled && !empty && game != null) {
            Box(Modifier.fillMaxWidth().height(540.dp).alpha(shell.previewStrength / 100f * (1f - launch.value))) {
                MediaBackdrop(game, focusFaces, Modifier.fillMaxSize(), playVideo = browsing && state.screen == CollectionScreen.Library,
                    sound = shell.previewSound, state = backdrop)
            }
            Box(Modifier.fillMaxWidth().height(541.dp).background(Brush.verticalGradient(
                0f to Color(0xC7000000), .24f to Color(0x33000000), .5f to Color(0x26000000), .82f to Color(0xD9000000), 1f to Palette.Canvas)))
        }

        Box(Modifier.fillMaxSize().alpha(chromeAlpha)) {
            TopBar(Tab.Library, shell::openTab, device, showStatus = shell.showStatus, showPercent = shell.showPercent, frameRate = frameRate)
            if (!empty) Chips(state, shell, Modifier.padding(top = 92.dp).padding(horizontal = Layout.Margin), ready)
            // The pill that names what plays behind the shelf.
            AnimatedVisibility(backdrop.video && shell.previewEnabled, Modifier.align(Alignment.TopEnd).padding(top = 142.dp, end = Layout.Margin),
                enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
                PreviewPill(backdrop.progress ?: 0f, shell.previewSound)
            }
            AnimatedVisibility(state.scanning && shell.import == null, Modifier.align(Alignment.TopCenter).padding(top = 142.dp),
                enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
                Row(Modifier.mediaPill().tap("cancel-scan") { shell.coordinator.cancelScan() }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Looking for new games", Typo.Small.copy(fontWeight = FontWeight.SemiBold), color = Palette.Soft, lines = 1)
                    Glyph(Pad.back(swapped), size = 20.dp)
                }
            }
        }

        when {
            empty -> Welcome(shell, swapped, Modifier.alpha(chromeAlpha))
            game == null && state.loaded -> NoResults(state, shell, swapped, Modifier.alpha(chromeAlpha))
            game != null -> {
                ShelfRow(state, shell, center, launch.value, browsing && ready, focusFaces, detail, flying)
                FocusInfo(game, shell, Modifier.padding(top = 500.dp).fillMaxWidth().alpha(chromeAlpha * (1f - launch.value)))
            }
        }

        if (!empty) {
            val context = when {
                state.searching -> "“${state.query.search}” · " + if (game == null) "no results" else "${state.slots.size} ${if (state.slots.size == 1) "result" else "results"}"
                game == null -> ""
                else -> "${state.selection.index + 1} of ${state.slots.size} · ${state.shelf.title}"
            }
            val hints = if (shell.hints == HintMode.Hidden || game == null) emptyList() else buildList {
                add(Hint(Pad.confirm(swapped), "Play", primary = true, tag = "insert") { shell.select() })
                add(Hint(Pad.X, "Details", tag = "details") { shell.openDetails() })
                add(Hint(Pad.Y, if (game.favorite) "Unfavorite" else "Favorite", tag = "favorite") { shell.secondary() })
                if (shell.previewEnabled && media?.video(game) != null) add(Hint(Pad.R3, if (shell.previewSound) "Mute" else "Sound", tag = "preview-sound") { shell.stick(1) })
                add(Hint(Pad.Menu, "Quick menu", tag = "system-menu") { shell.openMenu() })
            }
            ScreenHints(hints, browsing, Modifier.align(Alignment.BottomStart).alpha(chromeAlpha).testTag("library-hints"), context = context)
        }
    }
}

/** Recently Played, Favorites, All Games, then each console, between their L1 and R1 glyphs; the sort control at the right. */
@Composable private fun Chips(state: CollectionState, shell: ConsoleShell, modifier: Modifier, ready: Boolean) {
    val chips = shell.chips
    val counts = remember(state.allGames) {
        val all = state.allGames
        mapOf<Shelf, Int?>(Shelf.Recent to null, Shelf.Favorites to all.count { it.favorite }, Shelf.All to all.size) +
            GameSystem.entries.associate { system -> Shelf.Console(system) to all.count { it.system == system } }
    }
    val list = rememberLazyListState()
    val selected = if (state.searching) -1 else chips.indexOf(state.shelf)
    LaunchedEffect(selected, chips.size) {
        if (selected < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == selected }
        val end = list.layoutInfo.viewportEndOffset
        if (item == null || item.offset < 0 || item.offset + item.size > end) list.animateScrollToItem((selected - 1).coerceAtLeast(0))
    }
    Box(modifier.fillMaxWidth().height(34.dp)) {
    Row(Modifier.align(Alignment.CenterStart).padding(end = 170.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(Pad.L1, modifier = Modifier.padding(end = 10.dp))
        // The selected chip's pill glides along the row to the next chip; the chips themselves stay where they are.
        Box(Modifier.weight(1f, fill = false)) {
        val motion = LocalDesignMotion.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selected }
        val target = info?.let { with(density) { it.offset.toDp().value to it.size.toDp().value } }
        var placed by remember { mutableStateOf(false) }
        val pillX by animateFloatAsState(target?.first ?: 0f, if (placed) motion.move(DesignMotion.TAB_MS) else androidx.compose.animation.core.snap(), label = "chip-x")
        val pillW by animateFloatAsState(target?.second ?: 0f, if (placed) motion.move(DesignMotion.TAB_MS) else androidx.compose.animation.core.snap(), label = "chip-w")
        if (target != null) SideEffect { placed = true }
        if (target != null) Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = pillX.dp).width(pillW.dp).fillMaxHeight().clip(RoundedCornerShape(17.dp)).background(Palette.ChipSelected))
        }
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(chips, key = { _, shelf -> shelf.key }) { index, shelf ->
                val on = index == selected
                val ink by androidx.compose.animation.animateColorAsState(if (on) Color.White else Palette.Soft, motion.fade(), label = "chip-ink")
                Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) Color.Transparent else Palette.ChipRest)
                    .tap("chip-${shelf.key}", ready) { shell.chooseChip(shelf) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val name = if (shelf is Shelf.Console) shelf.system.shortName else shelf.title
                    Text(name, Typo.Caption.copy(fontWeight = FontWeight.SemiBold), color = ink, lines = 1)
                    counts[shelf]?.let { Text("%,d".format(it), Typo.Caption.copy(fontWeight = FontWeight.SemiBold).tabular(), color = if (on) Palette.Soft else Palette.Secondary, lines = 1) }
                }
            }
        }
        }
        Glyph(Pad.R1, modifier = Modifier.padding(start = 10.dp))
    }
        Row(Modifier.align(Alignment.CenterEnd).height(34.dp).clip(RoundedCornerShape(17.dp)).tap("sort", ready) { shell.stepSort() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(14.dp)) {
                val k = size.width / 24f
                listOf(Triple(4f, 20f, 7f), Triple(7f, 17f, 12f), Triple(10f, 14f, 17f)).forEach { (x0, x1, y) ->
                    drawLine(Palette.Quiet, Offset(x0 * k, y * k), Offset(x1 * k, y * k), 2.2f * k, StrokeCap.Round)
                }
            }
            Text(sortName(state.query.sort), Typo.Caption, color = Palette.Quiet, lines = 1)
        }
    }
}

private fun sortName(sort: LibrarySort) = when (sort) {
    LibrarySort.Added -> "Date added"
    LibrarySort.Title -> "Title"
    LibrarySort.Recent -> "Last played"
    LibrarySort.Playtime -> "Most played"
}

/** One box's place on the shelf at a fractional distance from the focus: the mockup's steps, eased between. */
private class Pose(val scale: Float, val centerX: Float, val angle: Float, val lift: Float, val dim: Float, val alpha: Float)

private fun pose(offset: Float): Pose {
    val a = abs(offset); val s = sign(offset)
    val near = a.coerceAtMost(1f)
    fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
    val angle = if (offset <= 0f) lerp(ShelfGeometry.FOCUS_ANGLE, ShelfGeometry.SIDE_ANGLE, near) else lerp(ShelfGeometry.FOCUS_ANGLE, -ShelfGeometry.SIDE_ANGLE, near)
    val step = if (a < 1f) ShelfGeometry.FIRST_STEP * a else ShelfGeometry.FIRST_STEP + (a - 1f) * ShelfGeometry.NEXT_STEP
    val dim = when { a <= 1f -> lerp(0f, .3f, a); a <= 2f -> lerp(.3f, .5f, a - 1f); else -> .5f }
    val alpha = when { a <= 2f -> 1f; a <= 3f -> lerp(1f, .55f, a - 2f); a <= 4f -> lerp(.55f, 0f, a - 3f); else -> 0f }
    return Pose(lerp(ShelfGeometry.FOCUS_SCALE, 1f, near), s * step, angle, lerp(ShelfGeometry.FOCUS_LIFT, 0f, near), dim, alpha)
}

/**
 * The 3D shelf. The focus moves continuously (450 ms ease) so every box slides, turns and dims through the steps
 * between; a new chip's games arrive in place. Tapping a side box moves to it; tapping the focused box plays it.
 */
@Composable private fun ShelfRow(state: CollectionState, shell: ConsoleShell, center: Float, launch: Float, enabled: Boolean, focusFaces: dev.cartridge.storage.CoverFaces?,
    detail: Float, flying: Set<String>) {
    val motion = LocalDesignMotion.current
    val media = LocalGameMedia.current
    val index = state.selection.index
    val position = remember { Animatable(index.toFloat()) }
    val key = state.shelf.key to state.query
    var shelfKey by remember { mutableStateOf(key) }
    // The boxes on screen a moment ago, so a row that is replaced can leave while the new one arrives.
    val frame = remember { mutableListOf<Pair<LibraryGame, Float>>() }
    var leaving by remember { mutableStateOf<Leaving?>(null) }
    if (key != shelfKey) {
        // Another chip arrives from the side it lies on; a sort or search has no side, so it rises in place.
        val side = if (key.first != shelfKey.first) shell.shelfDirection else 0
        // Spines flying in from Systems are the new row's arrival; the old row simply goes.
        leaving = Leaving(if (flying.isEmpty()) frame.toList() else emptyList(), side, (leaving?.id ?: 0) + 1)
        shelfKey = key
    }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(leaving?.id) {
        val row = leaving ?: return@LaunchedEffect
        position.snapTo(index.toFloat())
        swap.snapTo(0f)
        swap.animateTo(1f, motion.progress(if (row.side == 0) DesignMotion.RISE_MS else DesignMotion.ROW_MS))
        leaving = null
    }
    // Opening the console: the boxes rise into place from the middle outward, once per start of the app.
    val entrance = remember { Animatable(if (ShelfEntrance.done || state.slots.isEmpty()) 1f else 0f) }
    LaunchedEffect(state.slots.isNotEmpty()) {
        if (ShelfEntrance.done || state.slots.isEmpty()) return@LaunchedEffect
        ShelfEntrance.done = true
        entrance.animateTo(1f, motion.progress(DesignMotion.START_MS))
    }
    LaunchedEffect(index, state.shelf.key, state.query) {
        // A new row starts at its own focus rather than sliding across from the old row's.
        if (leaving != null) return@LaunchedEffect
        if (abs(position.value - index) > 6f) position.snapTo(index + sign(position.value - index) * 6f)
        position.animateTo(index.toFloat(), motion.shelf())
    }
    val pos = if (leaving != null) index.toFloat() else position.value
    val bottom = ShelfGeometry.AREA_TOP + ShelfGeometry.AREA_HEIGHT - ShelfGeometry.BOTTOM_GAP
    val first = (pos - ShelfGeometry.REACH).toInt().coerceAtLeast(0)
    val last = (pos + ShelfGeometry.REACH).roundToInt().coerceAtMost(state.slots.lastIndex)
    val rowTravel = motion.travel(ROW_TRAVEL)
    val riseTravel = motion.travel(RISE_TRAVEL)
    // The focused box is handed to the stage while it flies to Game detail or into a game; the others make way.
    val handedOff = detail > 0f || state.launch != null
    val apart = motion.travel(APART_TRAVEL) * detail
    val shown = mutableListOf<Pair<LibraryGame, Float>>()
    Box(Modifier.fillMaxSize()) {
        leaving?.let { row ->
            val t = swap.value
            for ((game, offset) in row.boxes) key("leaving-${row.id}-${game.id}") {
                val p = pose(offset)
                val faces = rememberFaces(media, game.label, game.shownAs)
                val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
                val (baseW, baseH) = boxSize(game.shownAs)
                val w = baseW * p.scale; val h = baseH * p.scale
                val dx = -row.side * rowTravel * t
                val dy = if (row.side == 0) riseTravel * .6f * t else 0f
                val alpha = p.alpha * (1f - t)
                if (alpha > .01f) GameBox(look, w.dp, h.dp, p.angle, Modifier.zIndex(10f - abs(offset)).absoluteOffset((center + p.centerX - w / 2 + dx).dp, (bottom - h + dy).dp),
                    translateZ = p.lift.dp, dim = p.dim, focused = false, alpha = alpha)
            }
        }
        for (i in first..last) {
            val game = state.slots[i] ?: continue
            val offset = i - pos
            if (abs(offset) >= ShelfGeometry.REACH) continue
            shown += game to offset
            key(game.id) {
                val focusedHere = i == index
                val p = pose(offset)
                val label = if (focusedHere && state.labelDraft?.gameId == game.id) state.labelDraft.label else game.label
                val faces = if (focusedHere && focusFaces != null) focusFaces else rememberFaces(media, label, game.shownAs)
                val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
                val (baseW, baseH) = boxSize(game.shownAs)
                // On the way back from a game the focused box settles from near the camera; the others step back into the dark.
                val toward = if (focusedHere) launch else 0f
                val w = baseW * p.scale; val h = baseH * p.scale
                // A row arriving: each box a beat after the one nearer the focus, from the side (or from below) it comes.
                val step = abs(offset).roundToInt().coerceAtMost(4)
                val arrive = if (leaving != null && flying.isEmpty()) stagger(swap.value, step, .07f) else 1f
                val rise = stagger(entrance.value, step, .09f)
                val side = leaving?.side ?: 0
                val dx = side * rowTravel * (1f - arrive) + sign(offset) * apart
                val dy = (if (leaving != null && side == 0) riseTravel * (1f - arrive) else 0f) + riseTravel * 1.5f * (1f - rise)
                val hidden = (focusedHere && handedOff) || game.id in flying
                val alpha = if (hidden) 0f else p.alpha * arrive * rise * (if (focusedHere) 1f else (1f - launch) * (1f - detail))
                if (alpha > .01f) GameBox(look, w.dp, h.dp, p.angle * (1f - toward),
                    Modifier.zIndex(20f - abs(offset)).absoluteOffset((center + p.centerX - w / 2 + dx).dp, (bottom - h - toward * 40f + dy).dp)
                        .semantics { contentDescription = "${look.title}, ${game.system.title}" }
                        .tap("box-$i", enabled) { if (focusedHere) shell.select() else shell.coordinator.navigate(i - index) },
                    translateZ = (p.lift + toward * 520f).dp, dim = p.dim, focused = abs(offset) < .5f, alpha = alpha)
            }
        }
    }
    SideEffect { frame.clear(); frame.addAll(shown) }
}

/** A row on its way out: the boxes it showed, at their distances from the focus, and the side it leaves toward (0: it sinks). */
private class Leaving(val boxes: List<Pair<LibraryGame, Float>>, val side: Int, val id: Int)

/** The console's opening rise plays once per start of the app, not on every return to the shelf. */
private object ShelfEntrance { var done = false }

private const val ROW_TRAVEL = 340f
private const val RISE_TRAVEL = 28f
private const val APART_TRAVEL = 120f

/** Where the focused box stands on the shelf at rest: where a box flying to or from the shelf lands. */
fun shelfFocusPose(media: dev.cartridge.domain.Media): BoxPose = shelfPose(media, 0f)

/** Where a box [offset] places from the focus stands on the shelf at rest, and how dim it is there. */
fun shelfDim(offset: Float) = pose(offset).dim

fun shelfPose(media: dev.cartridge.domain.Media, offset: Float): BoxPose {
    val (baseW, baseH) = boxSize(media)
    val p = pose(offset)
    val w = baseW * p.scale; val h = baseH * p.scale
    val bottom = ShelfGeometry.AREA_TOP + ShelfGeometry.AREA_HEIGHT - ShelfGeometry.BOTTOM_GAP
    return BoxPose(Layout.WIDTH / 2 + p.centerX - w / 2, bottom - h, w, h, p.angle, p.lift)
}

/** The focused game's name, facts and history, centred under the shelf. */
@Composable private fun FocusInfo(game: LibraryGame, shell: ConsoleShell, modifier: Modifier) {
    val saves by produceState<Int?>(null, game.id, game.lastPlayed) { value = shell.saveCount(game) }
    Column(modifier.padding(horizontal = Layout.Margin), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(displayTitle(game.title), Typo.TitleLarge, Modifier.widthIn(max = 1000.dp).testTag("selected-title"), lines = 1)
            if (game.favorite) FavoriteStar(Modifier.testTag("favorite-mark"))
        }
        val facts = listOfNotNull(game.system.shortName, game.metadata["year"]?.value?.takeIf(String::isNotBlank), game.metadata["genre"]?.value?.takeIf(String::isNotBlank))
        Text(facts.joinToString(" · "), Typo.Body, color = Palette.Secondary, lines = 1)
        val history = buildList {
            add(if (game.lastPlayed > 0) "Last played " + relative(game.lastPlayed) else "Never played")
            if (game.playMs >= 60_000) add("${playtime(game.playMs)} played")
            saves?.let { add(if (it == 1) "1 save state" else "$it save states") }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            history.forEachIndexed { i, part ->
                if (i > 0) Text("·", Typo.Caption, color = Palette.Tertiary)
                Text(part, Typo.Caption.tabular(), color = Palette.Tertiary, lines = 1)
            }
        }
    }
}

private fun relative(time: Long): String {
    val now = System.currentTimeMillis()
    if (now - time < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }
}

private fun playtime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h %02dm".format(minutes % 60)
}

@Composable private fun FavoriteStar(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2 + this.size.height * .04f)
        val outer = this.size.width * .5f; val inner = outer * .45f
        val star = Path().apply {
            for (n in 0 until 10) {
                val r = if (n % 2 == 0) outer else inner
                val a = Math.toRadians((-90 + n * 36).toDouble())
                val x = c.x + r * kotlin.math.cos(a).toFloat(); val y = c.y + r * kotlin.math.sin(a).toFloat()
                if (n == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(star, Palette.Favorite)
        drawPath(star, Palette.Favorite, style = Stroke(this.size.width * .08f, join = StrokeJoin.Round))
    }
}

/** "Gameplay preview" with the video's place in it, and whether its sound is on. */
@Composable private fun PreviewPill(progress: Float, sound: Boolean) {
    Row(Modifier.height(28.dp).mediaPill().padding(horizontal = 12.dp).testTag("preview-pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ink = Color(0xFFE5E5EA)
        Canvas(Modifier.size(14.dp)) {
            val k = size.width / 24f
            val line = Stroke(2.2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val speaker = Path().apply { moveTo(4f * k, 9f * k); lineTo(8f * k, 9f * k); lineTo(13f * k, 5f * k); lineTo(13f * k, 19f * k); lineTo(8f * k, 15f * k); lineTo(4f * k, 15f * k); close() }
            drawPath(speaker, ink, style = line)
            if (sound) {
                drawArc(ink, -50f, 100f, false, Offset(11f * k, 7f * k), androidx.compose.ui.geometry.Size(8f * k, 10f * k), style = line)
            } else {
                drawLine(ink, Offset(17f * k, 9f * k), Offset(21f * k, 15f * k), 2.2f * k, StrokeCap.Round)
                drawLine(ink, Offset(21f * k, 9f * k), Offset(17f * k, 15f * k), 2.2f * k, StrokeCap.Round)
            }
        }
        Text("Gameplay preview", Typo.Small.copy(fontWeight = FontWeight.SemiBold), color = ink, lines = 1)
        Box(Modifier.size(40.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(ink))
        }
    }
}

/** The empty library is the welcome: one sentence, one action. */
@Composable private fun BoxScope.Welcome(shell: ConsoleShell, swapped: Boolean, modifier: Modifier) {
    Column(modifier.align(Alignment.Center).widthIn(max = 640.dp).testTag("welcome"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your games,\nbeautifully collected.", Typo.Display.copy(textAlign = TextAlign.Center))
        Text("Choose the folder that holds your games, on this device or an SD card. Cartridge sorts them by console. Your files are never changed.",
            Typo.Body.copy(textAlign = TextAlign.Center), Modifier.padding(top = 16.dp, bottom = 28.dp).widthIn(max = 460.dp), Palette.Secondary)
        Row(Modifier.height(Layout.Target + 4.dp).clip(RoundedCornerShape(Radii.Pill)).background(Palette.Label).tap("welcome-add") { shell.addGames() }
            .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add your games", Typo.Headline, color = Palette.Canvas, lines = 1)
        }
        Text(GameSystem.entries.joinToString("  ·  ") { it.shortName }, Typo.Caption.copy(textAlign = TextAlign.Center), Modifier.padding(top = 28.dp)
            .widthIn(max = 520.dp).testTag("welcome-consoles"), Palette.Tertiary, lines = 3)
    }
    HintBar(listOf(Hint(Pad.confirm(swapped), "Add games", primary = true) { shell.addGames() }, Hint(Pad.Menu, "Quick menu") { shell.openMenu() }),
        modifier.align(Alignment.BottomStart))
}

@Composable private fun BoxScope.NoResults(state: CollectionState, shell: ConsoleShell, swapped: Boolean, modifier: Modifier) {
    Column(modifier.align(Alignment.Center).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No games match “${state.query.search}”", Typo.Title.copy(textAlign = TextAlign.Center), Modifier.testTag("selected-title"))
        Text("Search looks at titles, developers and genres across every console.", Typo.Body.copy(textAlign = TextAlign.Center),
            Modifier.padding(top = 10.dp), Palette.Secondary)
    }
    HintBar(listOf(Hint(Pad.back(swapped), "Clear search", tag = "back") { shell.back() }), modifier.align(Alignment.BottomEnd))
}

/** Frames drawn in the last second, for the frame-rate readout. */
@Composable internal fun rememberFrameRate(): Int {
    var rate by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0; var since = 0L
        while (true) {
            withFrameNanos { now ->
                if (since == 0L) since = now
                frames++
                if (now - since >= 1_000_000_000L) { rate = frames; frames = 0; since = now }
            }
        }
    }
    return rate
}
