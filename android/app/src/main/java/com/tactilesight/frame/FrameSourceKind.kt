package com.tactilesight.frame

/**
 * Where frames come from. WebRTC is listed but disabled — it is visible so the
 * demo path is legible, and disabled because #19 has not built it yet. Showing
 * it greyed is more honest than hiding it.
 */
enum class FrameSourceKind(
    val displayName: String,
    val available: Boolean,
    /**
     * Whether frames from this source carry depth, and therefore whether the
     * spoken answer can contain a distance at all.
     *
     * Not cosmetic. It is the difference between "what is around me" and "how
     * far away is it", and the UI has to say which one the user is getting —
     * silently dropping every distance would look like the feature broke.
     */
    val measuresDistance: Boolean,
) {
    BUNDLED("Bundled captures", available = true, measuresDistance = true),

    /**
     * The phone alone: no band, no depth sensor, therefore no spoken numbers.
     * See [PhoneCameraSource] for why that is a rule rather than a limitation
     * we intend to work around.
     */
    PHONE_CAMERA("Phone camera", available = true, measuresDistance = false),

    WEBRTC("WebRTC from band", available = false, measuresDistance = true),
}
