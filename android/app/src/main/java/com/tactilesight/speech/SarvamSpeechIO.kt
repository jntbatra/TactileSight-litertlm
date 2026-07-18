package com.tactilesight.speech

import android.media.MediaPlayer
import android.util.Base64
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

    override suspend fun speak(text: String, language: Language) {
        require(apiKey.isNotBlank()) {
            "Sarvam API key missing — set sarvam.api.key in android/local.properties"
        }
        val wav = withContext(Dispatchers.IO) { synthesise(text, language) }
        play(wav)
    }

    /** Returns WAV bytes. Throws on any non-200 or malformed response. */
    private fun synthesise(text: String, language: Language): ByteArray {
        val body = JSONObject().apply {
            put("text", text)
            put("target_language_code", language.sarvamCode)
            put("model", MODEL)
            put("speaker", SPEAKER)
        }.toString()

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
                error("Sarvam TTS failed: HTTP ${connection.responseCode} $error")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val base64 = json.getJSONArray("audios").getString(0)
            return Base64.decode(base64, Base64.DEFAULT)
        } finally {
            connection.disconnect()
        }
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
        const val MODEL = "bulbul:v2"
        const val SPEAKER = "anushka"
        const val TIMEOUT_MS = 15_000
    }
}
