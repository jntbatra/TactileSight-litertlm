package com.tactilesight.core

import android.content.Context

/**
 * The few choices that outlive a launch: where to describe, which server, and
 * which destination describes the frame.
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

    /**
     * Whether the private server speaks OpenAI `chat/completions` (LM Studio,
     * llama-server, vLLM) rather than our `/v1/describe` contract.
     *
     * Set by the Check button, which probes both. Remembered because the wire
     * format has to be decided before a press, not during one.
     */
    var privateServerIsOpenAi: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE_OPENAI, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVATE_OPENAI, value).apply()

    /** Which model the private server should run, when it is OpenAI-style. */
    var privateServerModel: String
        get() = prefs.getString(KEY_PRIVATE_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PRIVATE_MODEL, value.trim()).apply()

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
     * The spoken language. Chosen at setup and persisted — it is the user's
     * language, not a per-session choice.
     */
    var language: Language
        get() = Language.fromCode(prefs.getString(KEY_LANGUAGE, null))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.sarvamCode).apply()

    fun urlFor(mode: BrainMode): String = when (mode) {
        BrainMode.PRIVATE_SERVER -> privateServerUrl
        BrainMode.ON_DEVICE_NPU -> ""
    }

    fun setUrlFor(mode: BrainMode, url: String) {
        when (mode) {
            BrainMode.PRIVATE_SERVER -> privateServerUrl = url
            BrainMode.ON_DEVICE_NPU -> Unit
        }
    }

    private companion object {
        const val FILE = "tactilesight"
        const val KEY_MODE = "brain_mode"
        const val KEY_PRIVATE_URL = "private_server_url"
        const val KEY_PROMPT = "custom_prompt"
        const val KEY_LANGUAGE = "language"
        const val KEY_PRIVATE_OPENAI = "private_server_is_openai"
        const val KEY_PRIVATE_MODEL = "private_server_model"

        /** The laptop tier's default port, per server/README. */
        const val DEFAULT_PRIVATE_URL = "http://192.168.1.100:8000"

        /** Cirrascale's published Imagine API endpoint. */
    }
}
