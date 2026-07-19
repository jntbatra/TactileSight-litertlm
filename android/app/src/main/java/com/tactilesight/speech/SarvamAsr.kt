package com.tactilesight.speech

import android.util.Log
import com.tactilesight.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech in, via Sarvam (ADR-0012, #9). The user holds the button, asks a
 * question in their own language, and this returns it as **English text** for
 * the VLM.
 *
 * ### Why `/speech-to-text-translate` and not `/speech-to-text`
 *
 * The VLM works in English. `/speech-to-text` returns the question in the
 * language it was asked in, so Hindi speech would need a second `/translate`
 * call to become something the model can answer — two network round trips on a
 * venue network, spent while a blind user stands waiting. The translate variant
 * lands in one:
 *
 * ```
 * मेरे सामने क्या है?  ->  "What is in front of me?"
 * ```
 *
 * ### Why no language is sent
 *
 * The endpoint auto-detects, and that is the right behaviour rather than a
 * shortcut. If the spoken language had to be declared, the language picker
 * would stop being a speech-*out* preference and become something the user must
 * set correctly before they can be understood at all — and someone who cannot
 * see the screen has no way to discover that they got it wrong.
 *
 * Deliberately dependency-free, like [SarvamSpeechIO]: one multipart POST does
 * not justify a library the venue network would have to fetch.
 */
class SarvamAsr(
    private val apiKey: String = BuildConfig.SARVAM_API_KEY,
) {

    /**
     * The question as English text, or null if nothing usable was heard.
     *
     * Null is a normal outcome, not an error: a user holds the button and says
     * nothing, or the hall is too loud. The caller turns that into a
     * description rather than a complaint (#11) — every press yields speech.
     */
    suspend fun transcribe(wav: ByteArray): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "no Sarvam key — cannot transcribe")
            return@withContext null
        }
        if (wav.size < MINIMUM_USEFUL_BYTES) {
            Log.i(TAG, "recording too short (${wav.size} bytes) — treating as no question")
            return@withContext null
        }

        try {
            val response = postMultipart(wav)
            val transcript = response.optString("transcript").trim()
            val language = response.optString("language_code", "?")
            if (transcript.isEmpty()) {
                Log.i(TAG, "empty transcript — heard nothing usable")
                null
            } else {
                Log.i(TAG, "heard ($language): $transcript")
                transcript
            }
        } catch (e: Exception) {
            Log.w(TAG, "transcription failed", e)
            null
        }
    }

    /**
     * Multipart by hand. `HttpURLConnection` has no multipart support and the
     * body is small and fixed-shape, so this is a boundary and three parts
     * rather than a dependency.
     */
    private fun postMultipart(wav: ByteArray): JSONObject {
        val boundary = "----tactilesight$BOUNDARY_SEED"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty(AUTH_HEADER, apiKey)
        }

        try {
            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                out.writeBytes("$MODEL\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"question.wav\"\r\n",
                )
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("Sarvam ASR failed: HTTP ${connection.responseCode} $error")
            }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "SarvamAsr"

        /** Any language in, English out — see the class docs. */
        const val ENDPOINT = "https://api.sarvam.ai/speech-to-text-translate"
        const val AUTH_HEADER = "api-subscription-key"

        /**
         * Measured 2026-07-19 against the live API. `saarika:flash` is
         * deprecated and answers with a redirect notice rather than a
         * transcript; `saaras:v3` also reports `language_probability`, which is
         * worth having when a transcript looks wrong and you need to know
         * whether it misheard the words or the language.
         */
        const val MODEL = "saaras:v3"

        /**
         * Below this there is no speech in the buffer — a fumbled press rather
         * than a question. 16 kHz mono 16-bit is 32 kB per second, so this is
         * about a quarter of a second.
         */
        const val MINIMUM_USEFUL_BYTES = 8_000

        /** Longer than TTS: this uploads audio, and venue uplinks are poor. */
        const val TIMEOUT_MS = 20_000

        /** Fixed, not random: the boundary only has to not appear in a WAV. */
        const val BOUNDARY_SEED = "8f3a1c"
    }
}
