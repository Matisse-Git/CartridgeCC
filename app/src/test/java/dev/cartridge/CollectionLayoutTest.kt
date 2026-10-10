package dev.cartridge

import dev.cartridge.domain.CollectionLayout
import org.junit.Assert.*
import org.junit.Test

class CollectionLayoutTest {
    @Test fun axesHaveDistinctMeaningAndGridEdgesNeverWrapRows() {
        assertEquals(5, CollectionLayout.Horizontal.move(4, 10, 1, 1))
        assertEquals(5, CollectionLayout.Vertical.move(4, 10, -1, 1))
        assertEquals(2, CollectionLayout.Grid.move(2, 10, 1, 0))
        assertEquals(3, CollectionLayout.Grid.move(3, 10, -1, 0))
        assertEquals(7, CollectionLayout.Grid.move(4, 10, 0, 1))
        assertEquals(9, CollectionLayout.Grid.move(8, 10, 0, 1))
        assertEquals(6, CollectionLayout.Grid.move(9, 10, 0, -1))
        assertEquals(4, CollectionLayout.Grid.move(0, 10, 1, 1))
    }
    @Test fun everyFocusedIdentityIsInsideABoundedAlignedWindow() {
        for (count in listOf(1, 2, 3, 4, 8, 9, 10, 1001)) for (layout in CollectionLayout.entries) {
            for (index in 0 until count) {
                val start = layout.windowStart(index, count)
                assertTrue(index >= start && index < start + layout.windowCount)
                assertTrue(start >= 0)
                assertTrue(layout.windowCount <= CollectionLayout.POOL)
                if (layout == CollectionLayout.Grid) assertEquals(0, start % 3)
            }
        }
    }
    private fun window(layout: CollectionLayout, index: Int, count: Int) =
        layout.windowStart(index, count).let { start -> start until (start + layout.windowCount).coerceAtMost(count) }
    private fun onScreen(layout: CollectionLayout, index: Int, count: Int): List<Int> = if (layout != CollectionLayout.Grid)
        (index - 1..index + 1).filter { it in 0 until count }
    else layout.gridCenter(index, count).let { centre -> (0 until count).filter { kotlin.math.abs(it / 3 - centre) <= 1f } }
    @Test fun whatIsOnScreenBeforeAndAfterOneStepIsAlreadyInTheWindow() {
        for (layout in CollectionLayout.entries) for (count in listOf(1, 2, 3, 4, 5, 6, 9, 10, 16, 20, 31, 1001)) for (index in 0 until count) {
            val window = window(layout, index, count)
            assertTrue("$layout $index/$count", window.toList().containsAll(onScreen(layout, index, count)))
            // The objects a single step slides in were given their labels before the step.
            for ((x, y) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val next = layout.move(index, count, x, y)
                assertTrue("$layout $index->$next/$count", window.toList().containsAll(onScreen(layout, next, count)))
            }
        }
    }
    @Test fun aStepRelabelsOnlyWhatEntersTheWindowAndNothingOnScreen() {
        for (layout in CollectionLayout.entries) for (count in listOf(4, 7, 16, 40, 1001)) {
            var index = 0
            var previous = window(layout, index, count)
            while (true) {
                val next = layout.move(index, count, 1, 1).takeIf { it != index } ?: break
                val window = window(layout, next, count)
                // One pooled object per game: no two games of a window share one.
                assertEquals(window.count(), window.map(CollectionLayout::slot).toSet().size)
                // A pooled object follows its shelf index alone, so only the games new to the window are bound.
                val entered = window.filter { it !in previous }
                assertTrue("$layout step to $next of $count binds ${entered.size}", entered.size <= layout.windowCount / 5 * (next - index))
                assertTrue("$layout step to $next of $count binds on screen", entered.none { it in onScreen(layout, next, count) })
                if (next > 200) break
                index = next; previous = window
            }
        }
    }
}
