package dev.cartridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.EngineStatus

/**
 * The one thing that rests on a game (ADR 0031): in its top corner while it is frozen, or while it runs faster than
 * normal, with the speed. Frozen shows only the pause bars. It is drawn over the view, so a state's picture never has it.
 */
@Composable fun GameBadge(status: EngineStatus, modifier: Modifier = Modifier) {
    val fast = status.state == SessionState.Running && status.speed > 1
    if (!status.frozen && !fast) return
    Row(modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.L).clip(RoundedCornerShape(Radius.Row)).background(Ink.Scrim)
        .padding(horizontal = Space.M, vertical = Space.S).testTag("game-badge")
        .semantics { contentDescription = if (status.frozen) "Frozen" else "Fast-forward ${status.speed}×" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
        // Drawn rather than typed: the pause and play characters are emoji in some of Android's fonts.
        Canvas(Modifier.size(width = if (status.frozen) 12.dp else 18.dp, height = 14.dp)) {
            if (status.frozen) {
                val bar = size.width * .36f
                listOf(0f, size.width - bar).forEach { x -> drawRoundRect(Ink.Text, Offset(x, 0f), Size(bar, size.height), CornerRadius(1.5.dp.toPx())) }
            } else {
                val half = size.width / 2
                listOf(0f, half).forEach { x ->
                    drawPath(Path().apply { moveTo(x, 0f); lineTo(x + half, size.height / 2); lineTo(x, size.height); close() }, Ink.Text)
                }
            }
        }
        if (!status.frozen) Txt("${status.speed}×", Type.Heading, lines = 1)
    }
}
