package dev.cartridge.ui.screens

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.domain.MediaMotion
import dev.cartridge.session.CollectionScreen
import dev.cartridge.session.CollectionState
import dev.cartridge.session.Launch
import dev.cartridge.ui.design.*

/**
 * Launching (spec 4.4, Launch.dc.html): while a game is on its way in, its screenshot at half strength behind a radial
 * vignette, its box turned slightly toward the player, its name, what the start does and how far it has got. It rises
 * over the shelf as the shelf's box comes forward, and when the game's first picture is ready the box flies on toward
 * the camera as the screen fades into the game. [pauseShortcut] names the buttons that open the pause menu in play.
 */
@Composable fun LaunchScreen(state: CollectionState, pauseShortcut: String, modifier: Modifier = Modifier) {
    val reduced = state.reducedMotion
    // The launch stays drawn while it fades out after the coordinator has let go of it.
    var shown by remember { mutableStateOf<Launch?>(null) }
    val appear = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val current = state.launch
    if (current != null && current != shown) SideEffect { shown = current }
    LaunchedEffect(current?.gameId) {
        if (current != null) {
            leave.snapTo(0f); progress.snapTo(.08f)
            // From the shelf the screen rises while the box comes forward; from a resumed session it has the curtain's time.
            val fromShelf = state.screen == CollectionScreen.Inserting
            if (reduced) appear.snapTo(1f)
            else { appear.snapTo(0f); appear.animateTo(1f, tween(if (fromShelf) 560 else MediaMotion.CURTAIN_MS, delayMillis = if (fromShelf) 200 else 0, easing = DesignMotion.Ease)) }
        } else if (shown != null) {
            if (!reduced) {
                progress.animateTo(1f, tween(160))
                leave.animateTo(1f, tween(LEAVE_MS, easing = DesignMotion.Ease))
            }
            shown = null; appear.snapTo(0f); leave.snapTo(0f); progress.snapTo(0f)
        }
    }
    val step = (current ?: shown)?.step
    LaunchedEffect(current?.gameId, step) {
        if (current == null) return@LaunchedEffect
        when (step) {
            // The core loads for as long as it takes; the bar keeps moving, slower the longer it waits, and never fills.
            1 -> { progress.animateTo(.34f, tween(300)); progress.animateTo(.84f, tween(5000, easing = LinearOutSlowInEasing)) }
            2 -> progress.animateTo(.94f, tween(260))
        }
    }
    val launch = current ?: shown ?: return
    val game = state.allGames.firstOrNull { it.id == launch.gameId } ?: return
    val media = LocalGameMedia.current
    val faces = rememberFaces(media, game.label, game.shownAs)
    val look = remember(game.title, game.system, game.metadata["year"], faces) { BoxLook.of(game, faces) }
    val gone = leave.value
    val alpha = appear.value * (1f - gone)
    if (alpha <= 0f) return

    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Palette.Canvas).testTag("launch-screen")
        .semantics { contentDescription = "Starting ${look.title}" }) {
        val cx = maxWidth.value / 2f
        // The game's own scenery at 50 %, softened, under a vignette that keeps the middle open.
        MediaBackdrop(game, faces, Modifier.fillMaxSize().alpha(.5f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(2.dp) else Modifier),
            playVideo = false)
        Canvas(Modifier.fillMaxSize()) {
            // radial-gradient(ellipse 60% 70% at 50% 42%): a circle of the ellipse's height, stretched to its width.
            val rx = size.width * .6f; val ry = size.height * .7f
            val centre = Offset(size.width / 2f, size.height * .42f)
            scale(rx / ry, 1f, centre) {
                drawRect(Brush.radialGradient(0f to Color(0x33000000), .7f to Color(0xD9000000), 1f to Color.Black, center = centre, radius = ry),
                    Offset(-size.width, -size.height), size.copy(size.width * 3f, size.height * 3f))
            }
        }

        // The box, at Game detail's size, fitted to the mockup's 262 so a tall case keeps clear of the words.
        val (baseW, baseH) = boxSize(game.shownAs)
        val k = minOf(1.24f, BOX_HEIGHT / baseH)
        val w = baseW * k; val h = baseH * k
        // It settles in from slightly nearer as the screen rises, and flies on toward the camera as it fades.
        val arrive = 1f - appear.value
        val near = arrive * .12f + gone * gone * .9f
        GameBox(look, w.dp, h.dp, 28.dp, -8f * (1f - gone), Modifier.absoluteOffset((cx - w / 2f).dp, (140f + BOX_HEIGHT - h).dp)
            .graphicsLayer { scaleX = 1f + near; scaleY = 1f + near; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .55f) },
            translateZ = 60.dp, focused = true)

        Column(Modifier.absoluteOffset(0.dp, 456.dp).fillMaxWidth().alpha(1f - gone), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            look.series?.let { Text(it, Typo.Caption.copy(fontWeight = FontWeight.SemiBold), color = Palette.Quiet, lines = 1) }
            Text(if (look.series != null) look.short else look.title, Typo.Title, Modifier.widthIn(max = 1000.dp).testTag("launch-title"), lines = 1)
            val line = listOfNotNull(launch.note, game.system.core.engine.substringBeforeLast(" ")).joinToString(" · ")
            Text(line, Typo.Callout, Modifier.padding(top = 4.dp).testTag("launch-note"), Palette.Secondary, lines = 1)
            Box(Modifier.padding(top = 18.dp).size(220.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x29FFFFFF))
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f) }) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).clip(RoundedCornerShape(2.dp)).background(Palette.Label))
            }
        }

        PauseHint(pauseShortcut, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).alpha(1f - gone))
    }
}

/** The glass pill under the launch: which buttons open the pause menu during play. */
@Composable private fun PauseHint(shortcut: String, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(Radii.Pill)).background(Color(0x14FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val ink = Typo.Caption.copy(fontWeight = FontWeight.Medium)
        if (shortcut == STICKS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Glyph(Pad.L3, size = 22.dp); Text("+", ink, color = Palette.Soft, lines = 1); Glyph(Pad.R3, size = 22.dp)
            }
            Text("during play opens the pause menu", ink, color = Palette.Soft, lines = 1)
        } else Text("Press $shortcut during play to open the pause menu", ink, color = Palette.Soft, lines = 1)
    }
}

/** The default pause shortcut, drawn as its two glyphs. */
const val STICKS = "L3 + R3"
private const val BOX_HEIGHT = 262f
private const val LEAVE_MS = 460
