package com.tactilesight.core

import android.util.Log

/**
 * Press in, speech out. Holds the three seams together and owns exactly one
 * invariant: **every press yields speech** (ADR-0011). A dead press — silence
 * with no explanation — is the one outcome a blind user cannot recover from,
 * so capture failure, brain failure and model failure all degrade to a spoken
 * sentence rather than nothing.
 *
 * Pure logic, no Android framework beyond logging: unit-tested with fakes, no
 * hardware and no model.
 */
class Orchestrator(
    private val frames: FrameSource,
    /**
     * Resolved per press, not captured once. The resident brain changes when
     * the user switches destination (on-device / private server / cloud), and
     * a reference taken at construction would keep describing on the old one
     * — including, after privacy mode is switched on, a cloud brain that must
     * no longer see imagery.
     */
    private val brain: () -> SemanticBrain,
    private val speech: SpeechIO,
    private val language: Language = Language.ENGLISH,
) {

    /** For a brain that never changes — tests, and any single-engine caller. */
    constructor(
        frames: FrameSource,
        brain: SemanticBrain,
        speech: SpeechIO,
        language: Language = Language.ENGLISH,
    ) : this(frames, { brain }, speech, language)

    /**
     * Handle one press. Returns what was spoken, so callers (and tests) can see
     * the outcome. Never throws for capture or brain failure.
     *
     * @throws Exception only if speech itself fails — with no offline TTS in the
     * MVP (ADR-0012) there is nothing left to degrade to, and swallowing it
     * would hide a silent app behind a green log line.
     */
    suspend fun onPress(question: String? = null): String {
        val current = brain()
        val text = try {
            val frame = frames.capture()
            // A blank answer is as dead as a crash — models do return empty
            // strings — so it degrades the same way.
            current.describe(frame, question).spoken.ifBlank {
                Log.w(TAG, "${current.name} returned a blank answer")
                FALLBACK
            }
        } catch (e: Exception) {
            Log.w(TAG, "press degraded to fallback", e)
            FALLBACK
        }

        speech.speak(text, language)
        return text
    }

    companion object {
        private const val TAG = "Orchestrator"

        /** Spoken when the pipeline fails. Honest, not reassuring. */
        const val FALLBACK = "Sorry, I could not see that. Please try again."
    }
}
