package com.tactilesight.brain

import android.util.Base64
import android.util.Log
import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain
import com.tactilesight.frame.DepthCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Describes a frame on a server — our laptop, or Qualcomm Cloud AI 100.
 *
 * Both are the same code path; only [baseUrl] differs. That is deliberate: the
 * demo endpoint is a Cloudflare tunnel whose URL changes every time it
 * restarts, so the destination has to be data, not a build-time constant.
 *
 * Speaks the frozen server contract (TEAM.md):
 * ```
 * POST /v1/describe {mode, question, language, image_b64} -> {spoken, rich, confident}
 * ```
 * base64 JSON, not multipart. The two tracks are built independently against
 * this, in different languages, so it is not ours to renegotiate here.
 *
 * The prompt lives **server-side** and is not duplicated in this class. A
 * second prompt means the phone silently behaves differently from the cloud
 * tier, which has already cost this project hours.
 *
 * Dependency-free like [com.tactilesight.speech.SarvamSpeechIO], for the same
 * reason: one POST does not justify a library the venue network must fetch.
 */
class CloudBrain(
    private val baseUrl: String,
    override val name: String,
    private val language: String = "en",
) : SemanticBrain {

    override suspend fun describe(frame: Frame, question: String?): Answer =
        withContext(Dispatchers.IO) {
            // The same crop the on-device brain sees, so switching destination
            // does not silently change what the model is looking at.
            val jpeg = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg)

            val body = JSONObject().apply {
                put("mode", if (question.isNullOrBlank()) MODE_DESCRIBE else MODE_QUERY)
                put("question", question ?: JSONObject.NULL)
                put("language", language)
                put("image_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }.toString()

            val endpoint = "${baseUrl.trimEnd('/')}$DESCRIBE_PATH"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val detail = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    error("describe failed: HTTP ${connection.responseCode} $detail")
                }

                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val spoken = json.optString(FIELD_SPOKEN).trim()
                Log.i(TAG, "answer: $spoken")

                // A blank or unconfident answer is degraded to spoken fallback
                // by the Orchestrator rather than becoming a dead press.
                Answer(spoken, confident = json.optBoolean(FIELD_CONFIDENT, true))
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TAG = "CloudBrain"
        const val DESCRIBE_PATH = "/v1/describe"
        const val MODE_DESCRIBE = "DESCRIBE"
        const val MODE_QUERY = "QUERY"
        const val FIELD_SPOKEN = "spoken"
        const val FIELD_CONFIDENT = "confident"

        /** Generous: a cold cloud backend compiles its model on first request. */
        const val TIMEOUT_MS = 60_000
    }
}
