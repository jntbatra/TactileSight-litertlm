package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture-of-a-thing problem.
 *
 * A life-size poster is detected correctly, sits at a real measured distance
 * and is the right apparent size for what it depicts — every check except
 * flatness passes it. Capture id014 is a printed advertisement of two people
 * that the device announced as "a person one and a half metres in front of
 * you": the distance true, the noun false, and a blind user with no way to
 * catch it.
 */
class ObjectDistanceTest {

    private val wholeFrame = ObjectDetector.Detection(
        label = "person", confidence = 0.9f,
        left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.9f,
    )

    /** A perfectly flat surface at [millimetres] — a wall, or a poster on one. */
    private fun flatWall(millimetres: Int) = DepthMap(
        width = 640, height = 480,
        millimetres = ShortArray(640 * 480) { millimetres.toShort() },
    )

    /** A tilted but still flat surface — a wall seen at an angle. */
    private fun tiltedWall(nearMm: Int, farMm: Int) = DepthMap(
        width = 640, height = 480,
        millimetres = ShortArray(640 * 480) { i ->
            (nearMm + (farMm - nearMm) * (i % 640) / 640).toShort()
        },
    )

    /** Depth that varies within the box the way a body does. */
    private fun bodyShaped(baseMm: Int) = DepthMap(
        width = 640, height = 480,
        millimetres = ShortArray(640 * 480) { i ->
            val x = i % 640
            val fromCentre = kotlin.math.abs(x - 320) / 320f
            (baseMm + (fromCentre * baseMm * 0.6f)).toInt().toShort()
        },
    )

    @Test
    fun `a poster is measured but not called a person`() {
        val measured = ObjectDistance.measure(listOf(wholeFrame), flatWall(1_300)).single()

        // The distance is real - there IS something 1.3 m away.
        assertTrue("should still measure the surface", measured.isKnown)
        // But nothing about it says "person".
        assertFalse("a flat plane was called solid", measured.isSolid)
    }

    @Test
    fun `a wall seen at an angle is still flat`() {
        // The reason this fits a plane rather than measuring raw spread: a
        // tilted wall has a large depth RANGE while being perfectly flat, and
        // a spread-based test would call it a body.
        val measured = ObjectDistance.measure(listOf(wholeFrame), tiltedWall(1_200, 2_400)).single()
        assertFalse("a tilted wall was called solid", measured.isSolid)
    }

    @Test
    fun `something with real relief is called solid`() {
        val measured = ObjectDistance.measure(listOf(wholeFrame), bodyShaped(1_300)).single()
        assertTrue("a body-shaped surface was rejected", measured.isSolid)
    }

    @Test
    fun `an object depth cannot reach is never called solid`() {
        // No measurement means no claim of any kind - not a distance, and
        // certainly not a name.
        val measured = ObjectDistance.measure(listOf(wholeFrame), flatWall(0)).single()
        assertFalse(measured.isKnown)
        assertFalse(measured.isSolid)
    }
}
