package dev.cartridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.library.Shelf
import dev.cartridge.session.CollectionState
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.plural

/** A touch target with no ripple: on this surface the object or the label is the feedback. */
internal fun Modifier.tap(tag: String, enabled: Boolean = true, action: () -> Unit) =
    testTag(tag).clickable(interactionSource = null, indication = null, enabled = enabled, onClick = action)

/** Darkens the edges so neighbours recede and the top and bottom text always sits on a quiet ground. */
@Composable fun StageShade(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.radialGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Ink.Stage.copy(alpha = .78f),
            center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * .64f))
        drawRect(Brush.verticalGradient(0f to Ink.Stage.copy(alpha = .62f), .22f to Color.Transparent, .68f to Color.Transparent, 1f to Ink.Stage.copy(alpha = .86f)))
    })
}

/**
 * Everything drawn over the 3D collection: where you are, the real device status, the focused game's name
 * and the few controls that matter here. No toolbar, no panels.
 */
@Composable fun BoxScope.LibraryChrome(state: CollectionState, shell: ConsoleShell, device: DeviceStatus, confirm: String, back: String) {
    val motion = LocalMotion.current
    val accent = LocalAccent.current
    val game = state.selectedGame
    val empty = state.loaded && state.allGames.isEmpty()
    val ready = state.loaded && !state.busy

    if (!empty) {
        // Touch: the edges browse, the object plays. Controllers never need these.
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight(.6f).width(96.dp).tap("previous", ready) { shell.coordinator.navigate(-1) })
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight(.6f).width(96.dp).tap("next", ready) { shell.coordinator.navigate(1) })
        Box(Modifier.align(Alignment.Center).size(260.dp, 220.dp).tap("insert", ready && state.rendererReady && game != null) { shell.select() })
    }

    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Space.Edge, vertical = Space.L),
        verticalAlignment = Alignment.Top) {
        if (!empty) Column(Modifier.tap("console-selector", ready && state.shelves.size > 1) { shell.switchShelf(1) }) {
            Crossfade(if (state.searching) "search" else state.shelf.key, animationSpec = motion.fade(), label = "shelf") { key ->
                Txt(if (key == "search") "Search" else Shelf.parse(key)?.title.orEmpty(), Type.Title, modifier = Modifier.testTag("shelf-title"), lines = 1)
            }
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                if (!state.searching && state.shelves.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.shelves.forEach { shelf ->
                        val current = shelf == state.shelf
                        val width by animateDpAsState(if (current) 14.dp else 4.dp, motion.focus(), label = "shelf-dot")
                        Box(Modifier.size(width, 4.dp).clip(CircleShape).background(if (current) accent else Ink.Faint.copy(alpha = .6f)))
                    }
                }
                Txt(when {
                    state.searching -> "“${state.query.search}” · " + if (game == null) "no results" else plural(state.slots.size, "result")
                    game == null -> ""
                    else -> plural(state.slots.size, "game")
                }, Type.Label, Ink.Faint, Modifier.testTag("shelf-count"), lines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            if (shell.showFrameRate && shell.frameRate > 0) Txt("${shell.frameRate} fps", Type.Label, Ink.Faint, Modifier.testTag("frame-rate"), lines = 1)
            if (shell.showStatus) StatusCluster(device, showBattery = true, showPercent = shell.showPercent)
            Canvas(Modifier.size(28.dp).tap("system-menu", state.loaded) { shell.openMenu() }.semantics { contentDescription = "Menu" }) {
                val x0 = size.width * .22f; val x1 = size.width * .78f
                listOf(.36f, .64f).forEach { y -> drawLine(Ink.Soft, Offset(x0, size.height * y), Offset(x1, size.height * y), 1.5.dp.toPx(), StrokeCap.Round) }
            }
        }
    }

    AnimatedVisibility(state.scanning && shell.import == null, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = Space.L),
        enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
        Row(Modifier.clip(RoundedCornerShape(Radius.Row)).background(Ink.Raised).padding(horizontal = 14.dp, vertical = 8.dp).tap("cancel-scan") { shell.coordinator.cancelScan() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Txt("Looking for new games", Type.Label, Ink.Soft)
            HintBar(listOf(Hint(back, "Stop")))
        }
    }

    when {
        empty -> Welcome(shell, confirm)
        game == null && state.loaded -> Column(Modifier.align(Alignment.Center).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("No games match “${state.query.search}”", Type.Title, modifier = Modifier.testTag("selected-title"))
            Txt("Search looks at titles, developers and genres across every console.", Type.Body, Ink.Soft, Modifier.padding(top = Space.S, bottom = Space.L))
            HintBar(listOf(Hint(back, "Clear search", "back", shell::back), Hint("START", "Menu")), color = Ink.Soft)
        }
        game != null -> {
            // A vertical shelf stands at the right, so its words sit beside it; otherwise they sit beneath the object.
            val side = state.layout == CollectionLayout.Vertical
            Column((if (side) Modifier.align(Alignment.CenterStart).width(330.dp) else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Space.Edge, vertical = Space.L),
                horizontalAlignment = if (side) Alignment.Start else Alignment.CenterHorizontally) {
            Box(Modifier.widthIn(max = 560.dp).tap("details", ready) { shell.openDetails() }) { Txt(game.title, Type.Title, modifier = Modifier.testTag("selected-title"), lines = if (side) 3 else 1) }
            val unified = state.searching || state.shelf !is Shelf.Console
            val facts = listOfNotNull(if (unified) game.system.title else null, game.metadata["year"]?.value?.takeIf(String::isNotBlank),
                game.metadata["developer"]?.value?.takeIf(String::isNotBlank))
            Row(Modifier.padding(top = 3.dp).heightIn(min = 16.dp), horizontalArrangement = Arrangement.spacedBy(Space.S), verticalAlignment = Alignment.CenterVertically) {
                if (game.favorite) Txt("Favorite", Type.Label, accent, Modifier.testTag("favorite-mark"))
                if (game.favorite && facts.isNotEmpty()) Txt("·", Type.Label, Ink.Faint)
                if (facts.isNotEmpty()) Txt(facts.joinToString("  ·  "), Type.Label, Ink.Soft, lines = 1)
            }
            val hintAlpha by animateFloatAsState(if (shell.hintsVisible && !state.busy) 1f else 0f, motion.fade(), label = "hints")
            val hints = buildList {
                add(Hint(when (state.layout) { CollectionLayout.Horizontal -> "◀ ▶"; CollectionLayout.Vertical -> "▲ ▼"; CollectionLayout.Grid -> "✥" }, "Browse"))
                add(Hint(confirm, "Play"))
                add(Hint("X", "Details"))
                add(Hint("RS", "Turn"))
                if (state.searching) add(Hint(back, "Clear search", "back", shell::back)) else if (state.shelves.size > 1) add(Hint("L1 R1", "Console"))
                add(Hint("START", "Menu"))
            }
            if (side) Column(Modifier.padding(top = Space.L).alpha(hintAlpha).testTag("library-hints"), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                hints.forEach { HintBar(listOf(it)) }
            } else HintBar(hints, Modifier.padding(top = Space.M).alpha(hintAlpha).testTag("library-hints"))
            }
        }
    }

    if (game != null && state.slots.size > 1) Txt("${state.selection.index + 1} / ${state.slots.size}", Type.Label, Ink.Faint,
        Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Space.Edge, vertical = Space.L).testTag("position"))
}

/** The empty library is the welcome: one sentence, one action, beside the real object. */
@Composable private fun BoxScope.Welcome(shell: ConsoleShell, confirm: String) {
    val accent = LocalAccent.current
    Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.5f).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = Space.Edge + Space.L)
        .testTag("welcome")) {
        Txt("Your games,\nbeautifully collected.", Type.Display)
        Txt("Choose the folder that holds your games, on this device or an SD card. Cartridge sorts them by console. Your files are never changed.",
            Type.Body, Ink.Soft, Modifier.padding(top = Space.M, bottom = Space.L).widthIn(max = 340.dp))
        Row(Modifier.clip(RoundedCornerShape(Radius.Row)).background(accent).tap("welcome-add") { shell.addGames() }.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Glyph(confirm, Ink.Stage)
            Txt("Add your games", Type.Heading, Ink.Stage)
        }
        Txt("Game Boy  ·  Game Boy Color  ·  Game Boy Advance  ·  PlayStation", Type.Label, Ink.Faint, Modifier.padding(top = Space.L))
    }
}

@Composable fun CenteredNote(text: String, modifier: Modifier = Modifier) = Txt(text, Type.Label.copy(textAlign = TextAlign.Center), Ink.Faint, modifier)
