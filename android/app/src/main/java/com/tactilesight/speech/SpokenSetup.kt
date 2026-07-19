package com.tactilesight.speech

import android.util.Log
import com.tactilesight.core.Language
import com.tactilesight.core.SpeechIO

/**
 * First run, by ear: ask which language, in a way that excludes nobody.
 *
 * ### The bootstrap problem, and how the prompt solves it
 *
 * A spoken setup that asks *"which language?"* has to ask in **some** language.
 * Open in English and a Hindi-only user hears noise; open in the device locale
 * and there was no point asking. The prompt therefore says it **twice**:
 *
 * ```
 * "Choose language."      (English)
 * "भाषा चुनें।"            (Hindi)
 * ```
 *
 * Whoever is holding the device understands one of them. That is why this is
 * two utterances rather than one clever sentence — and why the list stops at
 * two rather than covering all eleven, which would be forty seconds of prompts
 * before the device does anything at all.
 *
 * ### Two ways to answer, because either alone fails someone
 *
 * The reply resolves through whichever signal is present:
 *
 * 1. **They name it** — "Hindi", "हिंदी", "Punjabi", "Tamil".
 * 2. **They just speak** — anything at all, said in Hindi, and Sarvam reports
 *    `hi-IN`. The words do not have to mean anything.
 *
 * Naming alone would fail a user who does not know the English word for their
 * own language. Detection alone would fail a user who answers in English
 * *because the prompt was in English*. Together they cover both.
 *
 * ### What happens when it fails
 *
 * Nothing is set, English stays, and the app opens normally. A setup that
 * cannot complete must not be able to trap a user outside the app — especially
 * one who cannot see the screen to escape it.
 */
class SpokenSetup(
    private val speech: SpeechIO,
    private val recorder: MicRecorder,
    private val asr: SarvamAsr,
) {

    data class Outcome(val language: Language?, val spokenBack: String)

    /**
     * Ask, listen, resolve. [record] runs the recording for as long as the
     * caller decides to listen — the caller owns timing because it owns the
     * button.
     */
    suspend fun run(record: suspend () -> ByteArray?): Outcome {
        // Both prompts, one after the other. Sarvam speaks one language per
        // call, so this is genuinely two utterances.
        // translate = false: these are written in the language they are spoken
        // in. Translating "भाषा चुनें।" as though it were English produced
        // "Choose a language" - spoken in a Hindi voice, in English words.
        speech.speak(PROMPT_ENGLISH, Language.ENGLISH, translate = false)
        speech.speak(PROMPT_HINDI, Language.HINDI, translate = false)

        val heard = record()?.let { asr.listen(it) }
        if (heard == null) {
            Log.i(TAG, "no answer — leaving the language unset")
            return Outcome(null, NOT_HEARD)
        }

        val chosen = resolve(heard)
        if (chosen == null) {
            Log.i(TAG, "could not resolve '${heard.transcript}' (${heard.languageCode})")
            return Outcome(null, NOT_UNDERSTOOD)
        }

        Log.i(TAG, "language set to ${chosen.sarvamCode} from '${heard.transcript}'")
        return Outcome(chosen, CONFIRMED)
    }

    /**
     * Named language wins over detected one.
     *
     * A user who says "Punjabi" **in English** means Punjabi — taking the
     * detected `en-IN` there would override an explicit instruction with an
     * inference, which is the wrong way round when the two disagree.
     */
    internal fun resolve(heard: SarvamAsr.Heard): Language? =
        named(heard.transcript) ?: heard.languageCode?.let { code ->
            Language.speakable.firstOrNull { it.sarvamCode.equals(code, ignoreCase = true) }
        }

    /**
     * Matches a language by any name a person actually uses for it.
     *
     * The textbook English spelling is often the one nobody says. Reported from
     * testing: **"Bangali" was not recognised while "Bengali" was** — and
     * Bangla/Bangali is what a Bengali speaker calls their own language. The
     * same holds across the list: Oriya for Odia, Panjabi for Punjabi, Tamizh
     * for Tamil, Angrezi for English.
     *
     * Getting this wrong is not a small annoyance. Setup is the first thing the
     * device ever does, and a user who names their language correctly and is
     * told "I did not catch that" learns that the device does not know their
     * language — which is the opposite of what an Indic-first product should
     * teach in its first ten seconds.
     *
     * Matching is on word boundaries rather than substrings, because "hindi"
     * lives inside nothing but "tamil" lives inside "tamilnadu" and a bare
     * `contains` would keep finding surprises.
     */
    private fun named(transcript: String): Language? {
        val said = transcript.lowercase()
        if (said.isBlank()) return null
        val words = said.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }.toSet()

        return Language.speakable.firstOrNull { language ->
            val forms = ALIASES[language].orEmpty() +
                language.name.lowercase() +
                language.displayName.lowercase()
            forms.any { form -> form in words || said.contains(form) }
        }
    }

    private companion object {
        const val TAG = "SpokenSetup"

        const val PROMPT_ENGLISH = "Choose language."
        const val PROMPT_HINDI = "भाषा चुनें।"

        /** Spoken in English — we do not yet know what else they understand. */
        const val NOT_HEARD = "I did not hear you. Staying in English for now."
        const val NOT_UNDERSTOOD = "I did not catch that language. Staying in English for now."
        const val CONFIRMED = "Language set."

        /**
         * What people call these languages, beyond the enum's spelling.
         *
         * Endonyms first — they are what a native speaker reaches for. The
         * transliterations are the common romanisations Sarvam returns when
         * someone says the name in English.
         */
        val ALIASES: Map<Language, Set<String>> = mapOf(
            Language.BENGALI to setOf("bangla", "bangali", "bengoli", "bangaali", "বাংলা"),
            Language.ODIA to setOf("oriya", "odiya", "odissi", "ଓଡ଼ିଆ"),
            Language.PUNJABI to setOf("panjabi", "punjaabi", "pnjabi", "ਪੰਜਾਬੀ"),
            Language.TAMIL to setOf("tamizh", "thamizh", "tamila", "தமிழ்"),
            Language.TELUGU to setOf("telegu", "telgu", "తెలుగు"),
            Language.KANNADA to setOf("kannad", "kanada", "kannadda", "ಕನ್ನಡ"),
            Language.MALAYALAM to setOf("malyalam", "malayaalam", "മലയാളം"),
            Language.MARATHI to setOf("marathee", "mahrati", "मराठी"),
            Language.GUJARATI to setOf("gujrati", "gujaraati", "ગુજરાતી"),
            Language.HINDI to setOf("hindustani", "हिंदी", "हिन्दी"),
            Language.ENGLISH to setOf("angrezi", "angreji", "inglish", "अंग्रेज़ी"),
        )
    }
}
