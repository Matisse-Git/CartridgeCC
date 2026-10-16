package dev.cartridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import dev.cartridge.apps.AppEntry
import dev.cartridge.apps.AppSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Invented apps with drawn icons, so interface tests and the tour's screenshots never show what the owner
 * has installed. Tests of Android's own launcher service use the real source instead.
 */
internal class FixtureAppSource(names: List<String>, private val withoutIcon: Set<String> = emptySet()) : AppSource {
    val installed = CopyOnWriteArrayList(names.mapIndexed { index, name -> AppEntry("example.fixture$index", "Main", name) })
    @Volatile var refuse = false
    val launched = CopyOnWriteArrayList<String>()
    @Volatile var report: ((String?) -> Unit)? = null
    @Volatile var reads = 0
    override fun list(): List<AppEntry> { reads++; return installed.toList() }
    override fun icon(entry: AppEntry, size: Int): Bitmap? {
        if (entry.label in withoutIcon) return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val seed = entry.label.fold(7) { sum, character -> sum * 31 + character.code }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(bitmap).apply {
            val inset = size * .06f
            paint.color = Color.HSVToColor(floatArrayOf(Math.floorMod(seed, 360).toFloat(), .42f, .62f))
            drawRoundRect(RectF(inset, inset, size - inset, size - inset), size * .24f, size * .24f, paint)
            paint.color = Color.argb(235, 250, 246, 238)
            when (Math.floorMod(seed / 7, 3)) {
                0 -> drawCircle(size * .5f, size * .5f, size * .2f, paint)
                1 -> { drawRect(size * .3f, size * .36f, size * .7f, size * .44f, paint); drawRect(size * .3f, size * .56f, size * .58f, size * .64f, paint) }
                else -> drawRoundRect(RectF(size * .32f, size * .32f, size * .68f, size * .68f), size * .08f, size * .08f, paint)
            }
        }
        return bitmap
    }
    override fun launch(entry: AppEntry, animate: Boolean): Boolean {
        if (refuse || entry !in installed) return false
        launched += entry.id; return true
    }
    override fun watch(changed: (String?) -> Unit): AutoCloseable { report = changed; return AutoCloseable { report = null } }

    companion object {
        val TOUR = listOf("Field Notes", "Tide Clock", "Atlas", "Harbor Mail", "Lantern", "Paper Camera", "Signal Radio", "Meridian Maps", "Ledger", "Night Reader",
            "Compass", "Orchard Music", "Kiln", "Weather Glass", "Settings", "Files", "Gallery", "Quiet Calculator and Unit Converter", "Tally", "Workshop",
            "Almanac", "Postbox", "Sundial", "Tuner")
    }
}
