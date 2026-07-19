package com.tactilesight.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Holds the microphone open while the button is held (#9, ADR-0011).
 *
 * `AudioRecord` rather than `MediaRecorder`, because the button governs the
 * recording: we must be able to stop on release and have the bytes *now*, not
 * wait for a file to be finalised. It also gives raw PCM, which is what the WAV
 * header below needs.
 *
 * 16 kHz mono 16-bit is what Sarvam's models are trained on and is plenty for
 * speech; 44.1 kHz would triple the upload for no accuracy on a venue network.
 */
class MicRecorder {

    private var record: AudioRecord? = null
    private var buffer: ByteArrayOutputStream? = null
    private var reader: Thread? = null

    @Volatile
    private var recording = false

    val isRecording get() = recording

    /**
     * Begin capturing. Returns false if the mic could not be opened — the
     * caller must still speak something (hard rule #4), so this is a value to
     * check rather than an exception to catch.
     *
     * Requires RECORD_AUDIO; the caller checks the permission.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            Log.w(TAG, "no usable buffer size for ${SAMPLE_RATE}Hz")
            return false
        }

        return try {
            // VOICE_RECOGNITION, not MIC: it asks the platform for the
            // noise-suppressed path tuned for speech, which is exactly the
            // situation here - a person talking in a loud hall.
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                minimum * BUFFER_MULTIPLE,
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord did not initialise")
                audio.release()
                return false
            }

            val sink = ByteArrayOutputStream()
            record = audio
            buffer = sink
            recording = true
            audio.startRecording()

            reader = Thread {
                val chunk = ByteArray(minimum)
                while (recording) {
                    val read = audio.read(chunk, 0, chunk.size)
                    if (read > 0) synchronized(sink) { sink.write(chunk, 0, read) }
                }
            }.also { it.start() }

            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start recording", e)
            stop()
            false
        }
    }

    /**
     * Stop and return the recording as a WAV, or null if nothing was captured.
     * Safe to call when not recording.
     */
    fun stop(): ByteArray? {
        if (!recording && record == null) return null
        recording = false

        try {
            reader?.join(READER_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        reader = null

        record?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() on a record that was not recording", e)
            }
            it.release()
        }
        record = null

        val pcm = buffer?.let { synchronized(it) { it.toByteArray() } }
        buffer = null
        return pcm?.takeIf { it.isNotEmpty() }?.let(::asWav)
    }

    /**
     * Wrap raw PCM in a 44-byte WAV header.
     *
     * Sarvam accepts a `.wav` upload, and a `.wav` that is actually headerless
     * PCM is rejected without a useful message — the request looks fine and the
     * transcript comes back empty, which reads as "it heard nothing" rather
     * than "the file was malformed". Worth building properly once.
     */
    private fun asWav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                                   // PCM subchunk size
        header.putShort(1)                                  // PCM, uncompressed
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)

        return header.array() + pcm
    }

    private companion object {
        const val TAG = "MicRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_BYTES = 44

        /** Headroom so a slow read cannot drop the start of a question. */
        const val BUFFER_MULTIPLE = 4

        /** Long enough for the reader to drain, short enough not to stall a release. */
        const val READER_JOIN_MS = 500L
    }
}
