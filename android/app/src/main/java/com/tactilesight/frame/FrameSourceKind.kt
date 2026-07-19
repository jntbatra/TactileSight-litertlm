package com.tactilesight.frame

/**
 * Where frames come from.
 *
 * The live band entry was `WEBRTC` while it was unbuilt, because ADR-0009
 * expected a WebRTC data channel. The board that shipped does not serve one: it
 * broadcasts a JSON bundle over a plain WebSocket (`ws://<board>:8083`) in
 * response to `POST /capture`. This name follows the wire rather than the plan
 * — a picker entry reading "WebRTC" for a link carrying no WebRTC is the kind
 * of small lie that costs an hour when the link misbehaves at a venue.
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

    /**
     * The real band over WiFi — see [BandCaptureSource].
     *
     * `measuresDistance` is true because the band carries an Astra Pro, but a
     * given frame can still arrive without depth if the board's encoder failed.
     * That is per-frame and handled by `Frame.hasDepth`; this flag answers the
     * different question of what the *source* is capable of, which is what the
     * UI has to tell the user before they press.
     */
    BAND("Band over WiFi", available = true, measuresDistance = true),
}
