package com.tactilesight.core

/**
 * Where the frame is described — the one choice that decides whether camera
 * imagery leaves the device.
 *
 * [sendsImageryOffDevice] is the whole point of this type. Privacy mode is a
 * hard rule (#7): it must *actually* block the cloud, not relabel the UI. So
 * the block is expressed here, as a property of the destination, and enforced
 * where the brain is resolved — not in a listener that a future refactor could
 * quietly drop.
 *
 * The distinction between [PRIVATE_SERVER] and [CLOUD] is who owns the machine,
 * and it is a real one: a laptop under the table is not a third party. Be
 * careful how far that claim is stretched, though — reaching that laptop over a
 * Cloudflare tunnel terminates TLS at Cloudflare's edge, so the *destination*
 * is private while the *path* is not. On a LAN address it genuinely is both.
 */
enum class BrainMode(
    val displayName: String,
    val sendsImageryOffDevice: Boolean,
) {
    /** Nothing leaves the phone. NPU where a QAIRT bundle is staged, else GPU. */
    ON_DEVICE("On-device", sendsImageryOffDevice = false),

    /** Our own machine — the laptop tier, reached by LAN address or tunnel. */
    PRIVATE_SERVER("Private server", sendsImageryOffDevice = true),

    /** Qualcomm Cloud AI 100. Someone else's hardware, by definition. */
    CLOUD("Cloud AI 100", sendsImageryOffDevice = true);

    /** Whether this destination is selectable while privacy mode is on. */
    fun isAllowedUnderPrivacy(privacyOn: Boolean): Boolean =
        !privacyOn || this != CLOUD
}
