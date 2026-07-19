package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety properties of measured distance. Every test here corresponds to a
 * way the device could confidently tell a blind user something untrue.
 */
class RegionDistanceTest {

    /** 640x480, every pixel the same distance. */
    private fun flat(millimetres: Int) = DepthMap(
        width = 640,
        height = 480,
        millimetres = ShortArray(640 * 480) { millimetres.toShort() },
    )

    /** 640x480 where a column range is [near] and the rest [far]. */
    private fun withNearColumns(near: Int, far: Int, from: Int, until: Int) = DepthMap(
        width = 640,
        height = 480,
        millimetres = ShortArray(640 * 480) { i ->
            if ((i % 640) in from until until) near.toShort() else far.toShort()
        },
    )

    @Test
    fun `reports one reading per region, left to right`() {
        val readings = RegionDistance.measure(flat(2_000))
        assertEquals(RegionDistance.Region.entries, readings.map { it.region })
    }

    @Test
    fun `a flat wall reads the same distance in every direction`() {
        RegionDistance.measure(flat(2_500)).forEach {
            assertEquals(it.region.name, 2_500, it.millimetres)
        }
    }

    @Test
    fun `something close on one side does not move the other side's number`() {
        // A person at 0.8 m across the left third only. If regions bled into
        // each other, the user would be told to avoid something that is not in
        // their way — and would learn to distrust direction entirely.
        val readings = RegionDistance.measure(
            withNearColumns(near = 800, far = 4_000, from = 0, until = 200),
        )
        val byRegion = readings.associateBy { it.region }
        assertEquals(800, byRegion[RegionDistance.Region.LEFT]?.millimetres)
        assertEquals(4_000, byRegion[RegionDistance.Region.AHEAD]?.millimetres)
        assertEquals(4_000, byRegion[RegionDistance.Region.RIGHT]?.millimetres)
    }

    @Test
    fun `zero means no reading, never a distance of zero`() {
        // The single most dangerous confusion available here: 0 mm is "the
        // sensor could not see", and spoken as a measurement it becomes
        // "something is touching you".
        assertTrue(RegionDistance.measure(flat(0)).none { it.isKnown })
    }

    @Test
    fun `a region that is mostly holes reports unknown rather than a number`() {
        // 5% of pixels valid. Enough to compute something; nowhere near enough
        // to say it out loud. Measured on real captures: a genuinely holed
        // region came back 6% valid while real surfaces ran 15-95%.
        val sparse = DepthMap(
            width = 640,
            height = 480,
            millimetres = ShortArray(640 * 480) { i -> if (i % 20 == 0) 1_500 else 0 },
        )
        RegionDistance.measure(sparse).forEach {
            assertNull("${it.region} claimed ${it.millimetres} from ${it.validFraction}", it.millimetres)
        }
    }

    @Test
    fun `readings outside the sensor's usable span are not treated as surfaces`() {
        // 9 m is past the band's range; trusting it would report a wall where
        // the sensor was simply guessing.
        assertTrue(RegionDistance.measure(flat(9_500)).none { it.isKnown })
        assertTrue(RegionDistance.measure(flat(200)).none { it.isKnown })
    }

    @Test
    fun `a single stray near pixel cannot pull the distance in`() {
        // The reason ADR-0013 mandates a percentile over min(). One speckle at
        // 0.4 m in an otherwise 3 m room must not become "arm's length".
        val mm = ShortArray(640 * 480) { 3_000 }
        mm[240 * 640 + 320] = 400
        val ahead = RegionDistance.measure(DepthMap(640, 480, mm))
            .first { it.region == RegionDistance.Region.AHEAD }
        assertEquals(3_000, ahead.millimetres)
    }

    @Test
    fun `validFraction is reported even when the distance is unknown`() {
        // So a scene reading "unknown" can be diagnosed as a depth hole rather
        // than a bug, without reaching for a debugger.
        RegionDistance.measure(flat(0)).forEach {
            assertEquals(0f, it.validFraction, 0.001f)
        }
    }
}
