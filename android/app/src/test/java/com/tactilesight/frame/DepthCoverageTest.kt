package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCoverageTest {

    @Test
    fun `crops the band's 1280x720 colour frame to the measurable region`() {
        val rect = DepthCoverage.cropRect(1280, 720)

        // 9% off the left, 12% off the right — the 24.9 mm baseline makes this
        // asymmetric, which is the part no document mentions.
        assertEquals(115, rect.left)
        assertEquals(1126, rect.right)

        // Full height: depth's vertical FOV (45.0 deg) contains the colour
        // frame's (40.8 deg), so there is nothing above or below to lose.
        assertEquals(0, rect.top)
        assertEquals(720, rect.bottom)
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

        // 79%: all of it horizontal. The earlier bounds kept 85%, but ~5% of
        // that had no depth behind it at all — a crop that keeps more of the
        // frame is not a better crop if the extra is unmeasurable.
        assertTrue("kept only ${(kept * 100).toInt()}% of the frame", kept > 0.75)
    }

    @Test
    fun `does not crop the colour frame vertically`() {
        // Pinned deliberately. The first version of this class cropped top and
        // bottom on the strength of a documented claim that depth covers "only
        // the central vertical region". Measurement says the opposite: depth
        // overflows the colour frame vertically (8.1% above, 4.8% below), so a
        // vertical crop discards frame area that does have depth behind it.
        for ((w, h) in listOf(1280 to 720, 640 to 480, 1920 to 1080)) {
            val rect = DepthCoverage.cropRect(w, h)
            assertEquals("$w x $h", 0, rect.top)
            assertEquals("$w x $h", h, rect.bottom)
        }
    }

    @Test
    fun `crops the 640x480 depth frame vertically, not horizontally`() {
        val rect = DepthCoverage.cropRect(640, 480, DepthCoverage.DEPTH)

        // The mirror image of the colour crop. Depth sees 8.1% above the colour
        // frame and 4.8% below, so those bands show scene the colour camera
        // never captured — and a preview carousel that shows them is comparing
        // two different regions while claiming to compare one.
        assertEquals(33, rect.top)
        assertEquals(458, rect.bottom)

        // Horizontally depth is the narrower sensor, so there is nothing to trim.
        assertEquals(0, rect.left)
        assertEquals(640, rect.right)
    }

    @Test
    fun `the two crops trim opposite axes — they are one measurement, read twice`() {
        val colour = DepthCoverage.cropRect(1280, 720, DepthCoverage.COLOUR)
        val depth = DepthCoverage.cropRect(640, 480, DepthCoverage.DEPTH)

        // Colour loses width only; depth loses height only. If either ever
        // trims both axes, someone has applied one sensor's bounds to the
        // other's image — the exact unit-mismatch class of bug that produced
        // the wrong bounds this class replaced.
        assertTrue("colour should lose width", colour.width < 1280)
        assertEquals("colour should keep full height", 720, colour.height)
        assertEquals("depth should keep full width", 640, depth.width)
        assertTrue("depth should lose height", depth.height < 480)
    }

    @Test
    fun `both crops frame roughly the same shape`() {
        val colour = DepthCoverage.cropRect(1280, 720, DepthCoverage.COLOUR)
        val depth = DepthCoverage.cropRect(640, 480, DepthCoverage.DEPTH)

        val colourAspect = colour.width.toDouble() / colour.height
        val depthAspect = depth.width.toDouble() / depth.height

        // 1.40 vs 1.51 — about 7% apart, and deliberately not asserted equal.
        // Exact equality would be the wrong invariant: both bounds are taken
        // conservatively across a range of scenes rather than solved per frame,
        // and coverage is mildly depth-dependent (parallax grows as objects get
        // closer). What must hold is that they describe the same rectangle to
        // within the slack we chose; an order-of-magnitude drift means one side
        // was edited alone.
        assertTrue(
            "aspect ratios diverged: colour $colourAspect vs depth $depthAspect",
            kotlin.math.abs(colourAspect - depthAspect) / colourAspect < 0.12,
        )
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
