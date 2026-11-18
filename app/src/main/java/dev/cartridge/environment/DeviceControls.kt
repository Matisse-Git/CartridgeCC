package dev.cartridge.environment

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The device settings the Quick menu shows and changes (spec 4.6): media volume, screen brightness, Do Not Disturb,
 * whether Wi-Fi is connected and how many controllers are. Brightness and Do Not Disturb need the player to allow
 * them once in Android's settings; until then their controls open that page. Wi-Fi and Bluetooth only open
 * Android's own panels, which is all Android lets an app do with them.
 */
class DeviceControls(private val context: Context) {
    /** Media volume, 0 to 1. */
    var volume by mutableFloatStateOf(0f); private set
    /** Screen brightness, 0 to 1, as Android stores it. */
    var brightness by mutableFloatStateOf(0f); private set
    var brightnessAllowed by mutableStateOf(false); private set
    var quiet by mutableStateOf(false); private set
    var quietAllowed by mutableStateOf(false); private set
    var wifi by mutableStateOf(false); private set
    var controllers by mutableIntStateOf(0); private set
    /** While charging, how long until full, where Android estimates it. */
    var fullIn by mutableStateOf<Long?>(null); private set

    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    /** Reads everything again: the player may have changed any of it outside Cartridge, or with the volume keys. */
    fun refresh() {
        runCatching {
            val audio = audio ?: return@runCatching
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        }
        runCatching {
            brightnessAllowed = Settings.System.canWrite(context)
            brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / MAX_BRIGHTNESS
        }
        runCatching {
            val notifications = notifications ?: return@runCatching
            quietAllowed = notifications.isNotificationPolicyAccessGranted
            quiet = notifications.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        }
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            wifi = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        runCatching {
            val input = context.getSystemService(InputManager::class.java)
            controllers = input?.inputDeviceIds?.toList()?.mapNotNull { id -> input.getInputDevice(id) }
                ?.count { device -> !device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD } ?: 0
        }
        runCatching {
            val battery = context.getSystemService(BatteryManager::class.java)
            fullIn = if (Build.VERSION.SDK_INT >= 28 && battery?.isCharging == true) battery.computeChargeTimeRemaining().takeIf { it > 0 } else null
        }
    }

    /** One of Android's volume steps up or down. */
    fun changeVolume(delta: Int) {
        runCatching {
            val audio = audio ?: return@runCatching
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (now + delta).coerceIn(0, max), 0)
        }
        refresh()
    }

    /** Brightness up or down by a twentieth, set by hand so automatic brightness does not take it back. */
    fun changeBrightness(delta: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val next = ((brightness + delta * .05f).coerceIn(.02f, 1f) * MAX_BRIGHTNESS).toInt()
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        }
        refresh()
        return true
    }

    /** Do Not Disturb on or off: on lets only priority interruptions through. False when it is not allowed yet. */
    fun toggleQuiet(): Boolean {
        val notifications = notifications ?: return false
        if (!notifications.isNotificationPolicyAccessGranted) return false
        runCatching {
            notifications.setInterruptionFilter(if (quiet) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        refresh()
        return true
    }

    fun allowBrightness() = open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
    fun allowQuiet() = open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    fun openWifi() = open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
    fun openBluetooth() = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /** Opens one of Android's pages; false when this device has none for it. */
    private fun open(intent: Intent): Boolean = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private companion object { const val MAX_BRIGHTNESS = 255f }
}
