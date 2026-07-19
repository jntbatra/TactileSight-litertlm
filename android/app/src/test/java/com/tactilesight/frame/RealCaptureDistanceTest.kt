package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The distance path over **real band captures**, not synthetic depth.
 *
 * The unit tests next door prove the rules hold on data shaped by hand. This
 * proves the rules survive contact with the sensor: real holes, real speckle,
 * real glass and dark surfaces. It reads the same `.npy` files that ship in the
 * APK and runs the same [RegionDistance] and [DistanceSpeech] the phone runs.
 *
 * Ground truth here comes from opening the matching `rgb.jpg` and looking at
 * it. Where a number is asserted, a human checked the photo.
 */
class RealCaptureDistanceTest {

    private val captures = File("src/main/assets/captures")

    private fun depthOf(scene: String): DepthMap {
        val file = File(captures, "$scene/depth_raw.npy")
        val bytes = file.readBytes()
        // uint16 little-endian, 640x480, after a v1 npy header.
        val headerLength = 10 + ((bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8))
        val pixels = ShortArray(640 * 480)
        for (i in pixels.indices) {
            val at = headerLength + i * 2
            pixels[i] = ((bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)).toShort()
        }
        return DepthMap(640, 480, pixels)
    }

    private fun spokenFor(scene: String): String =
        DistanceSpeech.clauseFor(RegionDistance.measure(depthOf(scene)))

    @Test
    fun `every shipped capture produces either a measurement or honest silence`() {
        assumeTrue("captures not present", captures.isDirectory)
        val scenes = captures.listFiles()?.filter { File(it, "depth_raw.npy").exists() }.orEmpty()
        assumeTrue("no captures with depth", scenes.isNotEmpty())

        scenes.forEach { scene ->
            val readings = RegionDistance.measure(depthOf(scene.name))
            val clause = DistanceSpeech.clauseFor(readings)

            // The failure this guards: a number derived from a handful of
            // surviving pixels, spoken with the same confidence as a good one.
            readings.filter { it.isKnown }.forEach {
                check(it.millimetres!! in 400..8_000) {
                    "${scene.name} ${it.region} spoke ${it.millimetres} mm, outside the sensor's range"
                }
                check(it.validFraction >= 0.10f) {
                    "${scene.name} ${it.region} spoke a distance from ${it.validFraction} valid"
                }
            }
            check(!clause.contains("null")) { "${scene.name}: $clause" }
            println("${scene.name.padEnd(16)} ${clause.ifBlank { "(silent — no usable depth)" }}")
        }
    }

    @Test
    fun `the dead-end alcove is heard as a corridor, not an empty room`() {
        assumeTrue("captures not present", captures.isDirectory)
        // id011: walls left and right, back wall ahead, nothing else in frame.
        // The VLM alone calls this "an empty room with white walls", which
        // tells a blind user nothing. This is the scene that justifies the
        // whole feature, so it is pinned.
        val readings = RegionDistance.measure(depthOf("scene_1_id011"))
        val byRegion = readings.associateBy { it.region }

        val right = byRegion[RegionDistance.Region.RIGHT]?.millimetres
        val ahead = byRegion[RegionDistance.Region.AHEAD]?.millimetres
        checkNotNull(right) { "the right wall should be measurable" }
        checkNotNull(ahead) { "the end of the corridor should be measurable" }

        // Looking at the photo: the right wall is closer than the far end.
        check(right < ahead) { "right wall $right mm should be nearer than the end $ahead mm" }
        println("id011 -> ${spokenFor("scene_1_id011")}")
    }

    @Test
    fun `a person close on one side is heard on that side`() {
        assumeTrue("captures not present", captures.isDirectory)
        // id002: a person walking, close, on the LEFT of frame; a plant on the
        // right; a washroom sign further off. If direction were wrong here the
        // device would steer someone into the thing it is warning them about.
        val left = RegionDistance.measure(depthOf("scene_1_id002"))
            .first { it.region == RegionDistance.Region.LEFT }
        checkNotNull(left.millimetres) { "the person on the left should be measurable" }
        check(left.millimetres!! < 2_000) {
            "someone an arm's length away read as ${left.millimetres} mm"
        }
        println("id002 -> ${spokenFor("scene_1_id002")}")
    }
}
