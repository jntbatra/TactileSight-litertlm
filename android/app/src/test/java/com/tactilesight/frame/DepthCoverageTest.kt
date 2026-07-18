package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCoverageTest {

    @Test
    fun `crops the band's 1280x720 colour frame to the measurable region`() {
        val rect = DepthCoverage.cropRect(1280, 720)

        // ~3% off the left, ~7% off the right — the 24.9 mm baseline makes this
        // asymmetric, which is the part no document mentions.
        assertEquals(43, rect.left)
        assertEquals(1187, rect.right)
        assertEquals(18, rect.top)
        assertEquals(687, rect.bottom)
    }

    @Test
    fun `the crop is asymmetric horizontally, not vertically centred`() {
        val rect = DepthCoverage.cropRect(1280, 720)

        val lostLeft = rect.left
        val lostRight = 1280 - rect.right

        assertTrue(
            "expected more loss on the right ($lostRight) than the left ($lostLeft) " +
                "— that asymmetry is the sensor baseline",
            lostRight > lostLeft,
        )
    }

    @Test
    fun `keeps most of the frame — this is a trim, not a zoom`() {
        val rect = DepthCoverage.cropRect(1280, 720)
        val kept = (rect.width.toLong() * rect.height) / (1280.0 * 720.0)

        assertTrue("kept only ${(kept * 100).toInt()}% of the frame", kept > 0.80)
    }

    @Test
    fun `never produces an empty or out-of-bounds rect`() {
        for ((w, h) in listOf(1280 to 720, 640 to 480, 320 to 240, 1 to 1, 3 to 2)) {
            val rect = DepthCoverage.cropRect(w, h)
            assertTrue("$w x $h -> width ${rect.width}", rect.width >= 1)
            assertTrue("$w x $h -> height ${rect.height}", rect.height >= 1)
            assertTrue("$w x $h -> right ${rect.right}", rect.right <= w)
            assertTrue("$w x $h -> bottom ${rect.bottom}", rect.bottom <= h)
            assertTrue("$w x $h -> left ${rect.left}", rect.left >= 0)
            assertTrue("$w x $h -> top ${rect.top}", rect.top >= 0)
        }
    }
}
