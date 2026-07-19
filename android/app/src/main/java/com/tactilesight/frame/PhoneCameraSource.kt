package com.tactilesight.frame

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tactilesight.core.DepthMap
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's own back camera as a [FrameSource] — TactileSight without the
 * band.
 *
 * ### What this mode is, and what it deliberately is not
 *
 * It is a complete answer to *"what is around me?"* on hardware everyone
 * already owns: no band, no pairing, install and press. That reach is worth
 * having on its own, and it is what makes this an app rather than an accessory.
 *
 * It is **not** navigation, and the difference is one word: **distance**.
 *
 * ADR-0013's rule is that the VLM never states a distance, so that every number
 * the user hears came from the depth sensor. A phone has no depth sensor, so
 * this source returns [DepthMap.NONE] and the pipeline speaks no numbers at
 * all. The alternative — letting the model estimate — would put a guessed metre
 * in front of someone who cannot see the thing they are about to walk into,
 * which is the precise failure the depth work exists to prevent.
 *
 * Object detection still runs: YOLO reads colour, so detections arrive and are
 * named, just unmeasured. That path already existed for glass and out-of-range
 * objects, which is why this source needs no branch anywhere downstream.
 *
 * ### Why ImageCapture and no preview
 *
 * Capture is on demand — one press, one frame — which is the same contract the
 * band has (ADR-0009), so nothing streams and the camera is not burning battery
 * between presses. There is no `Preview` use case bound because the user this
 * is built for cannot see one; the dev screen renders the captured JPEG
 * afterwards, which is the frame the model actually got rather than an
 * approximation of it.
 */
class PhoneCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : FrameSource {

    private val executor = Executors.newSingleThreadExecutor()
    private var capture: ImageCapture? = null

    /**
     * Bind the camera. Call before the first [capture] — binding takes a beat
     * and doing it inside a press would put that beat between the button and
     * the answer.
     *
     * Must run on the main thread: CameraX binds to a lifecycle.
     */
    suspend fun start() = withContext(Dispatchers.Main) {
        if (capture != null) return@withContext
        val provider = providerFor(context)
        val useCase = ImageCapture.Builder()
            // Latency over noise: this is a scene to describe, not a photo to
            // keep, and the VLM sees a 1024px-wide JPEG either way.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
        capture = useCase
        Log.i(TAG, "phone camera bound")
    }

    fun stop() {
        capture = null
        ProcessCameraProvider.getInstance(context).get().unbindAll()
        Log.i(TAG, "phone camera released")
    }

    override suspend fun capture(): Frame {
        start()
        val useCase = capture ?: error("camera is not bound")
        val jpeg = takeJpeg(useCase)
        return Frame(
            rgbJpeg = jpeg,
            depthMillimetres = DepthMap.NONE,
            capturedAtMillis = System.currentTimeMillis(),
            sourceId = SOURCE_ID,
        )
    }

    /**
     * One JPEG, straight out of the camera's own buffer.
     *
     * `takePicture` into memory rather than to a file: the frame is handed to
     * the model and dropped, and writing it to storage would mean a blind
     * user's surroundings accumulating on disk as a side effect of asking what
     * is in front of them.
     */
    private suspend fun takeJpeg(useCase: ImageCapture): ByteArray =
        suspendCancellableCoroutine { continuation ->
            useCase.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            continuation.resume(image.toJpegBytes())
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        } finally {
                            // Not closing this starves the capture pipeline
                            // after a handful of presses.
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    /** ImageCapture already hands back JPEG-encoded bytes in plane 0. */
    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private suspend fun providerFor(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }

    private companion object {
        const val TAG = "PhoneCameraSource"
        const val SOURCE_ID = "phone-camera"
    }
}
