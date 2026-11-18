package dev.cartridge.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A 4-tall progress track. With a [fraction] it fills to it; without one a short segment sweeps across while the
 * work runs, or, with reduced motion, the track rests and the words beside it say what is happening.
 */
@Composable fun Progress(fraction: Float?, modifier: Modifier = Modifier) {
    val motion = LocalDesignMotion.current
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.Surface3)) {
        if (fraction != null) {
            val shown by animateFloatAsState(fraction, motion.fade(), label = "progress")
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(CircleShape).background(Palette.Label))
        } else if (!motion.reduced) {
            val t by rememberInfiniteTransition(label = "progress-sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val segment = maxWidth * .3f
                Box(Modifier.offset(x = (maxWidth + segment) * t - segment).width(segment).fillMaxHeight().clip(CircleShape).background(Palette.Label))
            }
        }
    }
}
