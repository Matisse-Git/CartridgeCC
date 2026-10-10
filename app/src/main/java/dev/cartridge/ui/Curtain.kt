package dev.cartridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.cartridge.domain.LaunchTransition
import dev.cartridge.domain.MediaMotion
import kotlin.math.hypot

/**
 * The black that covers the screen while the stage changes hands between the library and a game.
 * It closes in [MediaMotion.CURTAIN_MS], the time the coordinator waits before taking the stage apart, and
 * opens a little more slowly on what is ready beneath it. Reduced motion closes and opens it as a cut.
 */
@Composable fun Curtain(closed: Boolean, style: LaunchTransition, modifier: Modifier = Modifier) {
    val reduced = LocalMotion.current.reduced
    val cover = remember { Animatable(if (closed) 1f else 0f) }
    LaunchedEffect(closed, reduced) {
        val target = if (closed) 1f else 0f
        if (reduced) cover.snapTo(target)
        else cover.animateTo(target, tween(if (closed) MediaMotion.CURTAIN_MS else OPEN_MS, easing = if (closed) Closing else Opening))
    }
    val hole = remember { Path() }
    Canvas(modifier.fillMaxSize().testTag("curtain").semantics { stateDescription = if (closed) "closed" else "open" }) {
        val amount = cover.value
        if (amount <= 0f) return@Canvas
        when (style) {
            LaunchTransition.Fade -> drawRect(Color.Black, alpha = amount)
            LaunchTransition.Iris -> {
                // Everything outside a circle that shrinks to the centre; at the end the circle is gone.
                val radius = hypot(size.width, size.height) / 2f * (1f - amount)
                // Emptying a path also returns it to the default winding fill, under which the circle would fill too.
                hole.rewind()
                hole.fillType = PathFillType.EvenOdd
                hole.addRect(Rect(Offset.Zero, size))
                if (radius > .5f) hole.addOval(Rect(center = Offset(size.width / 2f, size.height / 2f), radius = radius))
                drawPath(hole, Color.Black)
            }
            LaunchTransition.Shutter -> {
                val edge = size.height / 2f * amount
                drawRect(Color.Black, Offset.Zero, Size(size.width, edge))
                drawRect(Color.Black, Offset(0f, size.height - edge), Size(size.width, edge))
            }
        }
    }
}

private const val OPEN_MS = 420
private val Closing = CubicBezierEasing(.4f, 0f, 1f, 1f)
private val Opening = CubicBezierEasing(0f, 0f, .2f, 1f)
