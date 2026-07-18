package com.tactilesight.frame

import android.graphics.Bitmap
import android.graphics.Color
import com.tactilesight.core.DepthMap

/**
 * Renders a [DepthMap] for a human to look at: **near = blue, far = red,
 * no reading = black** — the band's own JET convention, so our preview and the
 * band team's tooling colour the same scene the same way.
 *
 * Two deliberate differences from simply showing `depth_colorized.jpg`:
 *
 * 1. We colourise the **real measurements**, so the carousel works for any
 *    source — a live band sends depth, not a pre-rendered preview.
 * 2. Invalid pixels are **black**. In the band's JPEG a no-reading pixel comes
 *    out dark blue (measured: RGB 2,1,126), almost indistinguishable from a
 *    genuinely near one — so a depth hole reads as an object at arm's length.
 *    That is the exact confusion ADR-0013 exists to prevent, so holes are shown
 *    as holes.
 *
 * Display only. Nothing here feeds a distance calculation.
 */
object DepthRenderer {

    /** Clamp range in millimetres — the band's usable span (ADR-0013). */
    private const val NEAR_MM = 400
    private const val FAR_MM = 8000

    fun render(depth: DepthMap): Bitmap {
        val pixels = IntArray(depth.width * depth.height)

        for (y in 0 until depth.height) {
            for (x in 0 until depth.width) {
                val mm = depth.at(x, y)
                pixels[y * depth.width + x] =
                    if (mm == null) Color.BLACK else colourFor(mm)
            }
        }

        return Bitmap.createBitmap(pixels, depth.width, depth.height, Bitmap.Config.ARGB_8888)
    }

    /** Near → blue, mid → green, far → red, matching the band's JET output. */
    private fun colourFor(millimetres: Int): Int {
        val t = ((millimetres - NEAR_MM).toFloat() / (FAR_MM - NEAR_MM)).coerceIn(0f, 1f)
        // Hue 240° (blue, near) down through 0° (red, far).
        return Color.HSVToColor(floatArrayOf(240f - t * 240f, 1f, 1f))
    }
}
