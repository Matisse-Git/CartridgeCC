package dev.cartridge.apps

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer

/**
 * The Apps destination's own state: what is installed, what a search shows and which app is focused.
 * Android is reached only through [AppSource] and never on the main thread, apart from starting an app.
 * Nothing is stored: the list is read when Apps opens, when the console returns to the foreground and when
 * Android reports a change, so it cannot go stale. What each button does here is the console shell's business.
 */
class AppLibrary(
    private val source: AppSource,
    private val scope: CoroutineScope,
    focus: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Alphabetical as this locale reads it: case and accents only break ties. */
    private val order: Comparator<in String> = Collator.getInstance(),
) {
    enum class Phase { Unread, Ready, Failed }

    private var all = emptyList<AppEntry>()
    /** What the grid shows: every app, or those the search matches, in a stable alphabetical order. */
    var entries by mutableStateOf(emptyList<AppEntry>()); private set
    var phase by mutableStateOf(Phase.Unread); private set
    var query by mutableStateOf(""); private set
    var focusId by mutableStateOf(focus); private set
    /** Bumped when an icon may have changed, so a tile on screen asks for it again. */
    var iconEpoch by mutableIntStateOf(0); private set
    /** The grid reports how many columns it really drew, so up and down move by a row of what is on screen. */
    var columns = 6
        set(value) { field = value.coerceAtLeast(1) }
    val searching get() = query.isNotEmpty()
    /** There is something to look through, or a search to change. */
    val searchable get() = phase == Phase.Ready && (entries.isNotEmpty() || searching)
    val focusIndex get() = entries.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    val focused get() = entries.getOrNull(focusIndex)

    private var reading: Job? = null
    private var stale = false
    private var watch: AutoCloseable? = null
    private val iconLoading = io.limitedParallelism(4)
    /** Decoded icons by entry id, most recently shown last. Read and written on the main thread only. */
    private val icons = object : LinkedHashMap<String, Bitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > ICON_LIMIT
    }

    /** Apps is being shown: follow installs and removals from now on, and read what is installed. */
    fun open() {
        if (watch == null) watch = runCatching { source.watch(::changed) }.getOrNull()
        refresh()
    }
    /** Read what is installed now. Requests made while a read is running fold into one further read. */
    fun refresh() {
        stale = true
        if (reading?.isActive == true) return
        reading = scope.launch {
            while (stale) {
                stale = false
                try { publish(withContext(io) { sort(source.list(), order) }) }
                catch (failure: CancellationException) { throw failure }
                // A list already on screen stays: one failed read is not a reason to take the apps away.
                catch (failure: Exception) { if (all.isEmpty()) phase = Phase.Failed }
            }
        }
    }
    private fun publish(next: List<AppEntry>) {
        // Focus and search are read now, after the wait: the player may have moved or typed meanwhile.
        val index = focusIndex
        val kept = next.mapTo(HashSet()) { it.id }
        all = next; phase = Phase.Ready
        entries = visible()
        // An app that went away hands its focus to whatever now stands in its place, or to the last one.
        if (entries.none { it.id == focusId }) entries.getOrNull(index.coerceAtMost(entries.lastIndex))?.let { focusId = it.id }
        icons.keys.retainAll(kept)
    }
    private fun visible() = if (!searching) all else fold(query).let { wanted -> all.filter { wanted in fold(it.label) } }
    private fun changed(packageName: String?) {
        if (packageName == null) icons.clear() else icons.keys.removeAll { it.startsWith("$packageName/") }
        iconEpoch++
        refresh()
    }

    fun move(dx: Int, dy: Int) {
        if (entries.isNotEmpty()) focusId = entries[gridMove(focusIndex, entries.size, columns, dx, dy)].id
    }
    fun focus(entry: AppEntry) { if (entries.any { it.id == entry.id }) focusId = entry.id }
    /** An empty text shows every app again. The focused app stays focused while it is still shown. */
    fun search(text: String) {
        query = text.trim()
        entries = visible()
        if (entries.none { it.id == focusId }) entries.firstOrNull()?.let { focusId = it.id }
    }

    fun cachedIcon(entry: AppEntry): Bitmap? = icons[entry.id]
    suspend fun icon(entry: AppEntry, size: Int): Bitmap? {
        icons[entry.id]?.let { return it }
        val epoch = iconEpoch
        val loaded = withContext(iconLoading) { runCatching { source.icon(entry, size) }.getOrNull() }
        // An icon read across an update belongs to the old version; the tile asks again for the new one.
        if (loaded != null && epoch == iconEpoch) icons[entry.id] = loaded
        return loaded
    }

    /** False when Android would not start it. The list is then read again, since it has probably changed. */
    fun launch(entry: AppEntry, animate: Boolean): Boolean {
        focus(entry)
        val started = runCatching { source.launch(entry, animate) }.getOrDefault(false)
        if (!started) refresh()
        return started
    }

    fun close() { watch?.close(); watch = null; reading?.cancel() }

    companion object {
        /** More icons than any screenful needs, few enough to cost a few megabytes at most. */
        const val ICON_LIMIT = 256

        /** One entry per launcher activity, by name, with the id settling apps that share a name. */
        fun sort(found: List<AppEntry>, order: Comparator<in String> = Collator.getInstance()): List<AppEntry> =
            found.distinctBy { it.id }.sortedWith(Comparator<AppEntry> { a, b -> order.compare(a.label, b.label) }.thenBy { it.id })

        /** Lower case with accents removed, so the console's own keyboard can find "Pokémon". */
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.lowercase()

        /**
         * One step on a grid that fills row by row. Left and right stop at the row's ends, up and down keep
         * the column, and a step into the gap of a short last row lands on its last app. The game grid moves
         * the same way.
         */
        fun gridMove(index: Int, count: Int, columns: Int, dx: Int, dy: Int): Int {
            require(count > 0 && columns > 0 && index in 0 until count)
            val row = (index / columns + dy).coerceIn(0, (count - 1) / columns)
            val column = (index % columns + dx).coerceIn(0, columns - 1)
            return (row * columns + column).coerceAtMost(count - 1)
        }
    }
}
