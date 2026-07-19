package com.tactilesight.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YOLOv11 over the colour frame, so a distance can be attached to *a thing*
 * rather than to a direction.
 *
 * ### Why colour and not infrared
 *
 * ADR-0013 specifies detection on IR, because IR shares depth's exact 640×480
 * grid and a box therefore indexes depth 1:1 with no calibration. That reasoning
 * is sound and the conclusion is still wrong for us: **our IR frames are
 * effectively black.** Measured across all 20 shipped captures, mean brightness
 * is 3.2/255 and 0.36% of pixels exceed 40 — four captures have literally none.
 * A detector trained on daylight photographs finds nothing there.
 *
 * So we detect on colour and map the box into depth using the **measured**
 * correspondence in [DepthCoverage] (RGB x 0.09–0.88 ↔ depth full width, depth
 * y 0.07–0.955 ↔ RGB full height). That is an empirical fit, not a calibration,
 * and it is good to about a box rather than to a pixel — which is all that is
 * needed to land a person's box on a person's depth.
 *
 * ### What it can and cannot name
 *
 * COCO's 80 classes. It knows `person`, `chair`, `couch`, `tv`, `backpack`. It
 * does **not** know *door*, *doorway*, *stairs*, *step*, *curb* or *sign* — much
 * of what a blind user most needs. That is why this **supplements** the VLM
 * rather than replacing it: the VLM reads "a sign that says WASHROOM" and names
 * a doorway; this attaches metres to the things it does know.
 */
class ObjectDetector(context: Context) : AutoCloseable {

    data class Detection(
        val label: String,
        val confidence: Float,
        /** Box in fractions of the colour frame, 0..1, left/top/right/bottom. */
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centreX get() = (left + right) / 2f
    }

    private val interpreter: Interpreter? = try {
        val asset = context.assets.openFd(MODEL_ASSET)
        FileInputStream(asset.fileDescriptor).use { stream ->
            val model = stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                asset.startOffset,
                asset.declaredLength,
            )
            Interpreter(model, Interpreter.Options().apply { numThreads = THREADS })
        }
    } catch (e: Exception) {
        // A missing or broken detector must not cost the user their answer:
        // the VLM description and the per-direction distance both still work.
        Log.w(TAG, "detector unavailable — continuing without object distances", e)
        null
    }

    val isAvailable get() = interpreter != null

    /** Detections above [MIN_CONFIDENCE], strongest first, after NMS. */
    fun detect(rgbJpeg: ByteArray): List<Detection> {
        val model = interpreter ?: return emptyList()
        val source = BitmapFactory.decodeByteArray(rgbJpeg, 0, rgbJpeg.size) ?: return emptyList()

        return try {
            val scaled = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
            val input = toFloatBuffer(scaled)
            scaled.recycle()

            val boxes = Array(1) { Array(ANCHORS) { FloatArray(4) } }
            val scores = Array(1) { FloatArray(ANCHORS) }
            val classes = Array(1) { ByteArray(ANCHORS) }
            model.runForMultipleInputsOutputs(
                arrayOf<Any>(input),
                mapOf(0 to boxes, 1 to scores, 2 to classes),
            )

            val found = ArrayList<Detection>()
            for (i in 0 until ANCHORS) {
                if (scores[0][i] < MIN_CONFIDENCE) continue
                val classIndex = classes[0][i].toInt() and 0xff
                if (classIndex !in COCO_LABELS.indices) continue
                val box = boxes[0][i]
                found += Detection(
                    label = COCO_LABELS[classIndex],
                    confidence = scores[0][i],
                    left = box[0] / INPUT_SIZE,
                    top = box[1] / INPUT_SIZE,
                    right = box[2] / INPUT_SIZE,
                    bottom = box[3] / INPUT_SIZE,
                )
            }
            suppressOverlaps(found)
        } catch (e: Exception) {
            Log.w(TAG, "detection failed", e)
            emptyList()
        } finally {
            source.recycle()
        }
    }

    /**
     * Non-maximum suppression. The exported graph decodes boxes but does not
     * suppress them, so one person arrives as six overlapping boxes — and six
     * "a person" in one sentence is worse than none.
     */
    private fun suppressOverlaps(all: List<Detection>): List<Detection> {
        val remaining = all.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<Detection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { overlap(best, it) > IOU_THRESHOLD }
            if (kept.size >= MAX_DETECTIONS) break
        }
        return kept
    }

    private fun overlap(a: Detection, b: Detection): Float {
        val x0 = maxOf(a.left, b.left)
        val y0 = maxOf(a.top, b.top)
        val x1 = minOf(a.right, b.right)
        val y1 = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, x1 - x0) * maxOf(0f, y1 - y0)
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /** NHWC float32, 0..1 — what the exported graph expects. */
    private fun toFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xff) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xff) / 255f)
            buffer.putFloat((pixel and 0xff) / 255f)
        }
        return buffer.rewind() as ByteBuffer
    }

    override fun close() {
        interpreter?.close()
    }

    private companion object {
        const val TAG = "ObjectDetector"
        const val MODEL_ASSET = "models/yolov11_det.tflite"
        const val INPUT_SIZE = 640
        const val CHANNELS = 3
        const val ANCHORS = 8400
        const val THREADS = 4

        /**
         * Below this the detector is guessing. Chosen from the captures: at
         * 0.35 the empty alcove (id011) still yields **zero** detections, which
         * is the property that matters — a device that invents a person in an
         * empty corridor is worse than one that stays quiet.
         */
        const val MIN_CONFIDENCE = 0.35f
        const val IOU_THRESHOLD = 0.5f

        /** A spoken sentence cannot carry more than a few things anyway. */
        const val MAX_DETECTIONS = 8

        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush",
        )
    }
}
