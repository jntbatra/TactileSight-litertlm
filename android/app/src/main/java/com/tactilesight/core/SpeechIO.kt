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
 * Every Sarvam language is exposed so a judge can switch live — the VLM always
 * works in English, so a language is a parameter, not an integration (ADR-0012).
 * The demo path is Punjabi + Hindi + English; the full list arrives with #10.
 */
enum class Language(val sarvamCode: String, val displayName: String) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "हिन्दी"),
    PUNJABI("pa-IN", "ਪੰਜਾਬੀ"),
}
