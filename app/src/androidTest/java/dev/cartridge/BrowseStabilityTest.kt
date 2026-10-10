package dev.cartridge

import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.library.Shelf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browsing a shelf longer than what fits on screen. Every object on stage carries its own game's label on
 * every presented frame, and a step uploads a label only for the object that entered the window.
 * Original titles only; labels are the typeset ones, so nothing is fetched or imported.
 */
class BrowseStabilityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, check: (DebugCollectionActivity) -> Boolean) =
        ui.waitUntil(30_000) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    private fun key(scenario: ActivityScenario<DebugCollectionActivity>, code: Int) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(60)
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }; ui.waitForIdle()
    }
    /** At rest: the spring has arrived and every staged object has its label on the GPU. */
    private fun settle(scenario: ActivityScenario<DebugCollectionActivity>) = await(scenario) {
        val surface = it.collectionSurface ?: return@await false
        val state = it.coordinator.state.value
        kotlin.math.abs(surface.focusPosition - state.selection.index) < .001f && surface.stagedUnbound == 0
    }

    @Test fun steppingAlongAMixedShelfNeverShowsAnObjectWithoutItsOwnLabel() {
        val name = "m1-test-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "$name.db").build()
        val systems = listOf(GameSystem.GBA, GameSystem.GB, GameSystem.PS1, GameSystem.GBC)
        runBlocking {
            val repository = RoomCollectionRepository(context, database); repository.initialize()
            repeat(GAMES) { repository.add(LibraryGame((it + 1).toString(16).padStart(64, '0'), "Original browse fixture $it", "content://fixture", system = systems[it % systems.size])) }
            repository.select(0)
        }
        database.close()
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                // One shelf with cartridges of both shapes and discs side by side.
                scenario.onActivity { it.coordinator.chooseShelf(Shelf.All) }
                await(scenario) { it.coordinator.state.value.shelf == Shelf.All && it.coordinator.state.value.selection.count == GAMES }
                for (layout in CollectionLayout.entries) {
                    scenario.onActivity { it.coordinator.settings(layout = layout); it.coordinator.navigate(-99) }
                    ui.waitForIdle(); settle(scenario)
                    val worst = AtomicInteger(); val frames = AtomicInteger()
                    var before = 0
                    scenario.onActivity { activity ->
                        val surface = activity.collectionSurface!!
                        assertEquals(CollectionLayout.POOL, surface.poolCapacity)
                        before = surface.labelUploads
                        surface.frameObserver = { _, _, presented -> if (presented) { frames.incrementAndGet(); worst.accumulateAndGet(surface.unlabelledOnStage) { a, b -> maxOf(a, b) } } }
                    }
                    val forward = if (layout == CollectionLayout.Horizontal) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
                    val back = if (layout == CollectionLayout.Horizontal) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
                    val steps = if (layout == CollectionLayout.Grid) 4 else 9
                    repeat(steps) { key(scenario, forward) }
                    settle(scenario)
                    var far = 0; var afterForward = 0
                    scenario.onActivity { far = it.coordinator.state.value.selection.index; afterForward = it.collectionSurface!!.labelUploads }
                    assertEquals(if (layout == CollectionLayout.Grid) 12 else 9, far)
                    // Exactly the games that entered the window were given a texture; nothing already on screen was touched.
                    val entered = layout.windowStart(far, GAMES) - layout.windowStart(0, GAMES)
                    assertTrue(entered in 1..steps * 3)
                    assertEquals("$layout forward", entered, afterForward - before)
                    repeat(steps) { key(scenario, back) }
                    settle(scenario)
                    scenario.onActivity { activity ->
                        assertEquals(0, activity.coordinator.state.value.selection.index)
                        assertEquals("$layout back", afterForward - before, activity.collectionSurface!!.labelUploads - afterForward)
                        activity.collectionSurface!!.frameObserver = null
                    }
                    assertTrue("$layout presented frames", frames.get() > 30)
                    assertEquals("$layout: an object was on stage without its own label", 0, worst.get())
                    Log.i("CartridgeBrowse", "$layout: ${frames.get()} frames, ${afterForward - before} label uploads for ${steps} steps each way, none on stage unlabelled")
                }
            }
        } finally { DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db") }
    }
    private companion object { const val GAMES = 30 }
}
