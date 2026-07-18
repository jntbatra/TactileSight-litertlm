package com.tactilesight.brain

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The on-device describing brain: Qwen3-VL-4B on GenieX.
 *
 * Runs the QAIRT backend on the Hexagon NPU where the bundle matches the SoC —
 * ours is built for `qualcomm-snapdragon-8-elite-gen5`, and the device is SM8850
 * (8 Elite Gen 5). Falls back to the `llama_cpp` backend on GPU/CPU otherwise.
 *
 * **Model lifecycle is a hard rule, not a preference** (ADR-0010):
 * - loaded **once**, on first use, behind [loadLock] so two presses cannot race
 *   into two loads of a multi-gigabyte model
 * - stays resident for the process lifetime
 * - released **only** in [close], which is called only on engine/model switch —
 *   never on Activity recreation
 *
 * The previous app released its model in `onDestroy`, so every rotation dropped
 * and reloaded it, leaked the native allocation, drove available RAM to ~1 GB
 * and was OOM-killed six times. This class is owned by the Application for that
 * reason.
 */
class GenieXBrain(
    private val context: Context,
    private val modelDir: File,
    private val runtime: RuntimeIdValue = RuntimeIdValue.QAIRT,
    private val computeUnit: ComputeUnitValue = ComputeUnitValue.NPU,
) : SemanticBrain {

    override val name: String = "GenieX (${runtime.value}/${computeUnit.value})"

    private val loadLock = Mutex()

    @Volatile
    private var vlm: VlmWrapper? = null

    /** Set once the first generation completes — the NPU-vs-GPU evidence (#2). */
    @Volatile
    var lastProfile: Profile? = null
        private set

    data class Profile(
        val timeToFirstTokenMs: Double,
        val prefillTokensPerSecond: Double,
        val decodeTokensPerSecond: Double,
        val promptTokens: Long,
        val generatedTokens: Long,
        val stopReason: String,
        val runtime: String,
        val computeUnit: String,
    )

    override suspend fun describe(frame: Frame, question: String?): Answer {
        val model = load()
        val imagePath = writeFrameForVlm(frame)

        try {
            val messages = arrayOf(
                VlmChatMessage(
                    "user",
                    listOf(
                        VlmContent(CONTENT_IMAGE, imagePath.absolutePath),
                        VlmContent(CONTENT_TEXT, VlmPrompt.forRequest(question)),
                    ),
                ),
            )

            // The image reaches the model through GenerationConfig.imagePaths;
            // injectMediaPathsToConfig walks the messages and fills them in.
            val generation = GenerationConfig().apply { maxTokens = MAX_TOKENS }
            val configured = model.injectMediaPathsToConfig(messages, generation)

            val templated = model.applyChatTemplate(messages, null, true).getOrThrow()

            val answer = StringBuilder()
            model.generateStreamFlow(templated.formattedText, configured).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> answer.append(result.text)
                    is LlmStreamResult.Error -> throw result.throwable
                    is LlmStreamResult.Completed -> lastProfile = result.profile.let {
                        Profile(
                            timeToFirstTokenMs = it.ttftMs,
                            prefillTokensPerSecond = it.prefillSpeed,
                            decodeTokensPerSecond = it.decodingSpeed,
                            promptTokens = it.promptTokens,
                            generatedTokens = it.generatedTokens,
                            stopReason = it.stopReason.orEmpty(),
                            runtime = runtime.value.orEmpty(),
                            computeUnit = computeUnit.value.orEmpty(),
                        )
                    }
                }
            }

            lastProfile?.let {
                Log.i(
                    TAG,
                    "ttft=${it.timeToFirstTokenMs}ms prefill=${it.prefillTokensPerSecond}tok/s " +
                        "decode=${it.decodeTokensPerSecond}tok/s tokens=${it.generatedTokens} " +
                        "runtime=${it.runtime} unit=${it.computeUnit} stop=${it.stopReason}",
                )
            }

            // A blank answer is handled upstream by the Orchestrator, which
            // degrades it to spoken fallback rather than a silent press.
            return Answer(answer.toString().trim())
        } finally {
            imagePath.delete()
        }
    }

    /** Load once, lock-guarded. A hot reload of this model is 30–60 s. */
    private suspend fun load(): VlmWrapper {
        vlm?.let { return it }

        return loadLock.withLock {
            // Re-check inside the lock: a caller that queued while another was
            // loading must reuse that result, not start a second load.
            vlm?.let { return@withLock it }

            initSdk()

            val bundle = requireNotNull(modelDir.takeIf { it.isDirectory }) {
                "no GenieX model at ${modelDir.absolutePath} — sideload one, see ModelStore"
            }

            Log.i(TAG, "loading ${bundle.name} on ${runtime.value}/${computeUnit.value}")
            val started = System.currentTimeMillis()

            val created = VlmWrapper.builder()
                .vlmCreateInput(
                    VlmCreateInput(
                        bundle.name,
                        bundle.absolutePath,
                        mmprojPathIn(bundle),
                        ModelConfig().apply {
                            // A one-shot description needs nowhere near the
                            // bundle's 4096 ceiling, and KV cache scales with it.
                            nCtx = CONTEXT_TOKENS
                        },
                        runtime.value,
                        computeUnit.value,
                    ),
                )
                .dispatcher(Dispatchers.IO)
                .build()
                .getOrThrow()

            Log.i(TAG, "loaded in ${System.currentTimeMillis() - started}ms")
            vlm = created
            created
        }
    }

    /**
     * GenieX's llama_cpp backend needs the multimodal projector alongside the
     * weights; QAIRT bundles carry the vision encoder internally and do not.
     */
    private fun mmprojPathIn(bundle: File): String =
        bundle.listFiles { f -> f.name.startsWith("mmproj") }
            ?.firstOrNull()
            ?.absolutePath
            .orEmpty()

    private suspend fun initSdk() = withContext(Dispatchers.IO) {
        val sdk = GenieXSdk.getInstance()

        suspendCancellableCoroutine { continuation ->
            sdk.init(
                context,
                object : GenieXSdk.InitCallback {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onFailure(message: String) {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                            Log.e(TAG, "GenieX init reported failure: $message")
                        }
                    }
                },
            )
        }

        val pluginId = when (runtime) {
            RuntimeIdValue.QAIRT -> GenieXSdk.PLUGIN_ID_QAIRT
            RuntimeIdValue.LLAMA_CPP -> GenieXSdk.PLUGIN_ID_LLAMA_CPP
        }
        val status = sdk.registerPlugin(pluginId)
        Log.i(TAG, "registerPlugin($pluginId) -> $status, version=${sdk.getPluginVersion(pluginId)}")
    }

    /** GenieX takes image *paths*, so the frame is spilled to cache and deleted after. */
    private suspend fun writeFrameForVlm(frame: Frame): File = withContext(Dispatchers.IO) {
        File.createTempFile("frame", ".jpg", context.cacheDir)
            .apply { writeBytes(frame.rgbJpeg) }
    }

    /** Only ever called on engine/model switch. Never on Activity recreation. */
    override fun close() {
        vlm?.close()
        vlm = null
        Log.i(TAG, "released")
    }

    private companion object {
        const val TAG = "GenieXBrain"
        const val CONTENT_IMAGE = "image"
        const val CONTENT_TEXT = "text"
        const val MAX_TOKENS = 64
        const val CONTEXT_TOKENS = 1024
    }
}
