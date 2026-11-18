package dev.cartridge.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Text in the console's own styles. Material's content-colour machinery is not used. */
@Composable fun Txt(text: String, style: TextStyle, color: Color = Ink.Text, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = lines)

/** A controller button drawn as a keycap. Face buttons are round; everything else is a pill. */
@Composable fun Glyph(label: String, tint: Color = Ink.Soft) {
    val round = label.length == 1
    Box(Modifier.height(18.dp).then(if (round) Modifier.width(18.dp) else Modifier).border(1.dp, tint.copy(alpha = .55f), if (round) CircleShape else RoundedCornerShape(5.dp))
        .padding(horizontal = if (round) 0.dp else 6.dp), contentAlignment = Alignment.Center) {
        Txt(label, Type.Caption.copy(letterSpacing = TextStyle.Default.letterSpacing), tint)
    }
}

/** A button and what it does here. With an [action] the hint is also the touch control for the same thing. */
class Hint(val glyph: String, val label: String, val tag: String? = null, val action: (() -> Unit)? = null)

@Composable fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier, color: Color = Ink.Faint) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.L), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { hint ->
            Row(if (hint.action != null) Modifier.tap(hint.tag ?: "hint-${hint.label.lowercase()}", action = hint.action).padding(vertical = 6.dp) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(hint.glyph, color)
                Txt(hint.label, Type.Label, color)
            }
        }
    }
}
