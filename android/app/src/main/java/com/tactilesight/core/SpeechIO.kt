package com.tactilesight.core

/**
 * Speech out (and, from issue #9, speech in). Sarvam cloud today; Sarvam Edge
 * is the documented on-device upgrade behind this seam (ADR-0012).
 *
 * There is deliberately no offline TTS fallback in the MVP — losing the network
 * leaves the app silent rather than degraded. Accepted, recorded risk.
 */
interface SpeechIO {

    /** Speak [text]. Suspends until the utterance finishes. */
    suspend fun speak(text: String, language: Language = Language.ENGLISH)
}

/**
 * Every language Sarvam's text-to-speech accepts, so a judge can switch live —
 * the VLM always works in English, so a language is a parameter, not an
 * integration (ADR-0012, #10).
 *
 * This list is not from the docs, which 404 and are JavaScript-rendered anyway.
 * It is what the API itself reports as valid, obtained by sending a deliberately
 * invalid code and reading the validation error (2026-07-18). When Sarvam adds a
 * language, the same probe is how to find out:
 *
 * ```bash
 * curl -s -X POST https://api.sarvam.ai/text-to-speech \
 *   -H "api-subscription-key: $KEY" -H "Content-Type: application/json" \
 *   -d '{"text":"t","target_language_code":"zz-ZZ","model":"bulbul:v3"}'
 * ```
 *
 * Names are written in each language's own script: a picker that offers
 * "Kannada" in Latin letters is not much use to someone who reads Kannada.
 *
 * English, Hindi and Punjabi come first because they are the demo path and
 * should be reachable without scrolling; the rest follow alphabetically.
 */
enum class Language(val sarvamCode: String, val displayName: String) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "हिन्दी"),
    PUNJABI("pa-IN", "ਪੰਜਾਬੀ"),
    ASSAMESE("as-IN", "অসমীয়া"),
    BENGALI("bn-IN", "বাংলা"),
    BODO("brx-IN", "बड़ो"),
    DOGRI("doi-IN", "डोगरी"),
    GUJARATI("gu-IN", "ગુજરાતી"),
    KANNADA("kn-IN", "ಕನ್ನಡ"),
    KASHMIRI("ks-IN", "کٲشُر"),
    KONKANI("kok-IN", "कोंकणी"),
    MAITHILI("mai-IN", "मैथिली"),
    MALAYALAM("ml-IN", "മലയാളം"),
    MANIPURI("mni-IN", "ꯃꯤꯇꯩꯂꯣꯟ"),
    MARATHI("mr-IN", "मराठी"),
    NEPALI("ne-IN", "नेपाली"),
    ODIA("od-IN", "ଓଡ଼ିଆ"),
    SANSKRIT("sa-IN", "संस्कृतम्"),
    SANTALI("sat-IN", "ᱥᱟᱱᱛᱟᱲᱤ"),
    SINDHI("sd-IN", "سنڌي"),
    TAMIL("ta-IN", "தமிழ்"),
    TELUGU("te-IN", "తెలుగు"),
    URDU("ur-IN", "اردو");

    companion object {
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.sarvamCode == code } ?: ENGLISH
    }
}
