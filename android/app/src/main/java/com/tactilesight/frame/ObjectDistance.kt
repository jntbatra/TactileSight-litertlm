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
        /**
         * How far the depth inside the box departs from a flat plane, as a
         * fraction of the distance. Near zero means the "object" is a picture
         * of one — see [isSolid].
         */
        val relief: Float = 0f,
    ) {
        val isKnown get() = millimetres != null

        /**
         * Whether this is a real object rather than a picture of one.
         *
         * **A life-size poster defeats every other check.** It is detected
         * correctly, it sits at a real measured distance, and it is the right
         * apparent size — capture id014 is a printed advertisement of two
         * people that the device announced as "a person one and a half metres
         * in front of you". The distance was true; the noun was not.
         *
         * What gives it away is that a poster is a **plane** and a body is not.
         * Fit a plane to the depth inside the box and measure the residual:
         *
         * ```
         * id014 poster        33 mm, 9 mm    ->  2.6%, 0.9% of distance
         * real person 1.1 m   556 mm         ->  52%
         * real person 1.0 m   966 mm         ->  94%
         * real people 2.3 m   152 mm         ->  6-7%
         * ```
         *
         * A flat wall reads to about 58 mm of sensor noise (measured on
         * id011's back wall), so anything under a few percent is a surface,
         * not a person standing in front of one.
         *
         * This only suppresses the **name**. The distance is still real - there
         * genuinely is something 1.3 m away - so the answer falls back to "one
         * and a half metres ahead", which is true of a poster, a wall or a
         * door alike.
         */
        val isSolid get() = relief >= MIN_RELIEF
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
        val distance = valid[index]
        return Measured(
            detection = detection,
            millimetres = distance,
            validFraction = fraction,
            relief = reliefOf(depth, left, top, right, bottom, distance),
        )
    }

    /**
     * Residual of a least-squares plane fit over the box, as a fraction of the
     * distance — how much the surface departs from being flat.
     *
     * The plane, not the raw spread, is what matters: a wall viewed at an angle
     * has a large depth *range* while being perfectly flat, and would look like
     * a body to a simpler test. Fitting `z = ax + by + c` removes that tilt and
     * leaves only genuine relief.
     */
    private fun reliefOf(
        depth: DepthMap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        distance: Int,
    ): Float {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxz = 0.0; var syz = 0.0

        for (y in top until bottom) {
            val row = y * depth.width
            for (x in left until right) {
                val mm = depth.millimetres[row + x].toInt()
                if (mm !in NEAR_LIMIT_MM..FAR_LIMIT_MM) continue
                val fx = x.toDouble(); val fy = y.toDouble(); val fz = mm.toDouble()
                n++; sx += fx; sy += fy; sz += fz
                sxx += fx * fx; sxy += fx * fy; syy += fy * fy
                sxz += fx * fz; syz += fy * fz
            }
        }
        if (n < MIN_PLANE_POINTS) return 0f

        // Normal equations for z = a*x + b*y + c. Solved by hand rather than
        // with a matrix library: it is 3x3 and this runs on every press.
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx, sxz),
            doubleArrayOf(sxy, syy, sy, syz),
            doubleArrayOf(sx, sy, n.toDouble(), sz),
        )
        for (col in 0 until 3) {
            val pivotRow = (col until 3).maxByOrNull { kotlin.math.abs(m[it][col]) } ?: return 0f
            if (kotlin.math.abs(m[pivotRow][col]) < 1e-9) return 0f
            val tmp = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (k in col until 4) m[r][k] -= factor * m[col][k]
            }
        }
        val a = m[0][3] / m[0][0]
        val b = m[1][3] / m[1][1]
        val c = m[2][3] / m[2][2]

        var sumSquares = 0.0
        for (y in top until bottom) {
            val row = y * depth.width
            for (x in left until right) {
                val mm = depth.millimetres[row + x].toInt()
                if (mm !in NEAR_LIMIT_MM..FAR_LIMIT_MM) continue
                val predicted = a * x + b * y + c
                val e = mm - predicted
                sumSquares += e * e
            }
        }
        val residual = kotlin.math.sqrt(sumSquares / n)
        return (residual / distance).toFloat()
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

    /**
     * Below this fraction of the distance, the box is a flat surface and the
     * detection is a picture rather than the thing itself.
     *
     * 5% sits well clear of both sides of the measured gap: the id014 poster
     * reads 0.9-2.6%, while real people read 6-94%. Set from those numbers
     * rather than chosen - and deliberately erring toward silence, because
     * losing the word "person" costs a little information while inventing one
     * costs the user's trust.
     */
    private const val MIN_RELIEF = 0.05f

    /** Too few points and the plane fit is describing noise. */
    private const val MIN_PLANE_POINTS = 200
}
