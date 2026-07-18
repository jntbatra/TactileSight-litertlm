package com.tactilesight.core

/**
 * Where the frame is described — the one choice that decides whether camera
 * imagery leaves the device.
 *
 * [sendsImageryOffDevice] is the whole point of this type. Privacy mode is a
 * hard rule (#7): it must *actually* block imagery leaving the phone, not
 * relabel the UI. So the block is expressed here, as a property of the
 * destination, and enforced where the brain is resolved — not in a listener
 * that a future refactor could quietly drop.
 *
 * **Qualcomm Cloud AI 100 was removed 2026-07-19.** It never had a vision model
 * deployed, so it could not describe a frame; keeping it in the picker meant a
 * selectable mode that was guaranteed to fail. `CloudBrain` survives it and is
 * not dead code — it is the OpenAI-compatible client [PRIVATE_SERVER] uses.
 *
 * That leaves one destination that sends imagery off-device, and privacy mode
 * now blocks it. Reaching our own laptop is a weaker leak than a third party's
 * cloud — a machine under the table is not a stranger — but it is still the
 * frame leaving the phone, and "private" has to mean the plain thing to a user
 * who cannot see where their camera is pointing. (Worth knowing either way: a
 * Cloudflare tunnel to that laptop terminates TLS at Cloudflare's edge, so the
 * *destination* is private while the *path* is not. On a LAN address it is
 * genuinely both.)
 */
enum class BrainMode(
    val displayName: String,
    val sendsImageryOffDevice: Boolean,
) {
    /**
     * Nothing leaves the phone. QAIRT on the Hexagon NPU — the only on-device
     * path we ship, because it is an order of magnitude ahead of the rest:
     *
     * ```
     * qairt / NPU       260 ms ttft   1287 tok/s prefill   load  7.2 s
     * llama_cpp / NPU  2688 ms        142 tok/s            load 29.2 s
     * llama_cpp / GPU  3417 ms         93 tok/s            load 29.0 s
     * llama_cpp/HYBRID 4087 ms         94 tok/s            load 31.2 s
     * ```
     *
     * The GGUF paths are **not** offered as choices — a picker entry that is
     * 13× slower is a way to make the demo worse by accident. They remain as an
     * automatic fallback when no QAIRT bundle is staged, so a device without
     * one still speaks (hard rule #4) and reports which engine answered.
     */
    ON_DEVICE_NPU("On-device", sendsImageryOffDevice = false),

    /** Our own machine — the laptop tier, reached by LAN address or tunnel. */
    PRIVATE_SERVER("Private server", sendsImageryOffDevice = true);

    /**
     * Whether this destination is selectable while privacy mode is on.
     *
     * Derived from [sendsImageryOffDevice] rather than naming a mode, so adding
     * a destination cannot accidentally add a privacy hole: a new mode is
     * blocked by default unless it declares that the frame stays put.
     */
    fun isAllowedUnderPrivacy(privacyOn: Boolean): Boolean =
        !privacyOn || !sendsImageryOffDevice
}
