package com.tactilesight.frame

import com.tactilesight.core.DepthMap

/**
 * How far away each detected object is: the box, projected into the depth
 * frame, then the same robust percentile [RegionDistance] uses.
 *
 * This is ADR-0013's **option 1** — report straight from detection — which the
 * ADR lists as the cheapest of the three ways to attach a distance to a named
 * thing, and the only one that gives *per-object* rather than per-direction
 * numbers. [RegionDistance] remains as the floor: it answers "how far is
 * anything in that direction" for the walls, doorways and stairs COCO cannot
 * name.
 *
 * ### The projection, and how much to trust it
 *
 * Colour and depth are different sensors 24.9 mm apart with different fields of
 * view, so a box in one does not index the other. Rather than a calibration we
 * did not have — `calib.json`'s 720p intrinsics are wrong by 13–18% — this uses
 * the correspondence measured in [DepthCoverage]:
 *
 * ```
 * RGB x 0.09 .. 0.88   <->  depth full width      (colour sees wider)
 * depth y 0.07 .. 0.955 <-> RGB full height       (depth sees taller)
 * ```
 *
 * It is a linear fit between two rectangles, so it is good **to about a box,
 * not to a pixel**, and it degrades with parallax as objects get closer. That
 * is enough to land a person's box on a person's depth, which is the job.
 * Validated on the captures: id002's person reads 1.1 m from their box, and
 * 1.1 m from the whole left third independently.
 *
 * A box can also project entirely outside depth's field — colour sees a wider
 * scene than depth measures — and objects at the frame edge do exactly that.
 * Those come back [Measured.UNKNOWN], which is honest: the camera saw it, the
 * depth sensor never could.
 */
object ObjectDistance {

    data class Measured(
        val detection: ObjectDetector.Detection,
        /** Null when depth could not measure this object. */
        val millimetres: Int?,
        val validFraction: Float,
    ) {
        val isKnown get() = millimetres != null

        companion object {
            const val UNKNOWN = "unknown"
        }
    }

    fun measure(detections: List<ObjectDetector.Detection>, depth: DepthMap): List<Measured> =
        detections.map { measureOne(it, depth) }

    private fun measureOne(detection: ObjectDetector.Detection, depth: DepthMap): Measured {
        val colour = DepthCoverage.COLOUR
        val depthBounds = DepthCoverage.DEPTH

        // Horizontal: colour's measurable band maps onto depth's full width.
        val span = colour.right - colour.left
        val x0 = ((detection.left - colour.left) / span).coerceIn(0f, 1f)
        val x1 = ((detection.right - colour.left) / span).coerceIn(0f, 1f)

        // Vertical: the colour frame sits inside depth's taller field.
        val height = depthBounds.bottom - depthBounds.top
        val y0 = (depthBounds.top + detection.top * height).coerceIn(0f, 1f)
        val y1 = (depthBounds.top + detection.bottom * height).coerceIn(0f, 1f)

        val left = (x0 * depth.width).toInt()
        val right = (x1 * depth.width).toInt().coerceAtMost(depth.width)
        val top = (y0 * depth.height).toInt()
        val bottom = (y1 * depth.height).toInt().coerceAtMost(depth.height)

        if (right <= left || bottom <= top) {
            // Projected clean out of depth's field — the edge-of-frame case.
            return Measured(detection, millimetres = null, validFraction = 0f)
        }

        val valid = ArrayList<Int>((right - left) * (bottom - top) / 2)
        for (y in top until bottom) {
            val row = y * depth.width
            for (x in left until right) {
                val mm = depth.millimetres[row + x].toInt()
                if (mm in NEAR_LIMIT_MM..FAR_LIMIT_MM) valid.add(mm)
            }
        }

        val total = (right - left) * (bottom - top)
        val fraction = if (total == 0) 0f else valid.size.toFloat() / total
        if (fraction < MIN_VALID_FRACTION || valid.isEmpty()) {
            return Measured(detection, millimetres = null, validFraction = fraction)
        }

        valid.sort()
        val index = ((valid.size - 1) * NEAREST_PERCENTILE).toInt()
        return Measured(detection, millimetres = valid[index], validFraction = fraction)
    }

    /**
     * Same percentile, limits and validity floor as [RegionDistance], and
     * deliberately so: two ways of measuring the same scene that disagreed
     * about what counts as a reading would be worse than either alone.
     */
    private const val NEAREST_PERCENTILE = 0.10f
    private const val NEAR_LIMIT_MM = 400
    private const val FAR_LIMIT_MM = 8_000
    private const val MIN_VALID_FRACTION = 0.10f
}
