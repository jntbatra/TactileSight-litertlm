package com.tactilesight.core

/**
 * One capture instant from the band's Astra Pro Plus: colour and raw depth,
 * from the same moment (ADR-0009 — the band sends on demand, nothing streams).
 *
 * **Infrared was removed 2026-07-19.** ADR-0013 built the distance path on it,
 * because IR shares depth's exact 640×480 grid and a detection box therefore
 * indexes depth 1:1 with no calibration. The reasoning was sound; the data was
 * not. Measured across all 20 shipped captures, IR mean brightness is 3.2/255
 * and 0.36% of pixels exceed 40 — four captures have none at all. A detector
 * trained on daylight photographs finds nothing in a black frame, so the stream
 * that was geometrically perfect was practically useless.
 *
 * Detection now runs on colour and the box is mapped into depth through the
 * measured correspondence in DepthCoverage. If IR exposure is ever fixed on the
 * band, the 1:1 path is strictly better and worth restoring — that is why this
 * note explains the removal rather than simply deleting the field.
 */
data class Frame(
    /** JPEG, 1280×720. What the VLM looks at. */
    val rgbJpeg: ByteArray,
    /** Depth in millimetres, 640×480, row-major. 0 means no reading. */
    val depthMillimetres: DepthMap,
    val capturedAtMillis: Long,
    /** Where this came from, for the dev UI and logs. */
    val sourceId: String = "",
) {
    /**
     * False for a source with no depth sensor — the phone's own camera.
     *
     * Everything measured hangs off this. A frame without depth is described
     * but never measured, and the spoken sentence then carries **no** distance
     * rather than an estimated one: ADR-0013's rule is that every number the
     * user hears is a measurement, and a phone camera cannot produce one.
     */
    val hasDepth: Boolean get() = depthMillimetres.width > 0 && depthMillimetres.height > 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return capturedAtMillis == other.capturedAtMillis &&
            sourceId == other.sourceId &&
            rgbJpeg.contentEquals(other.rgbJpeg) &&
            depthMillimetres == other.depthMillimetres
    }

    override fun hashCode(): Int {
        var result = rgbJpeg.contentHashCode()
        result = 31 * result + depthMillimetres.hashCode()
        result = 31 * result + capturedAtMillis.hashCode()
        result = 31 * result + sourceId.hashCode()
        return result
    }
}

/**
 * Metric depth, millimetres, row-major. Nothing reads this until #6 — it is
 * carried from day one so the distance work does not have to reopen the seam.
 *
 * A zero is **not** a distance of zero, it is *no reading*: glass, dark and
 * reflective surfaces come back invalid, and valid coverage averages only 62.6%
 * across the 21 captures ADR-0013 measured (20 of which ship here). Never treat
 * an invalid pixel as a measurement.
 */
data class DepthMap(
    val width: Int,
    val height: Int,
    val millimetres: ShortArray,
) {
    init {
        require(millimetres.size == width * height) {
            "depth is ${millimetres.size} px, expected ${width * height} for ${width}x$height"
        }
    }

    /** Millimetres at (x, y), or null where the sensor returned no reading. */
    fun at(x: Int, y: Int): Int? {
        val raw = millimetres[y * width + x].toInt() and 0xFFFF
        return if (raw == 0) null else raw
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthMap) return false
        return width == other.width && height == other.height &&
            millimetres.contentEquals(other.millimetres)
    }

    override fun hashCode(): Int =
        31 * (31 * width + height) + millimetres.contentHashCode()

    companion object {
        /**
         * No depth sensor at all — not "a sensor that read nothing".
         *
         * The distinction matters: a band frame where every pixel came back
         * invalid is a *measurement failure* worth logging and worth retrying,
         * while this is a phone camera that was never going to measure
         * anything. [Frame.hasDepth] is how the pipeline tells them apart.
         */
        val NONE = DepthMap(width = 0, height = 0, millimetres = ShortArray(0))
    }
}
