package com.tactilesight.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * The part of the colour frame that depth can actually measure.
 *
 * RGB (1280×720) and depth/IR (640×480) are **different sensors 24.9 mm apart**
 * with different fields of view, so some of what the camera sees has no depth
 * behind it at all. If the VLM describes an object out there, the phone can
 * never state a distance for it — and the honest answer becomes "distance
 * unknown" for something the user was just told about. Cropping the frame
 * before the VLM sees it means the VLM can only talk about things we can
 * measure.
 *
 * ### Where these numbers come from
 *
 * Not from the docs — they disagree and both are wrong. Measured by unprojecting
 * every valid depth pixel from 8 real captures (1.28 M points) into the colour
 * frame using `calib.json`'s depth intrinsics and the factory
 * `depth_to_color_extrinsics`, then taking the 1st–99th percentile of where they
 * land:
 *
 * ```
 * covered width  : 0.034 .. 0.928
 * covered height : 0.025 .. 0.955
 * ```
 *
 * ### What the docs get wrong
 *
 * `calib.json` says *"Depth covers only the central vertical region of the RGB
 * frame"* and TEAM.md says *"objects at the top/bottom of the RGB frame have no
 * depth"*. The vertical axis is barely the constraint: depth's vertical FOV is
 * 45.0° against the 16:9 RGB's 36.5°, so **depth actually sees more vertically
 * than the colour frame does**. (That follows from TEAM.md's own correction that
 * 720p is a crop, so `fy == fx`.)
 *
 * The real limit is **horizontal, and asymmetric**: ~3% is lost on the left and
 * ~7% on the right. That asymmetry is the 24.9 mm baseline between the sensors,
 * and no document mentions it.
 *
 * ### What this does not fix
 *
 * Only the systematic field-of-view mismatch. **Depth holes remain** — glass,
 * dark and reflective surfaces read invalid, and valid coverage averages 62.6%
 * *inside* this region. Those are handled where they must be, by refusing to
 * state a distance (ADR-0013). Cropping narrows the problem; it does not remove
 * the need for "distance unknown".
 *
 * Coverage is also mildly depth-dependent (parallax grows as objects get
 * closer), so the bounds are taken conservatively across a range of scenes
 * rather than computed per frame.
 */
object DepthCoverage {

    const val LEFT_FRACTION = 0.034f
    const val RIGHT_FRACTION = 0.928f
    const val TOP_FRACTION = 0.025f
    const val BOTTOM_FRACTION = 0.955f

    /** Pixel bounds of the measurable region. Pure maths — unit-tested. */
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    fun cropRect(imageWidth: Int, imageHeight: Int): Rect {
        val left = (imageWidth * LEFT_FRACTION).toInt()
        val top = (imageHeight * TOP_FRACTION).toInt()
        val right = (imageWidth * RIGHT_FRACTION).toInt().coerceAtMost(imageWidth)
        val bottom = (imageHeight * BOTTOM_FRACTION).toInt().coerceAtMost(imageHeight)
        return Rect(
            left = left,
            top = top,
            width = (right - left).coerceAtLeast(1),
            height = (bottom - top).coerceAtLeast(1),
        )
    }

    /**
     * Crop a JPEG to the measurable region and re-encode. Returns the original
     * bytes unchanged if it cannot be decoded — a describable frame beats a
     * correctly-cropped crash.
     */
    fun cropToMeasurableRegion(jpeg: ByteArray, quality: Int = 90): ByteArray {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val rect = cropRect(source.width, source.height)
            val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
            ByteArrayOutputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
                cropped.recycle()
                out.toByteArray()
            }
        } catch (e: IllegalArgumentException) {
            jpeg
        } finally {
            source.recycle()
        }
    }
}
