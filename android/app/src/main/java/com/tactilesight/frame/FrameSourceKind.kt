package com.tactilesight.frame

/**
 * The frame sources the dev picker offers. WebRTC is listed but disabled — it
 * is visible so the demo path is legible, and disabled because #19 has not
 * built it yet. Showing it greyed is more honest than hiding it.
 */
enum class FrameSourceKind(
    val displayName: String,
    val available: Boolean,
) {
    BUNDLED("Bundled captures", available = true),
    WEBRTC("WebRTC from band", available = false),
}
