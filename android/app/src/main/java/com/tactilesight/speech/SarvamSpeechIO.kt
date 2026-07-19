package com.tactilesight.speech

import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.tactilesight.BuildConfig
import com.tactilesight.core.Language
import com.tactilesight.core.SpeechIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Speech out via Sarvam cloud TTS (ADR-0012). The VLM describes in English and
 * Sarvam speaks it in the user's language, because 1–2B VLMs are markedly weak
 * in Indic languages.
 *
 * Only *speech* touches Sarvam — camera imagery never leaves the device. State
 * that distinction honestly.
 *
 * Deliberately dependency-free (HttpURLConnection, not OkHttp): one POST does
 * not justify a library the venue network would have to fetch.
 */
class SarvamSpeechIO(
    private val cacheDir: File,
    private val apiKey: String = BuildConfig.SARVAM_API_KEY,
) : SpeechIO {

    override suspend fun speak(text: String, language: Language, translate: Boolean) {
        require(apiKey.isNotBlank()) {
            "Sarvam API key missing — set sarvam.api.key in android/local.properties"
        }
        val wav = withContext(Dispatchers.IO) {
            synthesise(if (translate) translated(text, language) else text, language)
        }
        play(wav)
    }

    /**
     * English in, the user's language out.
     *
     * This step is not optional and its absence is invisible: text-to-speech
     * **synthesises, it does not translate**, so handing it an English sentence
     * with `target_language_code: pa-IN` produces English words in a Punjabi
     * voice. Everything looks correct — the language is passed, the call
     * succeeds, audio plays — and the user simply does not understand it.
     *
     * The VLM answers in English on purpose: small VLMs are markedly weaker in
     * Indic languages, so we translate a good English sentence rather than
     * generate a poor Punjabi one (ADR-0012).
     *
     * A translation failure degrades to the English text rather than to
     * silence — hard rule #4, every press yields speech.
     */
    private fun translated(text: String, language: Language): String {
        if (language == Language.ENGLISH || text.isBlank()) return text

        return try {
            val body = JSONObject().apply {
                put("input", text)
                put("source_language_code", SOURCE_LANGUAGE)
                put("target_language_code", language.sarvamCode)
                put("model", TRANSLATE_MODEL)
            }.toString()

            val json = post(TRANSLATE_ENDPOINT, body)
            json.optString("translated_text").ifBlank { text }
                .also { Log.i(TAG, "translated to ${language.sarvamCode}: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "translation to ${language.sarvamCode} failed — speaking English", e)
            text
        }
    }

    /** One JSON POST to Sarvam, shared by translate and text-to-speech. */
    private fun post(endpoint: String, body: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(AUTH_HEADER, apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("Sarvam $endpoint failed: HTTP ${connection.responseCode} $error")
            }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    /** Returns WAV bytes. Throws on any non-200 or malformed response. */
    private fun synthesise(text: String, language: Language): ByteArray {
        Log.i(TAG, "speaking ${language.sarvamCode} via $MODEL/$SPEAKER")
        val body = JSONObject().apply {
            put("text", text)
            put("target_language_code", language.sarvamCode)
            put("model", MODEL)
            put("speaker", SPEAKER)
        }.toString()

        val json = post(ENDPOINT, body)
        val base64 = json.getJSONArray("audios").getString(0)
        return Base64.decode(base64, Base64.DEFAULT)
    }

    /** Suspends until the utterance finishes — speech must not overlap. */
    private suspend fun play(wav: ByteArray) {
        val file = File.createTempFile("utterance", ".wav", cacheDir)
        try {
            file.writeBytes(wav)
            suspendCancellableCoroutine { continuation ->
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    prepare()
                    start()
                }
                continuation.invokeOnCancellation { player.release() }
            }
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.sarvam.ai/text-to-speech"
        const val AUTH_HEADER = "api-subscription-key"
        // v3 is the current model; v2 and v3-beta are also accepted. Speakers
        // are NOT shared between them — v2's "anushka" is rejected by v3 with
        // "not compatible with model bulbul:v3", so the pair moves together.
        const val TAG = "SarvamSpeechIO"
        const val TRANSLATE_ENDPOINT = "https://api.sarvam.ai/translate"

        /** The VLM always answers in English (ADR-0012). */
        const val SOURCE_LANGUAGE = "en-IN"

        /**
         * Not the default. `mayura:v1` covers 10 languages and rejects the rest
         * with "please switch to sarvam-translate:v1"; this one covers 22.
         */
        const val TRANSLATE_MODEL = "sarvam-translate:v1"
        const val MODEL = "bulbul:v3"
        const val SPEAKER = "ritu"
        const val TIMEOUT_MS = 15_000
    }
}
