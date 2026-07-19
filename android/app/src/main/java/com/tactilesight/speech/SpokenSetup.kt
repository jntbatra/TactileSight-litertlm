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
    private fun resolve(heard: SarvamAsr.Heard): Language? =
        named(heard.transcript) ?: heard.languageCode?.let { code ->
            Language.speakable.firstOrNull { it.sarvamCode.equals(code, ignoreCase = true) }
        }

    /** Matches a language by its English name or its own name. */
    private fun named(transcript: String): Language? {
        val said = transcript.lowercase()
        if (said.isBlank()) return null
        return Language.speakable.firstOrNull { language ->
            said.contains(language.name.lowercase()) ||
                said.contains(language.displayName.lowercase())
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
    }
}
