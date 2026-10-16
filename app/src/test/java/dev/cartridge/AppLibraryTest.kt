package dev.cartridge

import android.graphics.Bitmap
import dev.cartridge.apps.AppEntry
import dev.cartridge.apps.AppLibrary
import dev.cartridge.apps.AppSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** A launcher service that holds whatever the test installs. Names and packages are invented. */
internal class FixtureApps(vararg labels: String) : AppSource {
    val installed = labels.mapIndexed { index, label -> AppEntry("example.app$index", "Main", label) }.toMutableList()
    var failure: Exception? = null
    var reads = 0
    var refuse = false
    val launched = mutableListOf<Pair<String, Boolean>>()
    var report: ((String?) -> Unit)? = null
    var watching = false
    val iconsRead = mutableListOf<String>()
    override fun list(): List<AppEntry> { reads++; failure?.let { throw it }; return installed.toList() }
    override fun icon(entry: AppEntry, size: Int): Bitmap? { iconsRead += entry.id; return null }
    override fun launch(entry: AppEntry, animate: Boolean): Boolean {
        if (refuse || entry !in installed) return false
        launched += entry.id to animate; return true
    }
    override fun watch(changed: (String?) -> Unit): AutoCloseable { report = changed; watching = true; return AutoCloseable { watching = false; report = null } }
    fun remove(label: String) = installed.removeAll { it.label == label }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppLibraryTest {
    private fun TestScope.library(source: AppSource, focus: String? = null) =
        AppLibrary(source, backgroundScope, focus, StandardTestDispatcher(testScheduler), Collator.getInstance(Locale.ENGLISH))
    private val AppLibrary.names get() = entries.map { it.label }

    @Test fun appsAreListedOnceEachInTheOrderALocaleReadsThem() = runTest {
        val source = FixtureApps("Zeta Board", "apple Notes", "Émile Reader", "Banana Tools")
        // The same launcher activity reported twice is one app; two activities of one package are two.
        source.installed += source.installed[0]
        source.installed += AppEntry("example.app0", "Second", "Zeta Board")
        // Two different apps may share a name: their order must still be the same on every read.
        source.installed += AppEntry("example.a", "Main", "Clock"); source.installed += AppEntry("example.b", "Main", "Clock")
        val apps = library(source)
        assertEquals(AppLibrary.Phase.Unread, apps.phase); assertTrue(apps.entries.isEmpty())
        apps.open(); runCurrent()
        assertEquals(AppLibrary.Phase.Ready, apps.phase)
        // Byte order would have put "Banana" first and "Émile" last.
        assertEquals(listOf("apple Notes", "Banana Tools", "Clock", "Clock", "Émile Reader", "Zeta Board", "Zeta Board"), apps.names)
        assertEquals(listOf("example.a/Main", "example.b/Main"), apps.entries.filter { it.label == "Clock" }.map { it.id })
        assertEquals(listOf("example.app0/Main", "example.app0/Second"), apps.entries.filter { it.label == "Zeta Board" }.map { it.id })
        assertEquals(apps.entries.size, apps.entries.map { it.id }.toSet().size)
        val first = apps.entries; source.installed.reverse(); apps.refresh(); runCurrent()
        assertEquals(first, apps.entries)
    }

    @Test fun theGridMovesByRowsAndColumnsAndNeverLeavesTheApps() {
        // Eight apps in three columns: rows of 3, 3 and 2.
        fun move(index: Int, dx: Int, dy: Int) = AppLibrary.gridMove(index, 8, 3, dx, dy)
        assertEquals(1, move(0, 1, 0)); assertEquals(0, move(0, -1, 0)); assertEquals(2, move(2, 1, 0)) // A row ends at its own edge.
        assertEquals(3, move(0, 0, 1)); assertEquals(0, move(0, 0, -1)); assertEquals(6, move(3, 0, 1))
        assertEquals(7, move(5, 0, 1)) // No app beneath: the last one takes the step.
        assertEquals(7, move(7, 1, 0)); assertEquals(7, move(7, 0, 1)); assertEquals(4, move(7, 0, -1))
        assertEquals(0, AppLibrary.gridMove(0, 1, 6, 1, 1))
        assertThrows(IllegalArgumentException::class.java) { AppLibrary.gridMove(0, 0, 6, 1, 0) }
    }

    @Test fun focusIsRememberedRestoredAndHandedToANeighbourWhenAnAppGoesAway() = runTest {
        val source = FixtureApps("Atlas", "Beacon", "Compass", "Dial", "Ember")
        val remembered = source.installed.first { it.label == "Dial" }.id
        val apps = library(source, remembered)
        apps.open(); runCurrent()
        assertEquals("Dial", apps.focused!!.label); assertEquals(3, apps.focusIndex) // Where it was left, not the first app.
        apps.columns = 2; apps.move(0, -1); assertEquals("Beacon", apps.focused!!.label)
        apps.move(1, 0); apps.move(1, 0); assertEquals("Beacon", apps.focused!!.label)
        apps.move(-1, 0); apps.move(0, 9); assertEquals("Ember", apps.focused!!.label)
        // The last app is removed: the one before it takes the focus.
        source.remove("Ember"); source.report!!("example.app4"); runCurrent()
        assertEquals(listOf("Atlas", "Beacon", "Compass", "Dial"), apps.names); assertEquals("Dial", apps.focused!!.label)
        // One from the middle is removed: what now stands in its place takes it.
        apps.move(0, -1); assertEquals("Beacon", apps.focused!!.label)
        source.remove("Beacon"); source.report!!("example.app1"); runCurrent()
        assertEquals("Compass", apps.focused!!.label); assertEquals(1, apps.focusIndex)
        // A newly installed app appears without moving the focus off the app it was on.
        source.installed += AppEntry("example.new", "Main", "Abacus"); source.report!!("example.new"); runCurrent()
        assertEquals(listOf("Abacus", "Atlas", "Compass", "Dial"), apps.names); assertEquals("Compass", apps.focused!!.label)
        // Everything uninstalled: nothing is focused and nothing throws.
        source.installed.clear(); source.report!!(null); runCurrent()
        assertTrue(apps.entries.isEmpty()); assertNull(apps.focused); apps.move(1, 1); assertEquals(AppLibrary.Phase.Ready, apps.phase)
    }

    @Test fun searchFiltersWhatIsInstalledWithoutLosingTheFocusedApp() = runTest {
        val apps = library(FixtureApps("Pokémon Tools", "Pocket Radio", "Tide Clock", "Field Notes"))
        apps.open(); runCurrent()
        assertEquals("Field Notes", apps.focused!!.label)
        // The console's keyboard has no accents and no capitals to speak of; names are matched without either.
        apps.search("  POKEMON "); assertTrue(apps.searching); assertEquals("POKEMON", apps.query)
        assertEquals(listOf("Pokémon Tools"), apps.names); assertEquals("Pokémon Tools", apps.focused!!.label)
        apps.search("po"); assertEquals(listOf("Pocket Radio", "Pokémon Tools"), apps.names); assertEquals("Pokémon Tools", apps.focused!!.label)
        apps.search("nothing here"); assertTrue(apps.entries.isEmpty()); assertNull(apps.focused)
        // Clearing shows every app again, on the one that was found.
        apps.search(""); assertFalse(apps.searching); assertEquals(4, apps.entries.size); assertEquals("Pokémon Tools", apps.focused!!.label)
    }

    @Test fun aFailedReadIsReportedOnlyWhenThereIsNothingToShowAndIsRecoverable() = runTest {
        val source = FixtureApps("Atlas", "Beacon").apply { failure = SecurityException("Not allowed") }
        val apps = library(source)
        apps.open(); runCurrent()
        assertEquals(AppLibrary.Phase.Failed, apps.phase); assertTrue(apps.entries.isEmpty())
        source.failure = null; apps.refresh(); runCurrent()
        assertEquals(AppLibrary.Phase.Ready, apps.phase); assertEquals(listOf("Atlas", "Beacon"), apps.names)
        // With apps already on screen, one failed read takes nothing away.
        source.failure = IllegalStateException("Service restarting"); apps.refresh(); runCurrent()
        assertEquals(AppLibrary.Phase.Ready, apps.phase); assertEquals(listOf("Atlas", "Beacon"), apps.names)
    }

    @Test fun readsAreFoldedTogetherAndWatchingStopsWhenTheConsoleCloses() = runTest {
        val source = FixtureApps("Atlas")
        val apps = library(source)
        apps.open(); apps.open(); apps.refresh(); apps.refresh()
        assertTrue(source.watching); runCurrent()
        assertEquals(1, source.reads) // Four requests before the first answer are one read.
        apps.refresh(); runCurrent(); assertEquals(2, source.reads)
        // An update invalidates that app's icon only; tiles are told to ask again.
        apps.icon(apps.entries[0], 96); assertEquals(listOf("example.app0/Main"), source.iconsRead)
        val epoch = apps.iconEpoch
        source.report!!("example.app0"); runCurrent()
        assertEquals(epoch + 1, apps.iconEpoch); assertEquals(3, source.reads)
        apps.close(); assertFalse(source.watching)
    }

    @Test fun aRefusedLaunchReadsTheListAgainInsteadOfFailing() = runTest {
        val source = FixtureApps("Atlas", "Beacon")
        val apps = library(source)
        apps.open(); runCurrent()
        assertTrue(apps.launch(apps.entries[1], animate = true))
        assertEquals(listOf("example.app1/Main" to true), source.launched); assertEquals("Beacon", apps.focused!!.label)
        // Uninstalled while its icon was still on screen.
        val gone = apps.entries[1]; source.remove("Beacon")
        assertFalse(apps.launch(gone, animate = false)); runCurrent()
        assertEquals(listOf("Atlas"), apps.names); assertEquals("Atlas", apps.focused!!.label); assertEquals(1, source.launched.size)
        // A source that throws is a refusal, not a crash.
        val throwing = object : AppSource by source { override fun launch(entry: AppEntry, animate: Boolean): Boolean = throw SecurityException("Not exported") }
        assertFalse(library(throwing).launch(apps.entries[0], animate = true))
    }
}
