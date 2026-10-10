package dev.cartridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.RowKind

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

@Composable private fun Switch(on: Boolean, enabled: Boolean) {
    val accent = LocalAccent.current
    val track by animateColorAsState(if (on) accent else Ink.Line, LocalMotion.current.focus(), label = "switch-track")
    val offset by animateDpAsState(if (on) 14.dp else 2.dp, LocalMotion.current.focus(), label = "switch-thumb")
    Box(Modifier.size(30.dp, 18.dp).alpha(if (enabled) 1f else .4f).clip(CircleShape).background(track)) {
        Box(Modifier.padding(start = offset, top = 2.dp).size(14.dp).clip(CircleShape).background(if (on) Ink.Stage else Ink.Soft))
    }
}

/**
 * The one row used by every menu, sheet and settings page. Focus is a raised fill and an accent mark at the
 * leading edge; a disabled row stays readable but dims. [showNote] is false where a page prints the focused
 * row's note in its own help line.
 */
@Composable fun RowItem(row: MenuRow, focused: Boolean, showNote: Boolean = true, current: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    if (row.kind == RowKind.Header) {
        Txt(row.title.uppercase(), Type.Caption, Ink.Faint, Modifier.padding(start = 14.dp, top = Space.L, bottom = Space.S).testTag(row.tag))
        return
    }
    val fill by animateColorAsState(if (focused) Ink.Raised else Color.Transparent, motion.focus(), label = "row-fill")
    val mark by animateFloatAsState(if (focused || current) 1f else 0f, motion.focus(), label = "row-mark")
    val titleColor = when {
        row.kind == RowKind.Danger -> Ink.Danger
        row.warn -> Ink.Warning
        // Where you are, while focus is elsewhere: marked and tinted, but not raised.
        current && !focused -> accent
        focused -> Ink.Text
        row.kind == RowKind.Info -> Ink.Soft
        else -> Ink.Text.copy(alpha = .86f)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = Space.Row).clip(RoundedCornerShape(Radius.Row)).background(fill)
        .then(if (row.focusable) Modifier.clickable(interactionSource = null, indication = null, enabled = row.enabled, role = Role.Button, onClick = onClick) else Modifier)
        .alpha(if (row.enabled) 1f else .42f).testTag(row.tag).semantics { selected = focused }.padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 5.dp).size(3.dp, 18.dp).alpha(mark).clip(CircleShape).background(accent))
        Column(Modifier.weight(1f).padding(start = 6.dp, top = 9.dp, bottom = 9.dp)) {
            Txt(row.title, Type.Body.copy(fontWeight = if (focused) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal), titleColor, lines = 2)
            if (showNote && row.note != null) Txt(row.note, Type.Label, if (row.warn) Ink.Warning.copy(alpha = .8f) else Ink.Faint, Modifier.padding(top = 2.dp), lines = 3)
        }
        when (row.kind) {
            RowKind.Toggle -> Switch(row.checked == true, row.enabled)
            RowKind.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                if (focused) Txt("‹", Type.Body, Ink.Faint)
                Txt(row.value.orEmpty(), Type.Body, if (focused) accent else Ink.Soft, lines = 1)
                if (focused) Txt("›", Type.Body, Ink.Faint)
            }
            RowKind.Link -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                row.value?.let { Txt(it, Type.Body, Ink.Soft, Modifier.widthIn(max = 260.dp), lines = 1) }
                Txt("›", Type.Heading, Ink.Faint)
            }
            else -> row.value?.let { Txt(it, Type.Body, Ink.Soft, Modifier.padding(start = Space.M).widthIn(max = 300.dp), lines = 1) }
        }
    }
}

/** A scrolling list of rows that keeps the focused row, and a little of what surrounds it, in view. */
@Composable fun RowList(rows: List<MenuRow>, focused: MenuRow?, modifier: Modifier = Modifier, showNotes: Boolean = true, activate: (MenuRow) -> Unit) {
    val margin = with(LocalDensity.current) { 56.dp.toPx() }
    val rowHeight = with(LocalDensity.current) { Space.Row.toPx() }
    val requesters = remember(rows.map { it.id }) { rows.associate { it.id to BringIntoViewRequester() } }
    LaunchedEffect(focused?.id, rows.size) {
        val first = rows.firstOrNull { it.focusable }
        // Reveal the heading above the first row instead of stopping just beneath it.
        requesters[focused?.id]?.bringIntoView(Rect(0f, if (focused?.id == first?.id) -margin * 4 else -margin, 1f, rowHeight + margin))
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Box(Modifier.bringIntoViewRequester(requesters.getValue(row.id))) {
                RowItem(row, row.id == focused?.id, showNotes) { activate(row) }
            }
        }
    }
}

/** A panel at the trailing edge. The stage stays visible beside it. */
@Composable fun BoxScope.SidePanel(width: Dp, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width).background(Ink.Surface).testTag(tag)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
        .padding(horizontal = Space.L, vertical = Space.L), content = content)
}

@Composable fun Scrim(modifier: Modifier = Modifier, dismiss: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize().background(Ink.Scrim).then(if (dismiss != null) Modifier.tap("scrim", action = dismiss) else Modifier))
}

@Composable fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.Line)) {
        val shown by animateFloatAsState(fraction ?: 0f, LocalMotion.current.enter(), label = "progress")
        if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(CircleShape).background(accent))
    }
}

/** A short line that confirms something happened, or a warning that stays until its cause is gone. */
@Composable fun Notice(text: String, warning: Boolean, tag: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(Radius.Row)).background(Ink.Raised).padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 520.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
        if (warning) Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.Warning))
        Txt(text, Type.Label.copy(lineHeight = Type.Body.lineHeight), if (warning) Ink.Warning else Ink.Text, Modifier.testTag(tag), lines = 4)
    }
}
