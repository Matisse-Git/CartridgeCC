package dev.cartridge.ui.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.ui.design.Typo.tabular

/** Text in the design's own styles. */
@Composable fun Text(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = style.color, lines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = lines, softWrap = lines > 1)

/** The controller's buttons as the hint bar draws them (§2 Controller glyphs). */
enum class Pad(val label: String) {
    A("A"), B("B"), X("X"), Y("Y"), L1("L1"), R1("R1"), L2("L2"), R2("R2"), L3("L3"), R3("R3"), Menu(""), Home(""), DpadX(""), DpadY("");
    companion object {
        /** With the menu buttons swapped, B confirms and A goes back; the glyphs follow the buttons pressed. */
        fun confirm(swapped: Boolean) = if (swapped) B else A
        fun back(swapped: Boolean) = if (swapped) A else B
    }
}

/**
 * One glyph. [filled] is the primary action: a white disc with a black letter. Face buttons are 24 circles,
 * sticks a 30-wide pill, shoulders a 32×22 rounded rectangle.
 */
@Composable fun Glyph(pad: Pad, filled: Boolean = false, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    when (pad) {
        Pad.L1, Pad.R1, Pad.L2, Pad.R2 -> Box(modifier.size(32.dp, 22.dp).border(1.5.dp, Palette.ShoulderLine, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            Text(pad.label, TextStyle(fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Palette.Secondary, lines = 1)
        }
        else -> {
            val pill = pad == Pad.R3 || pad == Pad.L3 || pad == Pad.DpadX || pad == Pad.DpadY
            val shape = if (pill) RoundedCornerShape(size / 2) else CircleShape
            val ink = if (filled) Palette.Canvas else Palette.Label
            Box(modifier.height(size).width(if (pill) size * 1.25f else size).then(
                if (filled) Modifier.clip(shape).background(Palette.Label) else Modifier.border(1.5.dp, Palette.GlyphLine, shape)),
                contentAlignment = Alignment.Center) {
                when (pad) {
                    Pad.Menu -> Canvas(Modifier.size(size * .46f)) {
                        val w = 3.dp.toPx() * size.value / 24f
                        listOf(.17f, .5f, .83f).forEach { y -> drawLine(ink, Offset(this.size.width * .1f, this.size.height * y), Offset(this.size.width * .9f, this.size.height * y), w * .8f, StrokeCap.Round) }
                    }
                    Pad.Home -> Canvas(Modifier.size(size * .5f)) {
                        val s = this.size.width / 24f
                        val path = Path().apply { moveTo(4 * s, 11 * s); lineTo(12 * s, 4 * s); lineTo(20 * s, 11 * s); lineTo(20 * s, 20 * s); lineTo(15 * s, 20 * s)
                            lineTo(15 * s, 14 * s); lineTo(9 * s, 14 * s); lineTo(9 * s, 20 * s); lineTo(4 * s, 20 * s); close() }
                        drawPath(path, ink, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // The same pair of chevrons, turned upright for up / down.
                    Pad.DpadX, Pad.DpadY -> Canvas(Modifier.size(size * .58f, size * .42f).graphicsLayer { rotationZ = if (pad == Pad.DpadY) 90f else 0f }) {
                        val sx = this.size.width / 24f; val sy = this.size.height / 16f
                        val stroke = Stroke(3f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        drawPath(Path().apply { moveTo(7 * sx, 3 * sy); lineTo(2 * sx, 8 * sy); lineTo(7 * sx, 13 * sy) }, ink, style = stroke)
                        drawPath(Path().apply { moveTo(17 * sx, 3 * sy); lineTo(22 * sx, 8 * sy); lineTo(17 * sx, 13 * sy) }, ink, style = stroke)
                    }
                    else -> Text(pad.label, TextStyle(fontFamily = Inter, fontSize = (if (pill) 10 else if (filled) 12 else 11).sp * size.value / 24f,
                        fontWeight = if (filled) FontWeight.ExtraBold else FontWeight.Bold), color = ink, lines = 1)
                }
            }
        }
    }
}

/** A button and what it does here. With an [action] the hint is also its touch control. */
class Hint(val pad: Pad, val label: String, val primary: Boolean = false, val tag: String? = null, val action: (() -> Unit)? = null)

/**
 * The bottom hint bar (§2): 64 tall, 40 side margins, context text left in tertiary, hints right-aligned 26 apart.
 */
@Composable fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier, context: String? = null, gap: Dp = 26.dp, contextColor: Color = Palette.Tertiary) {
    Row(modifier.fillMaxWidth().height(Layout.HintBar).padding(horizontal = Layout.Margin), verticalAlignment = Alignment.CenterVertically) {
        Text(context.orEmpty(), Typo.Caption.tabular(), Modifier.weight(1f).testTag("hint-context"), contextColor, lines = 1)
        HintRow(hints, gap = gap)
    }
}

@Composable fun HintRow(hints: List<Hint>, modifier: Modifier = Modifier, gap: Dp = 26.dp) {
    Row(modifier.testTag("hints"), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { hint ->
            Row(if (hint.action != null) Modifier.tap(hint.tag ?: "hint-${hint.label.lowercase()}", action = hint.action) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(hint.pad, hint.primary)
                Text(hint.label, Typo.Callout.copy(fontWeight = if (hint.primary) FontWeight.SemiBold else FontWeight.Medium), lines = 1)
            }
        }
    }
}

/** A touch target with no ripple: focus and the pressed scale are the feedback. */
fun Modifier.tap(tag: String, enabled: Boolean = true, action: () -> Unit) =
    testTag(tag).clickable(interactionSource = null, indication = null, enabled = enabled, onClick = action)

/** Glass (§2): surface-2 at 72–78 % with a hairline border. The caller blurs what lies beneath, where it owns it. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(Radii.R28), alpha: Float = .74f) =
    clip(shape).background(Palette.Surface2.copy(alpha = alpha)).border(1.dp, Palette.GlassBorder, shape)

/** A small glass pill over media, as the preview and trailer badges are drawn. */
fun Modifier.mediaPill() = clip(RoundedCornerShape(Radii.Pill)).background(Color(0x73000000))

/**
 * The focus treatment (§1.4, §2 Motion): a focused item lifts — scale 1.04 and a deep shadow — over 250 ms;
 * pressed, it settles to 0.97. [ring] adds the white ring used on cards and thumbnails.
 */
@Composable fun Modifier.focusLift(focused: Boolean, shape: Shape, scale: Float = 1.04f, ring: Dp = 0.dp,
    interaction: MutableInteractionSource? = null, elevation: Dp = 24.dp): Modifier {
    val motion = LocalDesignMotion.current
    val pressed = interaction?.collectIsPressedAsState()?.value == true
    val target = when { pressed -> DesignMotion.PRESSED; focused -> scale; else -> 1f }
    val s by animateFloatAsState(target, motion.lift(), label = "lift")
    val lift by animateDpAsState(if (focused) elevation else 0.dp, motion.lift(), label = "lift-shadow")
    val ringWidth by animateDpAsState(if (focused) ring else 0.dp, motion.lift(), label = "ring")
    return this.graphicsLayer { scaleX = s; scaleY = s }
        .shadow(lift, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .then(if (ringWidth > 0.dp) Modifier.border(ringWidth, Palette.Label, shape) else Modifier)
}

/** Rest → white fill with black text when focused (rows, chips, pills). */
@Composable fun focusFill(focused: Boolean, rest: Color = Color.Transparent) = if (focused) Palette.Label else rest
@Composable fun focusInk(focused: Boolean, rest: Color = Palette.Label) = if (focused) Palette.Canvas else rest

/** An iOS-style switch in the spec's success green. */
@Composable fun Toggle(on: Boolean, modifier: Modifier = Modifier, width: Dp = 44.dp, height: Dp = 26.dp) {
    val motion = LocalDesignMotion.current
    val x by animateDpAsState(if (on) width - height else 0.dp, motion.lift(), label = "toggle")
    Box(modifier.size(width, height).clip(CircleShape).background(if (on) Palette.Success else Color(0xFF48484A))) {
        Box(Modifier.padding(start = x + 2.dp, top = 2.dp).size(height - 4.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}
