package com.tactilesight.brain

import android.util.Base64
import android.util.Log
import com.tactilesight.BuildConfig
import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain
import com.tactilesight.frame.DepthCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Describes a frame on **Qualcomm Cloud AI 100**, via Cirrascale's Imagine API.
 *
 * The phone talks to it directly rather than through our own server, and that
 * is the point: the three destinations must fail independently. Routing the
 * cloud tier through the laptop would make it *depend* on the laptop being up
 * and tunnelled, which would leave it strictly worse than the private-server
 * mode it is supposed to be an alternative to.
 *
 * The API speaks the OpenAI `chat/completions` shape — including `image_url`
 * content — so this is the same wire format as the laptop's llama.cpp server,
 * and the same one `server/backends/openai_compat.py` emits.
 *
 * ⚠️ **The key is compiled into the APK** (from `cirrascale.api.key` in
 * `local.properties`), exactly as the Sarvam key is. Anyone holding the APK can
 * extract it. That is an accepted trade for an MVP only the team installs, with
 * a key rotated after the event — a deliberate decision, not an oversight. Do
 * not ship this to real users unchanged.
 *
 * Uses [VlmPrompt], the same prompt the on-device brain uses, so switching
 * destination does not silently change how the app talks.
 */
class ImagineBrain(
    private val baseUrl: String,
    private val model: String,
    /** Blank for a local server — LM Studio and llama-server want no auth. */
    private val apiKey: String = BuildConfig.CIRRASCALE_API_KEY,
    private val promptOverride: () -> String? = { null },
    /** What to call this in the UI — the same wire serves two destinations. */
    private val label: String = "Cloud AI 100",
) : SemanticBrain {

    override val name: String = "$label ($model)"

    override suspend fun describe(frame: Frame, question: String?, surfaceIsFlat: Boolean): Answer =
        withContext(Dispatchers.IO) {
            // The same crop every other brain sees, so the destination does not
            // change what the model is looking at.
            val jpeg = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg)
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(jpeg, Base64.NO_WRAP)

            val content = JSONArray()
                .put(
                    JSONObject().put("type", "text").put(
                        "text",
                        promptOverride()?.takeIf { it.isNotBlank() } ?: VlmPrompt.forRequest(question),
                    ),
                )
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUrl)),
                )

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                put("max_tokens", MAX_TOKENS)
                // Low, for the same reason the cloud backend runs low: this
                // model is being asked what is *really* there, not to write.
                put("temperature", TEMPERATURE)
                put("stream", false)
            }.toString()

            val endpoint = completionsUrl(baseUrl)
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                // LM Studio and llama-server want no auth at all; sending an
                // empty bearer token is a 401 waiting to happen.
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val detail = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    error("Imagine API failed: HTTP ${connection.responseCode} $detail")
                }

                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val spoken = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim()

                Log.i(TAG, "answer: $spoken")
                Answer(spoken)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Where chat/completions lives, given whatever was typed in the endpoint
     * field.
     *
     * Two shapes arrive here. Cirrascale is handed with its version already in
     * the path (`https://…/apis/v2`); LM Studio and llama-server are handed as
     * a bare host and port (`http://10.0.0.5:1234`) and serve under `/v1`.
     * Appending blindly gives `POST /chat/completions`, which LM Studio answers
     * with "Unexpected endpoint… Returning 200 anyway" — a 200 carrying no
     * answer, which reads as a broken model rather than a wrong URL.
     */
    private fun completionsUrl(baseUrl: String): String {
        val root = baseUrl.trim().trimEnd('/')
        val hasVersionPath = URL(root).path.trim('/').isNotEmpty()
        return if (hasVersionPath) "$root$COMPLETIONS_PATH" else "$root$DEFAULT_VERSION$COMPLETIONS_PATH"
    }

    companion object {
        private const val TAG = "ImagineBrain"
        private const val COMPLETIONS_PATH = "/chat/completions"

        /** OpenAI-compatible servers serve under /v1 unless told otherwise. */
        private const val DEFAULT_VERSION = "/v1"

        /**
         * Generous on purpose. Some served models are **reasoning** models —
         * gemma-4-12b-qat spends its budget in `reasoning_content` first — and
         * a tight cap returns `content: ""` with `finish_reason: length`. That
         * looks like a broken model and is really a budget that ran out before
         * the answer started. The prompt still asks for one short sentence, so
         * a non-reasoning model stops long before this.
         */
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.2
        private const val TIMEOUT_MS = 60_000

        /** Cirrascale's INFERENCE_CLOUD_ENDPOINT. */
        const val DEFAULT_BASE_URL = "https://aisuite.cirrascale.com/apis/v2"
    }
}
