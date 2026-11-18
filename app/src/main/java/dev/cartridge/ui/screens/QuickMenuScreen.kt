package dev.cartridge.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.environment.DeviceControls
import dev.cartridge.shell.ConsoleShell
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.Page
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.*
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/**
 * The Quick menu (spec 4.6, QuickMenu.dc.html): a glass panel at the right over the dimmed launcher, with the clock and
 * battery, the device tiles and sliders, and under them what the library can do. Home or Menu opens and closes it.
 */
@Composable fun QuickMenuScreen(shell: ConsoleShell, device: DeviceStatus, swapped: Boolean, modifier: Modifier = Modifier) {
    val page = shell.page() ?: return
    if (page.key != "menu") return
    val controls = shell.device
    val motion = LocalDesignMotion.current
    val open = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { open.animateTo(1f, motion.fade(220)) }
    // Volume keys, a charger or a controller may change what it shows while it is open.
    LaunchedEffect(Unit) { while (true) { delay(1000); controls.refresh() } }
    val focused = shell.focused(page)

    Box(modifier.fillMaxSize().testTag("quick-menu")) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = open.value }.background(Color(0x8C000000)).tap("quick-close") { shell.back() })
        Column(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp, bottom = 24.dp).width(400.dp).fillMaxHeight()
            .graphicsLayer { alpha = open.value; translationX = (1f - open.value) * 24.dp.toPx() }
            .glass().tap("quick-panel") {}.padding(horizontal = 22.dp, vertical = 22.dp).testTag("system-menu-panel")) {
            Header(device, controls)
            val lines = shell.quickLines(page)
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.filter { line -> line.first().id.startsWith("tile:") }.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { line.forEach { Tile(it, it === focused, page, shell, Modifier.weight(1f)) } }
                }
                lines.filter { line -> line.first().id.startsWith("slider:") }.forEach { line -> Slider(line.first(), line.first() === focused, page, shell) }
            }
            val actions = lines.filter { line -> line.first().id.startsWith("wide:") || line.first().id.startsWith("act:") }
            if (actions.isNotEmpty()) {
                Text("LIBRARY", Typo.Section, Modifier.padding(start = 6.dp, top = 18.dp, bottom = 10.dp), Palette.Tertiary, lines = 1)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { line.forEach { Action(it, it === focused, page, shell, Modifier.weight(1f)) } }
                    }
                }
            }
        }
        val allow = focused?.value == "Allow access"
        val slider = focused?.id?.startsWith("slider:") == true
        val hints = buildList {
            add(Hint(Pad.back(swapped), "Close", tag = "back") { shell.back() })
            if (focused != null && focused.enabled && (allow || !slider)) add(Hint(Pad.confirm(swapped),
                when { allow -> "Allow"; focused.id == "tile:quiet" -> "Toggle"; else -> "Open" }, primary = true, tag = "quick-select") { shell.select() })
            if (slider && !allow) add(Hint(Pad.DpadX, "Adjust"))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 22.dp).graphicsLayer { alpha = open.value },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HintRow(hints, gap = 24.dp)
            focused?.note?.takeIf { allow || focused.id.startsWith("wide:") }?.let {
                Text(it, Typo.Caption, color = Palette.Tertiary, lines = 1)
            }
        }
    }
}

/** The clock and date at the left, the battery at the right, and its bar under both. */
@Composable private fun Header(device: DeviceStatus, controls: DeviceControls) {
    val date = remember(device.time) {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(device.time, Typo.Display.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold).tabular(), Modifier.testTag("quick-clock"), lines = 1)
            Text(date, Typo.Body.copy(fontSize = 15.sp), Modifier.padding(top = 4.dp), Palette.Secondary, lines = 1)
        }
        device.battery?.let { level ->
            Column(horizontalAlignment = Alignment.End) {
                Text("$level%", Typo.Title.copy(fontSize = 22.sp).tabular(), lines = 1)
                val line = when {
                    device.charging && controls.fullIn != null -> "Charging · full in ${duration(controls.fullIn!!)}"
                    device.charging -> "Charging"
                    device.low -> "Battery low"
                    else -> "On battery"
                }
                Text(line, Typo.Caption, Modifier.padding(top = 4.dp), Palette.Secondary, lines = 1)
            }
        }
    }
    device.battery?.let { level ->
        Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x1FFFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).clip(RoundedCornerShape(3.dp))
                .background(if (device.low) Palette.Destructive else Palette.Success))
        }
    }
}

private fun duration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** A device tile: its round icon, blue while it is on or connected, its name and its state. */
@Composable private fun Tile(row: MenuRow, focused: Boolean, page: Page, shell: ConsoleShell, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.height(65.dp).focusLift(focused, shape, scale = 1.03f, elevation = 30.dp).clip(shape)
        .background(if (focused) Palette.Label else Color(0x14FFFFFF)).tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) }
        .padding(start = 11.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (row.checked == true) Palette.Accent else if (focused) Color(0x1F000000) else Color(0x24FFFFFF)),
            contentAlignment = Alignment.Center) {
            Icon(tileIcon(row.id), if (row.checked == true || !focused) Color.White else Palette.Canvas, 20.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, Typo.Body.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Palette.Canvas else Palette.Label, lines = 1)
            Text(row.value.orEmpty(), Typo.Caption, color = if (focused) Palette.OnLabelSecondary else Palette.Secondary, lines = 1)
        }
    }
}

private fun tileIcon(id: String) = when (id) {
    "tile:wifi" -> "M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19h.01"
    "tile:bluetooth" -> "M7 7l10 10-5 5V2l5 5L7 17"
    "tile:quiet" -> "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    else -> "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
}

/** A capsule slider: white up to its value, the icon at its start and the value at its end. */
@Composable private fun Slider(row: MenuRow, focused: Boolean, page: Page, shell: ConsoleShell) {
    val shape = RoundedCornerShape(Radii.Pill)
    val level = row.value?.removeSuffix("%")?.toIntOrNull()
    val fraction = (level ?: 0) / 100f
    Box(Modifier.fillMaxWidth().height(45.dp).focusLift(focused, shape, scale = 1.02f, ring = 2.dp).clip(shape).background(Color(0x1FFFFFFF))
        .tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) }.testTag("slider-${row.id.removePrefix("slider:")}")) {
        if (level != null) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceAtLeast(.001f)).background(Palette.Label))
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = if (row.id == "slider:brightness")
                "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
                else if (level == 0) "M4 9h4l5-4v14l-5-4H4zM17 9l4 6M21 9l-4 6" else "M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7"
            Icon(icon, if (fraction > .1f) Palette.Canvas else Palette.Label, 20.dp)
            if (level == null) Text(row.title, Typo.Body.copy(fontWeight = FontWeight.SemiBold), Modifier.padding(start = 12.dp), lines = 1)
            Spacer(Modifier.weight(1f))
            Text(row.value.orEmpty(), Typo.Body.copy(fontWeight = FontWeight.Bold).tabular(), color = if (fraction > .88f) Palette.Canvas else Palette.Label, lines = 1)
        }
    }
}

/** One of the library's actions, a capsule like the mockup's power buttons. */
@Composable private fun Action(row: MenuRow, focused: Boolean, page: Page, shell: ConsoleShell, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val wide = row.id.startsWith("wide:")
    Row(modifier.height(40.dp).focusLift(focused, shape, scale = 1.03f).clip(shape).background(if (focused) Palette.Label else Color(0x14FFFFFF))
        .alpha(if (row.enabled) 1f else .45f).tap(row.tag, row.enabled) { shell.focus(page, row); shell.activate(page, row) }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (wide) Arrangement.spacedBy(10.dp) else Arrangement.Center) {
        if (wide) Icon("M7 4.5v15l12-7.5z", if (focused) Palette.Canvas else Palette.Label, 16.dp, filled = true)
        Text(row.title, Typo.Body.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Palette.Canvas else Palette.Label, lines = 1)
    }
}

/** A 24-unit outline icon from the mockup's SVG paths. */
@Composable private fun Icon(path: String, color: Color, size: Dp, filled: Boolean = false) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, Offset.Zero) {
            if (filled) drawPath(parsed, color) else drawPath(parsed, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
