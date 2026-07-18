package com.tactilesight.core

import android.content.Context

/**
 * The few choices that outlive a launch: where to describe, which server, and
 * whether privacy mode is on.
 *
 * These persist because re-typing a tunnel URL on a phone keyboard at a demo
 * is exactly the kind of friction that makes a working system look broken.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var mode: BrainMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { saved -> BrainMode.entries.firstOrNull { it.name == saved } }
            ?: BrainMode.ON_DEVICE_NPU
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Our own machine — a LAN address, or a tunnel to it. */
    var privateServerUrl: String
        get() = prefs.getString(KEY_PRIVATE_URL, DEFAULT_PRIVATE_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_PRIVATE_URL, value.trim()).apply()

    /** Qualcomm Cloud AI 100 — Cirrascale's INFERENCE_CLOUD_ENDPOINT. */
    var cloudUrl: String
        get() = prefs.getString(KEY_CLOUD_URL, DEFAULT_CLOUD_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_URL, value.trim()).apply()

    /**
     * Which model the cloud serves. Editable because it is not ours to pin —
     * `GET /v2/models` is the authority, and what is deployed there can change
     * without warning.
     */
    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /**
     * A prompt typed in the app, overriding [com.tactilesight.brain.VlmPrompt].
     * Blank means "use the built-in one".
     *
     * A dev affordance: the wording is load-bearing and tuning it by rebuilding
     * is slow enough that it does not get done. Note this deliberately does
     * **not** apply to the private-server mode — there the prompt lives
     * server-side by contract, and a second prompt on the phone is exactly the
     * drift TEAM.md forbids.
     */
    var customPrompt: String
        get() = prefs.getString(KEY_PROMPT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROMPT, value).apply()

    /**
     * When on, imagery must not leave the device to a third party (hard rule
     * #7). Enforced in brain resolution, not in the UI — see [effectiveMode].
     */
    var privacyMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVACY, value).apply()

    /**
     * The mode that will actually be used. Privacy mode forces anything it
     * forbids back to [BrainMode.ON_DEVICE_NPU] rather than failing the press,
     * because hard rule #4 says every press yields speech.
     *
     * Resolving it here means a stale saved preference — privacy switched on
     * while CLOUD was selected, then a restart — cannot leak a frame.
     */
    val effectiveMode: BrainMode
        get() = mode.takeIf { it.isAllowedUnderPrivacy(privacyMode) } ?: BrainMode.ON_DEVICE_NPU

    fun urlFor(mode: BrainMode): String = when (mode) {
        BrainMode.PRIVATE_SERVER -> privateServerUrl
        BrainMode.CLOUD -> cloudUrl
        BrainMode.ON_DEVICE_NPU, BrainMode.ON_DEVICE_GPU -> ""
    }

    fun setUrlFor(mode: BrainMode, url: String) {
        when (mode) {
            BrainMode.PRIVATE_SERVER -> privateServerUrl = url
            BrainMode.CLOUD -> cloudUrl = url
            BrainMode.ON_DEVICE_NPU, BrainMode.ON_DEVICE_GPU -> Unit
        }
    }

    private companion object {
        const val FILE = "tactilesight"
        const val KEY_MODE = "brain_mode"
        const val KEY_PRIVATE_URL = "private_server_url"
        const val KEY_CLOUD_URL = "cloud_url"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val KEY_PRIVACY = "privacy_mode"
        const val KEY_PROMPT = "custom_prompt"

        /** The laptop tier's default port, per server/README. */
        const val DEFAULT_PRIVATE_URL = "http://192.168.1.100:8000"

        /** Cirrascale's published Imagine API endpoint. */
        const val DEFAULT_CLOUD_URL = "https://aisuite.cirrascale.com/apis/v2"
    }
}
