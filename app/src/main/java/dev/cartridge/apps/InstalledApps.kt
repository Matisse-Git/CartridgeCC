package dev.cartridge.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toBitmap
import dev.cartridge.R

/**
 * Android's own launcher service: the list, icons and start a home screen uses. It only ever names an
 * activity Android itself listed as a launcher entry, and Android checks that again before starting it.
 * The manifest's `<queries>` for MAIN + LAUNCHER is what makes other apps' entries visible (ADR 0021).
 */
class InstalledApps(context: Context) : AppSource {
    // The application's context: this object outlives no Activity, and must not hold one.
    private val context = context.applicationContext
    private val launcher get() = checkNotNull(context.getSystemService(LauncherApps::class.java)) { "This device has no launcher service" }
    private val user get() = Process.myUserHandle()

    override fun list(): List<AppEntry> {
        // For an installed app with no launcher activity Android may add a stand-in that only opens its App info
        // page. Those are not apps to start, so an entry is kept only if it is a real MAIN + LAUNCHER activity.
        val real = launcherActivities().mapTo(HashSet()) { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        return launcher.getActivityList(null, user)
            .filter { it.componentName in real && it.componentName.packageName != context.packageName }
            .map { AppEntry(it.componentName.packageName, it.componentName.className, label(it)) }
    }
    private fun launcherActivities(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= 33) context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.queryIntentActivities(intent, 0)
    }

    /** The entry's own name, then its app's. Never the package name: that is not something a player reads. */
    private fun label(info: LauncherActivityInfo): String =
        info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: runCatching { info.applicationInfo.loadLabel(context.packageManager).toString().trim() }.getOrNull().takeUnless { it.isNullOrEmpty() }
            ?: "App"

    override fun icon(entry: AppEntry, size: Int): Bitmap? {
        val info = launcher.getActivityList(entry.packageName, user).firstOrNull { it.componentName.className == entry.activity } ?: return null
        // A launcher icon is 48 dp: ask for the density at which that is at least the size being drawn.
        val density = (size * DisplayMetrics.DENSITY_DEFAULT / 48).coerceIn(DisplayMetrics.DENSITY_DEFAULT, DisplayMetrics.DENSITY_XXXHIGH)
        return info.getIcon(density)?.toBitmap(size, size, Bitmap.Config.ARGB_8888)
    }

    override fun launch(entry: AppEntry, animate: Boolean): Boolean = try {
        val component = ComponentName(entry.packageName, entry.activity)
        val service = launcher
        if (!service.isActivityEnabled(component, user)) false else {
            val options = if (animate) ActivityOptions.makeCustomAnimation(context, R.anim.app_open, R.anim.app_behind)
                else ActivityOptions.makeCustomAnimation(context, 0, 0)
            service.startMainActivity(component, user, null, options.toBundle())
            true
        }
    } catch (failure: Exception) {
        // Removed or turned off since it was listed, or refused by Android. The caller explains and re-reads.
        false
    }

    override fun watch(changed: (String?) -> Unit): AutoCloseable {
        val service = launcher
        val own = user
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { if (user == own) changed(packageName) }
            override fun onPackageAdded(packageName: String, user: UserHandle) { if (user == own) changed(packageName) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { if (user == own) changed(packageName) }
            override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { if (user == own) changed(null) }
            override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { if (user == own) changed(null) }
            override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) { if (user == own) changed(null) }
            override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) { if (user == own) changed(null) }
        }
        service.registerCallback(callback, Handler(Looper.getMainLooper()))
        return AutoCloseable { service.unregisterCallback(callback) }
    }
}
