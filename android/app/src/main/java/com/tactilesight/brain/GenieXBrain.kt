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
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain
import com.tactilesight.frame.DepthCoverage
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
    runtime: RuntimeIdValue? = null,
    computeUnit: ComputeUnitValue? = null,
    /** A prompt typed in the app; null or blank falls back to [VlmPrompt]. */
    private val promptOverride: () -> String? = { null },
) : SemanticBrain {

    /**
     * Which backend this bundle needs, inferred from what is in it unless the
     * caller forces one — #2's NPU-vs-GPU comparison forces both in turn.
     *
     * A QAIRT bundle is compiled per-SoC and carries [ModelStore.BUNDLE_MARKER];
     * GGUF weights can only be driven by llama_cpp. Guessing wrong is not a
     * graceful failure — QAIRT rejects GGUF with a bare error code.
     */
    private val runtime: RuntimeIdValue = runtime
        ?: if (File(modelDir, ModelStore.BUNDLE_MARKER).exists()) {
            RuntimeIdValue.QAIRT
        } else {
            RuntimeIdValue.LLAMA_CPP
        }

    /**
     * QAIRT exists to drive the Hexagon NPU. llama_cpp reaches Adreno through
     * ggml's OpenCL backend, which is where our fastest measured numbers came
     * from (71 tok/s prefill on Gemma-4-E4B).
     */
    private val computeUnit: ComputeUnitValue = computeUnit
        ?: if (this.runtime == RuntimeIdValue.QAIRT) ComputeUnitValue.NPU else ComputeUnitValue.GPU

    override val name: String = "GenieX (${this.runtime.value}/${this.computeUnit.value})"

    /** Gigabytes of mapped weights — the thing the one-model rule is about. */
    override val holdsModel: Boolean = true

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

    /**
     * Map the model now, so the first press does not pay for it.
     *
     * Reports the outcome rather than throwing: a model that will not load is a
     * state the screen has to be able to show, not an error that kills startup.
     * The app still runs and still speaks — it just says so.
     */
    override suspend fun prepare(): Boolean = try {
        load()
        true
    } catch (e: Exception) {
        Log.e(TAG, "model failed to load", e)
        false
    }

    /** True once the model is mapped and a press will not have to wait. */
    val isLoaded: Boolean get() = vlm != null

    /**
     * The sentence as it is being written, for a screen that wants to show it.
     *
     * The model already decodes token by token — this only surfaces what was
     * being thrown away. Worth having because a press spends seconds in the
     * model and then seconds more in translation and speech, and a screen that
     * says "Looking…" for twenty seconds is indistinguishable from one that has
     * hung. Watching the sentence appear is the difference between waiting and
     * wondering.
     *
     * Sighted-only, and deliberately so: the spoken answer still arrives whole,
     * at the end. Speaking half-formed clauses as they decode would be worse
     * than silence for the user this is built for.
     *
     * Called on the decoding thread. Whoever sets this owns getting to the UI.
     */
    @Volatile
    var onPartial: ((String) -> Unit)? = null

    override suspend fun describe(frame: Frame, question: String?, surfaceIsFlat: Boolean): Answer {
        val base = promptOverride()?.takeIf { it.isNotBlank() } ?: VlmPrompt.forRequest(question)
        return describeWith(
            frame,
            if (surfaceIsFlat) VlmPrompt.withFlatSurface(base) else base,
            stream = true,
        )
    }

    /**
     * Only the on-device tier does this. At ~330 ms a second pass is free
     * against a press that spends 20 s in speech; over a network it would not
     * be, which is why the seam defaults to null rather than to this.
     */
    override suspend fun nameDirections(frame: Frame): String? = try {
        describeWith(frame, VlmPrompt.nameDirections()).spoken
    } catch (e: Exception) {
        Log.w(TAG, "direction naming failed — falling back to unnamed distances", e)
        null
    }

    /**
     * Describe [frame] with an explicit [prompt], bypassing [VlmPrompt].
     *
     * Exists so prompt wording can be **measured rather than argued about**:
     * the wording is load-bearing (see VlmPrompt) and every change to it has so
     * far been justified by anecdote. This is the seam that lets two variants
     * run over the same captures and be scored.
     */
    suspend fun describeWith(frame: Frame, prompt: String, stream: Boolean = false): Answer {
        val model = load()

        // Every press is a fresh look at the world, not the next turn of a
        // conversation. Without this the pipeline can carry state between
        // presses and blend the previous scene into the current answer — which
        // reads as hallucination rather than as a stale cache, and is hard to
        // catch because the sentence still sounds entirely plausible.
        //
        // QAIRT appears not to accumulate (it reports n_past=0 and repeats
        // byte-identical answers), but that is its behaviour, not our
        // guarantee, and llama_cpp need not match it. Cheap to make certain.
        // reset() returns a status code the SDK does not force you to look at.
        // Ignoring it is how "we call reset" turns into "we call reset and it
        // quietly does nothing".
        val resetCode = model.reset()
        Log.i(TAG, "reset -> $resetCode")

        val imagePath = writeFrameForVlm(frame)

        try {
            val messages = arrayOf(
                VlmChatMessage(
                    "user",
                    listOf(
                        VlmContent(CONTENT_IMAGE, imagePath.absolutePath),
                        VlmContent(CONTENT_TEXT, prompt),
                    ),
                ),
            )

            // The image reaches the model through GenerationConfig.imagePaths;
            // injectMediaPathsToConfig walks the messages and fills them in.
            // Sampling is set explicitly, not left to the SDK default. A crowded
            // scene made the model loop — "to your right is a person sitting on
            // a beanbag chair" eight times until it hit the token cap — which is
            // a decoding failure, not a prompt one, and no wording fixes it.
            val generation = GenerationConfig().apply {
                maxTokens = MAX_TOKENS
                samplerConfig = SamplerConfig().apply {
                    temperature = TEMPERATURE
                    repetitionPenalty = REPETITION_PENALTY
                }
            }
            val configured = model.injectMediaPathsToConfig(messages, generation)

            val templated = model.applyChatTemplate(messages, null, true).getOrThrow()

            val answer = StringBuilder()
            model.generateStreamFlow(templated.formattedText, configured).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        answer.append(result.text)
                        // Only the description streams to the screen. The
                        // direction-naming pass runs through here too, and
                        // spraying "AHEAD=people; LEFT=furniture" across the
                        // status line would show the user the machinery rather
                        // than the answer.
                        if (stream) onPartial?.invoke(answer.toString())
                    }
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
            val spoken = answer.toString().trim()
            Log.i(TAG, "answer: $spoken")
            return Answer(spoken)
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
                        modelPathFor(bundle),
                        mmprojPathIn(bundle),
                        // Positional, because verbose has no setter — and its
                        // native logging is the only way to see why creation
                        // fails; the JNI layer surfaces bare error codes.
                        // n_ctx must be 0 on QAIRT: the bundle's context is
                        // compiled in (4096) and the plugin rejects any value,
                        // including the SDK default —
                        //   "--nctx (n_ctx) is not supported by the qairt plugin"
                        ModelConfig(
                            /* nCtx = */ if (runtime == RuntimeIdValue.LLAMA_CPP) CONTEXT_TOKENS else 0,
                            /* nThreads = */ 8,
                            /* nThreadsBatch = */ 8,
                            /* nBatch = */ 2048,
                            /* nUBatch = */ 512,
                            /* nSeqMax = */ 1,
                            // llama_cpp offloads nothing unless told how many
                            // layers to move; 0 would run on CPU while still
                            // reporting "gpu", which is exactly the kind of
                            // silent lie #2's benchmark exists to catch. QAIRT
                            // ignores this — its placement is compiled in.
                            /* nGpuLayers = */ if (runtime == RuntimeIdValue.LLAMA_CPP) ALL_LAYERS else 0,
                            /* chatTemplatePath = */ "",
                            /* chatTemplateContent = */ "",
                            /* maxTokens = */ 2048,
                            /* enableThinking = */ false,
                            /* verbose = */ true,
                        ),
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
     * The QAIRT plugin takes the dirname of `model_path` and looks for `.bin`
     * shards there, so it must be handed a **file inside** the bundle. Passing
     * the bundle directory makes it search the parent and fail with:
     *
     * ```
     * No .bin LLM shards found in: …/files/models/geniex
     * ```
     *
     * `genie_config.json` is the natural anchor — it is the pipeline config the
     * bundle is built around, and it sits beside the shards.
     */
    private fun modelPathFor(bundle: File): String {
        val anchor = File(bundle, ModelStore.BUNDLE_MARKER).takeIf { it.exists() }
            ?: bundle.listFiles { it.isWeightFile }?.minByOrNull { it.name }
            ?: return bundle.absolutePath
        return anchor.absolutePath
    }

    /** The projector is weights too, but it is never the model to load. */
    private val File.isWeightFile: Boolean
        get() = extension in ModelStore.WEIGHT_EXTENSIONS && !name.startsWith(MMPROJ_PREFIX)

    /**
     * GenieX's llama_cpp backend needs the multimodal projector alongside the
     * weights; QAIRT bundles carry the vision encoder internally and do not.
     */
    private fun mmprojPathIn(bundle: File): String =
        bundle.listFiles { f -> f.name.startsWith(MMPROJ_PREFIX) }
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
        // init() already loads the bundled plugins; this call reports
        // "dlopen failed: library qairt not found" because it wants a library
        // path, not an id. Harmless — creation still routes to the plugin — so
        // we only log the version, which is the useful part.
        Log.i(TAG, "plugin $pluginId version=${sdk.getPluginVersion(pluginId)}")
    }

    /**
     * GenieX takes image *paths*, so the frame is spilled to cache and deleted
     * after.
     *
     * The frame is cropped to the region depth can actually measure first. RGB
     * and depth are different sensors 24.9 mm apart with different fields of
     * view, so the VLM would otherwise describe objects the phone can never put
     * a distance on — and the user would be told about something that then
     * answers "distance unknown" (see [DepthCoverage]).
     */
    private suspend fun writeFrameForVlm(frame: Frame): File = withContext(Dispatchers.IO) {
        val measurable = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg)
        File.createTempFile("frame", ".jpg", context.cacheDir)
            .apply { writeBytes(measurable) }
    }

    /** Only ever called on engine/model switch. Never on Activity recreation. */
    override fun close() {
        // Says whether a model was actually resident, because "released" on a
        // brain that never loaded reads identically in the log to a real
        // release — and this path had never once been observed to run.
        val wasLoaded = vlm != null
        vlm?.close()
        vlm = null
        Log.i(TAG, if (wasLoaded) "released $name — model was resident" else "closed $name — never loaded")
    }

    private companion object {
        const val TAG = "GenieXBrain"
        const val CONTENT_IMAGE = "image"
        const val CONTENT_TEXT = "text"
        /**
         * Enough that a real answer finishes; tight enough that a rambling one
         * still ends.
         *
         * A good answer is ~22 tokens and stops on `eos` — *"In front of you is
         * a sign that says "WASHROOM" and a doorway to your right."* The cap
         * only bites on crowded scenes, and at 64 it bit **mid-word**: a room of
         * people on bean bags gave *"…and in front of you is a white chair with
         * a person sitting in"* — `stop=length`, cut in the middle. Spoken
         * aloud that is worse than a short answer, because the listener cannot
         * tell whether the sentence ended or the device failed.
         *
         * 96 costs ~3.8 s of decode at the measured 25 tok/s in the worst case,
         * against ~0.9 s for a typical answer. The ceiling is what is being paid
         * for here, not the common case.
         *
         * **Raising this fixes a truncated answer, not a listy one.** The
         * bean-bag response was already failing the prompt's "group related
         * things into one phrase rather than listing them" well before it ran
         * out of tokens. If crowded scenes still produce inventories, that is a
         * prompt problem and this constant is the wrong place to fix it.
         */
        const val MAX_TOKENS = 96
        /**
         * Low, not zero. This is a description of a real scene, not a creative
         * task: we want the likeliest reading of what is in frame, and variance
         * here shows up as invented detail.
         */
        const val TEMPERATURE = 0.2f

        /**
         * The fix for a model that loops. Left at the SDK default, a lounge full
         * of people produced "to your right is a person sitting on a beanbag
         * chair" eight times over, running to the token cap mid-phrase. 1.15 is
         * enough to break the cycle without pushing it to reach for unusual
         * words - which, in a device that must not invent detail, is the failure
         * mode on the other side of this dial.
         */
        const val REPETITION_PENALTY = 1.15f

        const val CONTEXT_TOKENS = 1024
        const val MMPROJ_PREFIX = "mmproj"

        /** More than any model has, which is llama.cpp's idiom for "all of them". */
        const val ALL_LAYERS = 99
    }
}
