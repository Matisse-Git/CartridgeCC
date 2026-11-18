package dev.cartridge.ui.design

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cartridge.shell.Tab
import dev.cartridge.ui.DeviceStatus
import dev.cartridge.ui.design.Typo.tabular

/**
 * The top bar (§3): the Cartridge mark and name, the tabs between their L2 and R2 glyphs, and the real device
 * status. 76 tall with 40 side margins. A tab whose screen is not built yet is shown quieter and cannot be chosen.
 */
@Composable fun TopBar(current: Tab, onTab: (Tab) -> Unit, device: DeviceStatus, modifier: Modifier = Modifier,
    showStatus: Boolean = true, showPercent: Boolean = true, frameRate: Int? = null) {
    Box(modifier.fillMaxWidth().height(Layout.TopBar).padding(horizontal = Layout.Margin)) {
        Row(Modifier.align(Alignment.CenterStart).width(260.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CartridgeMark()
            Text("Cartridge", Typo.Headline, lines = 1)
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(Pad.L2)
            Row(Modifier.clip(RoundedCornerShape(Radii.Pill)).background(Color(0x9928282A)).border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(Radii.Pill)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tab.entries.forEach { tab ->
                    val selected = tab == current
                    val fill by animateColorAsState(if (selected) Palette.Label else Color.Transparent, LocalDesignMotion.current.fade(200), label = "tab")
                    Box(Modifier.clip(RoundedCornerShape(Radii.Pill)).background(fill).tap("tab-${tab.name.lowercase()}", tab.ready) { onTab(tab) }
                        .padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(tab.title, Typo.Callout.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), lines = 1,
                            color = when { selected -> Palette.Canvas; tab.ready -> Palette.Label; else -> Palette.Tertiary })
                    }
                }
            }
            Glyph(Pad.R2)
        }
        Row(Modifier.align(Alignment.CenterEnd).width(260.dp).testTag("system-status"), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            val numbers = Typo.Callout.copy(fontWeight = FontWeight.SemiBold).tabular()
            if (frameRate != null && frameRate > 0) Text("$frameRate fps", numbers, Modifier.testTag("frame-rate"), Palette.Tertiary, lines = 1)
            if (showStatus) {
                if (rememberWifi()) WifiIcon()
                device.battery?.let { level ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showPercent) Text("$level%", numbers, color = if (device.low) Palette.DestructiveText else Palette.Quiet, lines = 1)
                        BatteryIcon(level, device.charging, device.low)
                    }
                }
                Text(device.time, numbers, Modifier.testTag("system-time"), lines = 1)
            }
        }
    }
}

/** The Cartridge mark: a cartridge with its label window and contacts, in label white. */
@Composable fun CartridgeMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp, 24.dp)) {
        val k = size.width / 22f
        val body = Path().apply {
            moveTo(4f * k, 1f * k); lineTo(18f * k, 1f * k)
            quadraticTo(20.5f * k, 1f * k, 20.5f * k, 3.5f * k); lineTo(20.5f * k, 21f * k)
            quadraticTo(20.5f * k, 23f * k, 18.5f * k, 23f * k); lineTo(15.5f * k, 23f * k); lineTo(15.5f * k, 20.5f * k)
            lineTo(6.5f * k, 20.5f * k); lineTo(6.5f * k, 23f * k); lineTo(3.5f * k, 23f * k)
            quadraticTo(1.5f * k, 23f * k, 1.5f * k, 21f * k); lineTo(1.5f * k, 3.5f * k)
            quadraticTo(1.5f * k, 1f * k, 4f * k, 1f * k); close()
        }
        drawPath(body, Palette.Label)
        drawRoundRect(Palette.Canvas, Offset(5f * k, 5f * k), Size(12f * k, 8f * k), CornerRadius(1.5f * k))
        listOf(6f, 10f, 14f).forEach { x -> drawRoundRect(Palette.Canvas, Offset(x * k, 16f * k), Size(2f * k, 2.5f * k), CornerRadius(.6f * k)) }
    }
}

@Composable private fun WifiIcon() {
    Canvas(Modifier.size(18.dp, 14.dp).testTag("wifi")) {
        val k = size.width / 24f
        val line = Stroke(2.2f * k, cap = StrokeCap.Round)
        fun arc(radius: Float, cy: Float) {
            drawArc(Palette.Label, 225f, 90f, false, Offset(12f * k - radius * k, cy * k - radius * k), Size(2 * radius * k, 2 * radius * k), style = line)
        }
        arc(14f, 16.5f + 0f); arc(9.2f, 16.5f + .2f); arc(4.3f, 16.5f + .3f)
        drawCircle(Palette.Label, 1.2f * k, Offset(12f * k, 16.5f * k))
    }
}

@Composable private fun BatteryIcon(level: Int, charging: Boolean, low: Boolean) {
    Canvas(Modifier.size(26.dp, 13.dp)) {
        val k = size.width / 26f
        val outline = Palette.Label.copy(alpha = .45f)
        drawRoundRect(outline, Offset(.75f * k, .75f * k), Size(21.5f * k, 11.5f * k), CornerRadius(3.5f * k), style = Stroke(1.5f * k))
        drawLine(outline, Offset(24f * k, 4.5f * k), Offset(24f * k, 8.5f * k), 1.6f * k, StrokeCap.Round)
        val fill = when { low -> Palette.Destructive; charging -> Palette.Success; else -> Palette.Label }
        drawRoundRect(fill, Offset(3f * k, 3f * k), Size(17f * k * level.coerceIn(4, 100) / 100f, 7f * k), CornerRadius(1.5f * k))
    }
}

/** Whether the device is on Wi-Fi now. False without the permission, so the icon is simply absent. */
@Composable fun rememberWifi(): Boolean {
    val context = LocalContext.current
    var wifi by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networks = mutableMapOf<Network, Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(networks) { networks[network] = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI); wifi = networks.values.any { it } }
            }
            override fun onLost(network: Network) { synchronized(networks) { networks.remove(network); wifi = networks.values.any { it } } }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback) }.isSuccess && manager != null
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return wifi
}
