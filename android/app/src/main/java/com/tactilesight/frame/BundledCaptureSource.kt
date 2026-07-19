package com.tactilesight.frame

import android.content.res.AssetManager
import com.tactilesight.core.BrowsableFrameSource
import com.tactilesight.core.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Frames from the 20 real Astra Pro Plus captures shipped in the APK.
 *
 * These are genuine band output, not synthetic fixtures — so the whole pipeline
 * runs with no band hardware attached, and a wiped phone still demos. The live
 * WebRTC source (#19) is a sibling behind the same seam; it is not browsable,
 * which is why browsing lives in [BrowsableFrameSource] rather than here.
 */
class BundledCaptureSource(
    private val assets: AssetManager,
) : BrowsableFrameSource {

    /** Capture folder names, sorted — `scene_1_id001` … `scene_1_id020`. */
    override val sceneIds: List<String> by lazy {
        assets.list(ROOT)?.sorted().orEmpty()
    }

    override var selectedIndex: Int = 0
        set(value) {
            require(value in sceneIds.indices) { "no capture at index $value" }
            field = value
        }

    override suspend fun capture(): Frame = load(sceneIds[selectedIndex])

    override suspend fun load(sceneId: String): Frame = withContext(Dispatchers.IO) {
        val dir = "$ROOT/$sceneId"
        Frame(
            rgbJpeg = read("$dir/rgb.jpg"),
            depthMillimetres = assets.open("$dir/depth_raw.npy").use(NpyReader::readDepthMap),
            // These are recorded captures; the honest capture time is the one in
            // the file, not now. Read from metadata.json when #6 needs it.
            capturedAtMillis = 0L,
            sourceId = sceneId,
        )
    }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    private companion object {
        const val ROOT = "captures"
    }
}
