package dev.cartridge.apps

import android.graphics.Bitmap

/** One launcher entry of an installed Android app. An app with two launcher activities has two entries. */
data class AppEntry(val packageName: String, val activity: String, val label: String) {
    /** Stable for as long as the app is installed: focus, icons and tests address an app by this, never by position. */
    val id get() = "$packageName/$activity"
}

/**
 * What the console asks of Android's launcher service. [list] and [icon] may block and are never called on
 * the main thread. Nothing here knows about games: apps are not library entries and have no console.
 */
interface AppSource {
    /** Every launcher activity this user can start, Cartridge itself excluded. Order is not promised. */
    fun list(): List<AppEntry>
    /** The app's own launcher icon, [size] pixels square. Null when Android has none to give. */
    fun icon(entry: AppEntry, size: Int): Bitmap?
    /** Start the entry as a home screen would. False when Android refused or the app is no longer there. */
    fun launch(entry: AppEntry, animate: Boolean): Boolean
    /**
     * Reports, on the main thread, a package that was installed, updated, removed, turned on or turned off;
     * null when several changed at once. Closing the result stops the reports.
     */
    fun watch(changed: (String?) -> Unit): AutoCloseable
}
