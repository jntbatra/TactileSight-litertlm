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
    private val apiKey: String = BuildConfig.CIRRASCALE_API_KEY,
) : SemanticBrain {

    override val name: String = "Cloud AI 100 ($model)"

    override suspend fun describe(frame: Frame, question: String?): Answer =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) {
                "Cirrascale key missing — set cirrascale.api.key in android/local.properties"
            }

            // The same crop every other brain sees, so the destination does not
            // change what the model is looking at.
            val jpeg = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg)
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(jpeg, Base64.NO_WRAP)

            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", VlmPrompt.forRequest(question)))
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

            val endpoint = "${baseUrl.trimEnd('/')}$COMPLETIONS_PATH"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
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

    companion object {
        private const val TAG = "ImagineBrain"
        private const val COMPLETIONS_PATH = "/chat/completions"
        private const val MAX_TOKENS = 64
        private const val TEMPERATURE = 0.2
        private const val TIMEOUT_MS = 60_000

        /** Cirrascale's INFERENCE_CLOUD_ENDPOINT. */
        const val DEFAULT_BASE_URL = "https://aisuite.cirrascale.com/apis/v2"
    }
}
