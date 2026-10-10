package dev.cartridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClockStyle(val title: String) { System("Match Android"), Twelve("12-hour"), TwentyFour("24-hour") }

data class DeviceStatus(val time: String = "", val battery: Int? = null, val charging: Boolean = false) {
    val low get() = battery != null && battery <= 15 && !charging
}

private fun formatTime(context: Context, style: ClockStyle): String = when (style) {
    ClockStyle.System -> DateFormat.getTimeFormat(context).format(Date())
    // The locale's own pattern for hour and minute, with only the 12/24 choice forced.
    ClockStyle.Twelve -> SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "hm"), Locale.getDefault()).format(Date())
    ClockStyle.TwentyFour -> SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm"), Locale.getDefault()).format(Date())
}

/**
 * Real device time and battery. Android broadcasts drive it: one tick a minute, clock/zone/locale changes,
 * and battery changes. Nothing polls, and the receiver exists only while the owner is STARTED.
 */
@Composable fun rememberDeviceStatus(style: ClockStyle): DeviceStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(DeviceStatus(formatTime(context, style))) }
    DisposableEffect(context, owner, style) {
        fun time() { status = status.copy(time = formatTime(context, style)) }
        fun battery(intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val state = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status = status.copy(battery = if (level >= 0 && scale > 0) (100L * level / scale).toInt().coerceIn(0, 100) else null,
                charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery(intent) else time() }
        }
        var registered = false
        fun start() {
            if (registered) return
            time()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            // Battery state is sticky: registration returns the current reading without waiting for a change.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::battery)
            registered = true
        }
        fun stop() { if (registered) { context.unregisterReceiver(receiver); registered = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    return status
}

@Composable fun StatusCluster(status: DeviceStatus, showBattery: Boolean, showPercent: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.testTag("system-status"), horizontalArrangement = Arrangement.spacedBy(Space.M), verticalAlignment = Alignment.CenterVertically) {
        BasicText(status.time, Modifier.testTag("system-time"), Type.Label.copy(color = Ink.Soft))
        if (showBattery) status.battery?.let { level ->
            val color = if (status.low) Ink.Warning else Ink.Soft
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showPercent) BasicText("$level%", Modifier.testTag("system-battery-level"), Type.Label.copy(color = color))
                Canvas(Modifier.size(22.dp, 11.dp).testTag("system-battery").semantics {
                    contentDescription = "Battery $level percent" + if (status.charging) ", charging" else ""
                }) {
                    val cap = 2.dp.toPx(); val stroke = 1.dp.toPx()
                    val body = Size(size.width - cap - stroke, size.height - stroke)
                    drawRoundRect(color, Offset(stroke / 2, stroke / 2), body, CornerRadius(2.5.dp.toPx()), style = Stroke(stroke))
                    drawRoundRect(color, Offset(body.width + stroke, size.height * .3f), Size(cap, size.height * .4f), CornerRadius(1.dp.toPx()))
                    val inset = 2.dp.toPx()
                    drawRoundRect(color, Offset(inset, inset), Size(((body.width - inset * 2 + stroke) * level / 100f).coerceAtLeast(1.dp.toPx()), size.height - inset * 2),
                        CornerRadius(1.dp.toPx()))
                    if (status.charging) {
                        val cx = (body.width + stroke) / 2; val h = size.height
                        drawPath(Path().apply {
                            moveTo(cx + 1.5.dp.toPx(), h * .08f); lineTo(cx - 2.5.dp.toPx(), h * .56f); lineTo(cx - .2.dp.toPx(), h * .56f)
                            lineTo(cx - 1.5.dp.toPx(), h * .92f); lineTo(cx + 2.5.dp.toPx(), h * .44f); lineTo(cx + .2.dp.toPx(), h * .44f); close()
                        }, Ink.Stage)
                    }
                }
            }
        }
    }
}
