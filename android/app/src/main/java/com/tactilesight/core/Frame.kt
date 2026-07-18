package com.tactilesight.core

/**
 * One capture instant from the band's Astra Pro Plus: colour, infrared and raw
 * depth, all from the same moment (ADR-0009 — the band sends on-demand triplets,
 * nothing streams).
 *
 * IR and depth share the same 640×480 grid and the same optics, so a pixel in
 * one indexes the other directly — that is what makes distance calibration-free
 * (ADR-0013). RGB is a different sensor at 1280×720 and does **not** line up.
 */
data class Frame(
    /** JPEG, 1280×720. What the VLM looks at. */
    val rgbJpeg: ByteArray,
    /** JPEG, 640×480, 8-bit. Detection runs here (ADR-0013). */
    val irJpeg: ByteArray,
    /** Depth in millimetres, 640×480, row-major. 0 means no reading. */
    val depthMillimetres: DepthMap,
    val capturedAtMillis: Long,
    /** Where this came from, for the dev UI and logs. */
    val sourceId: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return capturedAtMillis == other.capturedAtMillis &&
            sourceId == other.sourceId &&
            rgbJpeg.contentEquals(other.rgbJpeg) &&
            irJpeg.contentEquals(other.irJpeg) &&
            depthMillimetres == other.depthMillimetres
    }

    override fun hashCode(): Int {
        var result = rgbJpeg.contentHashCode()
        result = 31 * result + irJpeg.contentHashCode()
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
 * across our 21 captures. Never treat an invalid pixel as a measurement.
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
}
