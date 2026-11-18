package dev.cartridge.ui.design

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.RowKind
import dev.cartridge.shell.RowPicture
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The rows every list of settings, sheet, dialog and page draws (spec 2, Settings.dc.html): a focused row turns
 * white with black text. Its kind decides what sits at the trailing edge: a switch, a value to change with ‹ ›, a
 * value and a chevron, or, for the few rows whose value is a verb, a button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun SettingRow(row: MenuRow, focused: Boolean, modifier: Modifier = Modifier, button: Boolean = false,
    /** Sheets and dialogs print each row's help under it; settings and pages print the focused row's at the foot. */
    showNote: Boolean = false, onClick: () -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bring.bringIntoView() }
    val fill by animateColorAsState(if (focused) Palette.Label else Color.Transparent, label = "row-fill")
    val ink = when {
        focused -> if (row.kind == RowKind.Danger) Palette.Destructive else Palette.Canvas
        row.kind == RowKind.Danger -> Palette.DestructiveText
        row.kind == RowKind.Add -> Palette.AccentText
        else -> Palette.Label
    }
    val sub = if (focused) Palette.OnLabelSecondary else Palette.Tertiary
    val warn = if (row.warn && !focused) Palette.DestructiveText else sub
    val folder = row.id.startsWith("folder:")
    Row(modifier.fillMaxWidth().heightIn(min = if (row.detail != null || row.picture != null) 52.dp else 48.dp).bringIntoViewRequester(bring)
        .clip(RoundedCornerShape(12.dp)).background(fill)
        .tap(row.tag, enabled = row.focusable, action = onClick)
        .semantics { selected = focused }.padding(horizontal = 12.dp, vertical = 6.dp).alpha(if (row.enabled) 1f else .42f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.picture?.let { Thumbnail(it) }
        if (folder) OutlineIcon(FOLDER, ink, 20.dp, 2f)
        if (row.kind == RowKind.Add) OutlineIcon(PLUS, if (focused) Palette.Canvas else Palette.AccentText, 20.dp, 2.2f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, Typo.Callout.copy(fontWeight = if (folder || row.kind == RowKind.Add) FontWeight.SemiBold else FontWeight.Medium), color = ink, lines = 2)
            row.detail?.let { Text(it, Typo.Small.tabular(), color = warn, lines = 1) }
                ?: row.note?.takeIf { showNote }?.let { Text(it, Typo.Small, color = warn, lines = 3) }
        }
        when (row.kind) {
            RowKind.Toggle -> Switch(row.checked == true)
            RowKind.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (focused) Text("‹", Typo.Callout, color = sub, lines = 1)
                Text(row.value.orEmpty(), Typo.Callout.copy(fontWeight = FontWeight.Normal).tabular(), color = if (focused) Palette.Canvas else Palette.Secondary, lines = 1)
                if (focused) Text("›", Typo.Callout, color = sub, lines = 1)
            }
            RowKind.Action -> if (button) row.value?.let { label ->
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (focused) Color(0x14000000) else Color(0x1FFFFFFF)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, Typo.Caption.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Palette.Canvas else Palette.Label, lines = 1)
                }
            } else row.value?.let { Text(it, Typo.Caption.tabular(), Modifier.widthIn(max = 280.dp), warn, lines = 1) }
            RowKind.Link -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.value?.let { Text(it, Typo.Caption.tabular(), Modifier.widthIn(max = 280.dp), warn, lines = 1) }
                Chevron(sub)
            }
            RowKind.Info -> row.value?.let { Text(it, Typo.Callout.copy(fontWeight = FontWeight.Normal).tabular(), Modifier.widthIn(max = 280.dp), warn, lines = 1) }
            else -> row.value?.let { Text(it, Typo.Callout.copy(fontWeight = FontWeight.Normal), Modifier.widthIn(max = 280.dp), sub, lines = 1) }
        }
    }
}

/** Rows split at their headings: rows before the first heading form a group without one. */
class RowGroup(val title: String?, val rows: List<MenuRow>)

fun rowGroups(rows: List<MenuRow>): List<RowGroup> {
    val out = mutableListOf<RowGroup>()
    var title: String? = null
    var current = mutableListOf<MenuRow>()
    fun flush() { if (current.isNotEmpty()) out += RowGroup(title, current); current = mutableListOf() }
    rows.forEach { row -> if (row.kind == RowKind.Header) { flush(); title = row.title } else current += row }
    flush()
    return out
}

/**
 * A group of rows under its heading. [card] puts them on surface-2, as settings groups are drawn on black; on glass
 * they rest on the panel itself. Settings are divided by hairlines; places and pictures read as a plain list.
 */
@Composable fun RowCard(group: RowGroup, focused: MenuRow?, modifier: Modifier = Modifier, card: Boolean = true, buttons: Set<String> = emptySet(), showNotes: Boolean = false,
    top: (@Composable () -> Unit)? = null, onClick: (MenuRow) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.title?.let { Text(it.uppercase(), Typo.Section, Modifier.padding(start = if (card) 16.dp else 12.dp), Palette.Tertiary, lines = 1) }
        Column(Modifier.fillMaxWidth().then(if (card) Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface2) else Modifier)) {
            top?.invoke()
            Column(Modifier.padding(if (card) 4.dp else 0.dp)) {
                val lined = group.rows.none { it.id.startsWith("folder:") || it.kind == RowKind.Add || it.picture != null }
                group.rows.forEachIndexed { index, row ->
                    val next = group.rows.getOrNull(index + 1)
                    SettingRow(row, row === focused, button = row.id in buttons, showNote = showNotes) { onClick(row) }
                    if (lined && next != null && row !== focused && next !== focused)
                        Box(Modifier.padding(start = 12.dp, end = 8.dp).fillMaxWidth().height(1.dp).background(Palette.Separator))
                }
            }
        }
    }
}

@Composable fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Palette.Success else Color(0x52787880), label = "switch-track")
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, label = "switch-knob")
    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track)) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(Color.White))
    }
}

@Composable fun Chevron(color: Color) {
    Canvas(Modifier.size(8.dp, 12.dp)) {
        val k = size.width / 8f
        drawLine(color, Offset(2 * k, 2 * k), Offset(6 * k, 6 * k), 2 * k, StrokeCap.Round)
        drawLine(color, Offset(6 * k, 6 * k), Offset(2 * k, 10 * k), 2 * k, StrokeCap.Round)
    }
}

/** A 24-unit outline icon from the mockups' SVG paths. */
@Composable fun OutlineIcon(path: String, color: Color, size: Dp, stroke: Float) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, Offset.Zero) { drawPath(parsed, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** A saved state's picture, read off the main thread. Until it is read, or where there is none, the frame stays empty. */
@Composable private fun Thumbnail(picture: RowPicture) {
    val image by produceState<ImageBitmap?>(null, picture) {
        value = picture.file?.let { file -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() } }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(80.dp, 45.dp).clip(shape).background(Palette.Canvas).border(1.dp, Palette.Separator, shape).testTag("thumbnail")) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

const val FOLDER = "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2h8.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"
const val PLUS = "M12 5v14M5 12h14"
