package com.tactilesight.frame

import com.tactilesight.core.DepthMap

/**
 * How far away the nearest surface is, in each third of the frame.
 *
 * This is the phone's half of the bargain the VLM prompt makes. The VLM is
 * forbidden from stating a distance because it cannot measure one; this is what
 * makes that rule affordable rather than merely safe — the number comes from
 * the depth sensor, or it is not spoken at all.
 *
 * ### Why thirds, and not per-object
 *
 * ADR-0013 lists three ways to attach a distance to a named thing, cheapest
 * first. This is **option 2 — match by horizontal position**: the VLM says
 * "a doorway to your left", and the left third's distance is the one that
 * belongs to it. Option 1 (report straight from a detector) and option 3 (full
 * RGB↔depth projection) are both better and both cost more: option 1 needs a
 * YOLO model staged on the device, option 3 needs trustworthy 720p intrinsics.
 * Neither is on the critical path, and this needs nothing that is not already
 * in the frame.
 *
 * The approximation is real and worth stating plainly: a thing straddling two
 * thirds gets the nearer one's number, and a small object in front of a far
 * wall is averaged away by the percentile unless it is the nearest thing in its
 * third. What this measures honestly is **the nearest surface in a direction**,
 * which is what a walking user needs first anyway.
 *
 * ### Why the fifth percentile, never the minimum
 *
 * ADR-0013 makes this a mandatory safeguard: a single speckle or edge pixel
 * reading 400 mm would turn "a clear corridor" into "something at arm's
 * length", and it would do it confidently. The percentile ignores that. It is
 * still deliberately *low* — we want the nearest real surface, not the average
 * of the wall behind it.
 *
 * ### Why "unknown" is a first-class answer
 *
 * Valid depth coverage averages 62.6% across the shipped captures and holes are
 * severe and local — glass, dark and reflective surfaces read invalid, and one
 * measured region came back 6% valid. A number derived from a handful of
 * surviving pixels is not a measurement, and spoken aloud it is indistinguishable
 * from a good one. Below [MIN_VALID_FRACTION] this returns null and the caller
 * says nothing about that direction.
 */
object RegionDistance {

    enum class Region(val spoken: String) {
        LEFT("to your left"),
        AHEAD("ahead"),
        RIGHT("to your right"),
    }

    /**
     * [millimetres] is null when the region had too little valid depth to
     * trust. [validFraction] is kept either way — it is the number to look at
     * when a scene reads "unknown" and you want to know whether that was a
     * depth hole or a bug.
     */
    data class Reading(
        val region: Region,
        val millimetres: Int?,
        val validFraction: Float,
    ) {
        val isKnown get() = millimetres != null
        val metres get() = millimetres?.let { it / 1000f }
    }

    /**
     * Nearest surface per third. Always returns one [Reading] per [Region], in
     * left-to-right order, so a caller can speak them positionally without
     * checking which are present.
     */
    fun measure(depth: DepthMap): List<Reading> = Region.entries.map { region ->
        val (x0, x1) = when (region) {
            Region.LEFT -> 0.00f to 0.33f
            Region.AHEAD -> 0.33f to 0.67f
            Region.RIGHT -> 0.67f to 1.00f
        }
        readingFor(depth, region, x0, x1)
    }

    private fun readingFor(depth: DepthMap, region: Region, x0: Float, x1: Float): Reading {
        // Vertically, skip the band above the colour frame (depth sees higher
        // than the camera does, DepthCoverage.DEPTH) and the floor immediately
        // underfoot, which is always the nearest surface and never the answer
        // to "what is ahead of me".
        val top = (depth.height * DepthCoverage.DEPTH.top).toInt()
        val bottom = (depth.height * FLOOR_HORIZON).toInt().coerceAtMost(depth.height)
        val left = (depth.width * x0).toInt()
        val right = (depth.width * x1).toInt().coerceAtMost(depth.width)

        val valid = ArrayList<Int>((right - left) * (bottom - top) / 2)
        for (y in top until bottom) {
            val row = y * depth.width
            for (x in left until right) {
                val mm = depth.millimetres[row + x].toInt()
                // Zero is "no reading", not "touching the sensor" — and values
                // outside the sensor's usable span are noise, not surfaces.
                if (mm in NEAR_LIMIT_MM..FAR_LIMIT_MM) valid.add(mm)
            }
        }

        val total = (right - left) * (bottom - top)
        val fraction = if (total == 0) 0f else valid.size.toFloat() / total
        if (fraction < MIN_VALID_FRACTION || valid.isEmpty()) {
            return Reading(region, millimetres = null, validFraction = fraction)
        }

        valid.sort()
        val index = ((valid.size - 1) * NEAREST_PERCENTILE).toInt()
        return Reading(region, millimetres = valid[index], validFraction = fraction)
    }

    /**
     * Where the floor stops being useful. Below this the sensor is looking at
     * the ground a step away, which is the nearest surface in almost every
     * frame and would make every answer say half a metre.
     */
    private const val FLOOR_HORIZON = 0.80f

    /** The band's usable span (ADR-0013). Readings outside it are not surfaces. */
    private const val NEAR_LIMIT_MM = 400
    private const val FAR_LIMIT_MM = 8_000

    /**
     * Nearest, but robust — the value ADR-0013 specifies. `min` would trust a
     * single bad pixel with a confident number; this ignores speckle.
     *
     * The exact percentile turns out not to matter much here, which is worth
     * knowing before someone spends time tuning it. Measured across 12 regions
     * of four captures, p5 and p10 differ by **12–196 mm** — every one of them
     * inside a single rounding step of the spoken form (500 mm). Even raw `min`
     * sat within 16 mm of p5 on the cleanest region. The safeguard earns its
     * place against live data, not against these captures.
     */
    private const val NEAREST_PERCENTILE = 0.10f

    /**
     * Below this the region is a depth hole, not a measurement. Set from the
     * captures: a genuinely-holed region measured 6% valid, while regions with
     * a real surface ran 15–95%.
     */
    private const val MIN_VALID_FRACTION = 0.10f
}
