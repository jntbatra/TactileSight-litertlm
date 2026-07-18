package com.tactilesight.frame

import android.content.res.AssetManager
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Frames from the 21 real Astra Pro Plus captures shipped in the APK.
 *
 * These are genuine band output, not synthetic fixtures — so the whole pipeline
 * runs with no band hardware attached, and a wiped phone still demos. The live
 * WebRTC source is a sibling behind the same seam (#19).
 */
class BundledCaptureSource(
    private val assets: AssetManager,
) : FrameSource {

    /** Capture folder names, sorted — `scene_1_id001` … `scene_1_id021`. */
    val sceneIds: List<String> by lazy {
        assets.list(ROOT)?.sorted().orEmpty()
    }

    /** Which capture the next [capture] call returns. */
    var selectedIndex: Int = 0
        set(value) {
            require(value in sceneIds.indices) { "no capture at index $value" }
            field = value
        }

    val selectedSceneId: String get() = sceneIds[selectedIndex]

    override suspend fun capture(): Frame = load(selectedSceneId)

    suspend fun load(sceneId: String): Frame = withContext(Dispatchers.IO) {
        val dir = "$ROOT/$sceneId"
        Frame(
            rgbJpeg = read("$dir/rgb.jpg"),
            irJpeg = read("$dir/ir.jpg"),
            depthMillimetres = assets.open("$dir/depth_raw.npy").use(NpyReader::readDepthMap),
            // These are recorded captures; the honest capture time is the one in
            // the file, not now. Read from metadata.json when #6 needs it.
            capturedAtMillis = 0L,
            sourceId = sceneId,
        )
    }

    /** The colourised depth image — for showing a human, never for measuring. */
    suspend fun depthPreview(sceneId: String): ByteArray =
        withContext(Dispatchers.IO) { read("$ROOT/$sceneId/depth_colorized.jpg") }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    private companion object {
        const val ROOT = "captures"
    }
}
