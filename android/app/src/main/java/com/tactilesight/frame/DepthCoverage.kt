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
 * Measured 2026-07-18, and they replace an earlier set that was wrong in both
 * axes. Two documented theories about this camera are both false: 1280x720 is
 * **neither** a crop of the 4:3 frame (TEAM.md) **nor** an anamorphic scale of
 * it (`calib.json`). It is a horizontally *wider*, vertically *narrower*
 * readout, established by comparing the colour camera against itself at
 * different resolutions over UVC — a same-modality comparison, which converges
 * (NCC 0.98) where three cross-modal RGB-IR attempts had failed.
 *
 * That gave real 720p intrinsics, corroborated against Orbbec's datasheet to
 * within 1.7%:
 *
 * ```
 * measured    fx 966.5  fy 967.3  cx 634.6  cy 364.4
 * calib.json  fx 1090.8 fy 818.1            <- wrong by 13% and 18%
 * ```
 *
 * `calib.json`'s 720p block is self-labelled "approximate", and an Orbbec
 * maintainer confirms the Pro Plus's high-resolution colour "is indeed not
 * calibrated". Reprojecting valid depth through the factory extrinsics into
 * the *measured* intrinsics gives:
 *
 * ```
 * covered width  : 0.09 .. 0.88     (horizontal is the real constraint)
 * covered height : full frame       (depth sees MORE vertically than colour)
 * ```
 *
 * ### Why there is no vertical crop
 *
 * Depth's vertical FOV is 45.0 deg against 720p colour's 40.8 deg, so the
 * colour frame is entirely inside depth's vertical field. Measured on 3.87 M
 * points from all 20 captures, 8.1% of depth projects above the colour frame
 * and 4.8% below — depth overflowing colour, not the reverse. Cropping
 * vertically threw away frame area that *does* have depth behind it.
 *
 * ### The bug this replaces
 *
 * The previous bounds (x 0.034-0.928, y 0.025-0.955) were computed in the
 * **640x480** colour frame and then applied to **1280x720** images. A unit
 * mismatch, not a bad estimate. Their horizontal bands admitted regions
 * containing *zero* depth (0.034-0.087 left, 0.881-0.928 right) — precisely
 * the failure this class exists to prevent.
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
 * closer — at 0.4-0.8 m the band tightens to x 0.102-0.810), so the bounds are
 * taken conservatively across a range of scenes rather than computed per frame.
 * Sensitivity-tested against four intrinsic variants (measured,
 * datasheet-implied, +/-2%): this band captures 95.7-99.1% of depth points in
 * every case, and "no vertical crop" holds under all of them.
 */
object DepthCoverage {

    const val LEFT_FRACTION = 0.09f
    const val RIGHT_FRACTION = 0.88f

    // Depth's vertical field contains the colour frame's entirely, so there is
    // nothing to trim. Kept as named constants rather than deleted: the crop is
    // a rectangle, and a future sensor may not be so generous.
    const val TOP_FRACTION = 0.0f
    const val BOTTOM_FRACTION = 1.0f

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
