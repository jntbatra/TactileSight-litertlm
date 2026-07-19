package com.tactilesight.frame

import android.util.Base64
import android.util.Log
import com.tactilesight.core.CaptureUnavailable
import com.tactilesight.core.DepthMap
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The real band: an Arduino UNO Q serving an Orbbec Astra Pro over WiFi.
 *
 * This is the source a user actually wears. Bundled captures are a development
 * scaffold and the phone camera is the standalone fallback; this one measures
 * the room the user is standing in.
 *
 * ### The two-channel dance, and why it looks backwards
 *
 * A capture takes an HTTP POST *and* a WebSocket, in that order but with the
 * socket opened first:
 *
 * 1. open `ws://host:8083` and complete the handshake
 * 2. `POST http://host:8081/capture`
 * 3. read one bundle off the socket
 *
 * The board broadcasts the bundle to whoever is *already* connected at the
 * moment the capture completes — it does not queue, and it does not answer the
 * POST with the payload. Connecting after the POST is therefore a race the app
 * loses often enough to look intermittent, which is exactly the kind of bug
 * that survives a demo and fails in front of judges. Opening first costs one
 * round trip and removes the race entirely.
 *
 * ### Degrading, rather than failing
 *
 * `Orchestrator.onPress` turns a thrown capture into a spoken apology, so every
 * press still produces speech (the app's central promise). This class therefore
 * throws with a *specific* message rather than returning a half-frame, and the
 * two partial-bundle cases are handled deliberately:
 *
 * - **No RGB.** The board sends `rgb_b64: ""` when its RGB worker misses the
 *   1-second deadline — a documented path in `haptic_depth_server.py`, and one
 *   observed live for hours when the board's tmpfs directory vanished. There is
 *   nothing for the VLM to describe, and handing it a blank image would get a
 *   confident description of nothing, so the capture is retried once and then
 *   raises [CaptureUnavailable] with a sentence naming the band. A blank
 *   preview is the one outcome ruled out: it looks like the app broke, and a
 *   blind user cannot see that the picture was missing.
 * - **No depth.** The board sends `depth_b64: ""` if `pypng` is missing or the
 *   encode failed. Colour still describes the scene, so this yields a frame
 *   with [DepthMap.NONE] and the pipeline simply speaks no numbers. That is the
 *   ADR-0013 rule holding: better to describe without distances than to invent
 *   one.
 */
class BandCaptureSource(
    /** Where the band is. Persisted, because DHCP moves it — see `Settings`. */
    private val address: () -> String,
) : FrameSource {

    override suspend fun capture(): Frame = withContext(Dispatchers.IO) {
        val host = address().trim().ifBlank { error("No band address set") }

        // One retry, and only for a missing colour frame.
        //
        // That failure is usually transient: the board's RGB worker polls at
        // 20 Hz against a 1 s deadline and misses it when a capture lands in
        // the wrong slot. A second attempt costs about a second and usually
        // succeeds.
        //
        // Nothing else is retried. A refused connection or a dead board fails
        // again identically, and a second timeout would double the silence
        // between the press and the spoken answer for no gain.
        try {
            attempt(host)
        } catch (first: MissingColourFrame) {
            Log.w(TAG, "no colour frame — retrying once")
            try {
                attempt(host)
            } catch (second: MissingColourFrame) {
                // Deliberately *not* the last good frame. A stale scene
                // described as the present one is the confident-wrong failure
                // this project exists to prevent: the user would be told about
                // a room they have already walked out of, with no way to know.
                throw CaptureUnavailable(
                    spokenMessage = NO_COLOUR_SPOKEN,
                    detail = "band sent no colour frame twice: ${second.message}",
                )
            }
        }
    }

    private fun attempt(host: String): Frame =
        BandSocket.connect(host, WEBSOCKET_PORT, TIMEOUT_MS).use { socket ->
            trigger(host)
            parse(String(socket.readMessage(), Charsets.UTF_8), host)
        }

    /** The bundle arrived but carried no JPEG. Retried once, then spoken. */
    private class MissingColourFrame(message: String) : Exception(message)

    /**
     * Ask the board to grab a frame.
     *
     * The board answers `{"ok": false, "error": ...}` when the camera is not
     * streaming — a real state, seen live when the Astra is attached but the
     * USB port is in device mode. Surfacing its wording beats a generic
     * timeout, because the fix ("switch to host mode") is in that string.
     */
    private fun trigger(host: String) {
        val connection = (URL("http://$host:$HTTP_PORT/capture").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                outputStream.close()
            }

        try {
            val body = if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else {
                error("band returned HTTP ${connection.responseCode} from /capture")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                error("band refused the capture: ${json.optString("error", body)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(message: String, host: String): Frame {
        val bundle = JSONObject(message)

        // The board broadcasts {"error": ...} instead of a bundle for a camera
        // that is not ready or a depth frame that timed out.
        bundle.optString("error").takeIf { it.isNotBlank() }?.let {
            error("band could not capture: $it")
        }

        val rgb = decode(bundle.optString("rgb_b64"))
        if (rgb.isEmpty()) {
            throw MissingColourFrame("rgb_b64 was empty — the board's RGB worker did not answer")
        }

        val depth = decode(bundle.optString("depth_b64")).let { bytes ->
            if (bytes.isEmpty()) {
                Log.w(TAG, "band sent no depth — describing without distances")
                DepthMap.NONE
            } else {
                DepthPngReader.readDepthMap(bytes)
            }
        }

        // `ts` is the board's clock in seconds. Its clock is not the phone's and
        // may be years out on a board with no RTC, so the frame is stamped with
        // the phone's time: everything downstream compares this against the
        // phone's own now.
        return Frame(
            rgbJpeg = rgb,
            depthMillimetres = depth,
            capturedAtMillis = System.currentTimeMillis(),
            sourceId = "band@$host",
        )
    }

    private fun decode(base64: String): ByteArray =
        if (base64.isBlank()) ByteArray(0) else Base64.decode(base64, Base64.DEFAULT)

    private companion object {
        const val TAG = "BandCaptureSource"
        const val HTTP_PORT = 8081
        const val WEBSOCKET_PORT = 8083

        /**
         * Generous enough for the board's own 3 s depth wait plus its 1 s RGB
         * poll, tight enough that a press never feels hung. A dead network must
         * reach the spoken apology, not sit there.
         */
        const val TIMEOUT_MS = 8_000

        /**
         * Said when the band answers but has no picture, after a retry.
         *
         * Names the band, because that is where the fault is and the user
         * cannot see that the image was missing. "Sorry, I could not see that"
         * sounds like the description failed and invites pressing again against
         * a fault that will repeat; this points at the thing to check.
         */
        const val NO_COLOUR_SPOKEN =
            "The band is not sending pictures. Please check the band's camera."
    }
}
