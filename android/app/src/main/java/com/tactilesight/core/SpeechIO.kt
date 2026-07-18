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
enum class Language(
    val sarvamCode: String,
    val displayName: String,
    /**
     * Whether Sarvam will actually *speak* it on our account.
     *
     * Translation and speech have different coverage, and the gap is not a
     * model choice — `sarvam-translate:v1` renders 22 languages, but
     * `bulbul:v3` answers 12 of them with "please request beta access". A
     * language we can translate but not speak is worse than one we do not
     * offer: it translates, then fails at the very last step, and the user
     * hears a network error instead of their answer.
     *
     * Measured against the live API on 2026-07-18 (see the probe above); flip
     * these to true if beta access is granted.
     */
    val isSpeakable: Boolean,
) {
    ENGLISH("en-IN", "English", isSpeakable = true),
    HINDI("hi-IN", "हिन्दी", isSpeakable = true),
    PUNJABI("pa-IN", "ਪੰਜਾਬੀ", isSpeakable = true),
    BENGALI("bn-IN", "বাংলা", isSpeakable = true),
    GUJARATI("gu-IN", "ગુજરાતી", isSpeakable = true),
    KANNADA("kn-IN", "ಕನ್ನಡ", isSpeakable = true),
    MALAYALAM("ml-IN", "മലയാളം", isSpeakable = true),
    MARATHI("mr-IN", "मराठी", isSpeakable = true),
    ODIA("od-IN", "ଓଡ଼ିଆ", isSpeakable = true),
    TAMIL("ta-IN", "தமிழ்", isSpeakable = true),
    TELUGU("te-IN", "తెలుగు", isSpeakable = true),

    // Translatable, but bulbul:v3 refuses them without beta access.
    ASSAMESE("as-IN", "অসমীয়া", isSpeakable = false),
    BODO("brx-IN", "बड़ो", isSpeakable = false),
    DOGRI("doi-IN", "डोगरी", isSpeakable = false),
    KASHMIRI("ks-IN", "کٲشُر", isSpeakable = false),
    KONKANI("kok-IN", "कोंकणी", isSpeakable = false),
    MAITHILI("mai-IN", "मैथिली", isSpeakable = false),
    MANIPURI("mni-IN", "ꯃꯤꯇꯩꯂꯣꯟ", isSpeakable = false),
    NEPALI("ne-IN", "नेपाली", isSpeakable = false),
    SANSKRIT("sa-IN", "संस्कृतम्", isSpeakable = false),
    SANTALI("sat-IN", "ᱥᱟᱱᱛᱟᱲᱤ", isSpeakable = false),
    SINDHI("sd-IN", "سنڌي", isSpeakable = false),
    URDU("ur-IN", "اردو", isSpeakable = false);

    companion object {
        /** What the picker offers: only what the user will actually hear. */
        val speakable: List<Language> get() = entries.filter { it.isSpeakable }

        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.sarvamCode == code } ?: ENGLISH
    }
}
