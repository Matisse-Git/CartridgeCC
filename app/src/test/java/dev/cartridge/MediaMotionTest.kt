package dev.cartridge

import dev.cartridge.domain.FocusSpring
import dev.cartridge.domain.MediaMotion
import org.junit.Assert.*
import org.junit.Test

class MediaMotionTest {
    @Test fun insertionLiftsAndAlignsBeforeDescendingIntoTheSlot() {
        val rest = MediaMotion.pose(0f)
        assertEquals(0f, rest.height, 0f)
        assertEquals(0f, rest.slot, 0f)
        val lifted = MediaMotion.pose(.3f)
        assertEquals(.18f, lifted.height, .001f)
        assertTrue(lifted.alignment > 0f)
        val aligned = MediaMotion.pose(.46f)
        assertEquals(1f, aligned.alignment, .001f)
        val inserted = MediaMotion.pose(1f)
        assertEquals(-1.32f, inserted.height, .001f)
        var last = MediaMotion.pose(.46f).height
        for (i in 47..100) {
            val pose = MediaMotion.pose(i/100f)
            assertTrue(pose.height <= last)
            assertEquals(1f, pose.alignment, .001f)
            last = pose.height
        }
        assertEquals(rest, MediaMotion.pose(-1f))
        assertEquals(inserted, MediaMotion.pose(2f))
    }
    @Test fun reducedMotionSnapClearsResidualSpringVelocity() {
        val spring = FocusSpring()
        repeat(3) { spring.step(2f, 1f/60) }
        assertEquals(1f, spring.snapTo(1f), 0f)
        repeat(10) { assertEquals(1f, spring.step(1f, 1f/60), 0f) }
        repeat(120) { spring.step(0f, 1f/60) }
        assertEquals(0f, spring.position, .001f)
    }
}
