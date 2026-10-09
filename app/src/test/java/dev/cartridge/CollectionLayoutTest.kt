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
                assertTrue(index >= start && index < start + layout.visibleCount)
                assertTrue(start >= 0)
                if (layout == CollectionLayout.Grid) assertEquals(0, start % 3)
            }
        }
    }
}
