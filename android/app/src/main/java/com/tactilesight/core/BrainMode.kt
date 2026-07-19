package com.tactilesight.core

/**
 * Where the frame is described — the one choice that decides whether camera
 * imagery leaves the device.
 *
 * [sendsImageryOffDevice] is the whole point of this type: it states, per
 * destination, whether the frame leaves the phone.
 *
 * **The privacy toggle was removed 2026-07-19, with the cloud tier.** It is not
 * a regression. A switch is worth having when the destination does not already
 * say what it does — with a third-party cloud in the picker, "on-device" and
 * "never send imagery anywhere" were genuinely different statements. Now the
 * choice is the phone or our own laptop, and selecting one *is* the decision;
 * a second control on top of it can only disagree with the first.
 *
 * This flag stays, and is the thing to reason from. The UI uses it to know an
 * endpoint is needed, and a third-party tier added later must declare it —
 * which is the moment to ask whether the toggle should come back.
 *
 * **Qualcomm Cloud AI 100 was removed 2026-07-19.** It never had a vision model
 * deployed, so it could not describe a frame; keeping it in the picker meant a
 * selectable mode that was guaranteed to fail. `CloudBrain` survives it and is
 * not dead code — it is the OpenAI-compatible client [PRIVATE_SERVER] uses.
 *
 * That leaves exactly one destination that sends imagery off-device, and it is
 * our own machine. Say that accurately rather than generously: a laptop under
 * the table is not a stranger, but the frame *is* leaving the phone. And the
 * claim is about the destination, not the path — a Cloudflare tunnel to that
 * laptop terminates TLS at Cloudflare's edge, so the destination stays private
 * while the path does not. On a LAN address it is genuinely both.
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

}
