package com.tactilesight.core

import android.util.Log
import com.tactilesight.frame.DirectionNames
import com.tactilesight.frame.DistanceSpeech
import com.tactilesight.frame.ObjectDetector
import com.tactilesight.frame.ObjectDistance
import com.tactilesight.frame.RegionDistance

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
    /**
     * Optional: null when no detector is staged, and the answer falls back to
     * per-direction distance. Named objects are better, but "a wall two metres
     * ahead" still beats silence, and COCO cannot name a wall anyway.
     */
    private val detect: ((ByteArray) -> List<ObjectDetector.Detection>)? = null,
    /** Resolved per press, so switching language needs no restart. */
    private val language: () -> Language = { Language.ENGLISH },
) {

    /** For a brain and language that never change — tests, single-engine callers. */
    constructor(
        frames: FrameSource,
        brain: SemanticBrain,
        speech: SpeechIO,
        language: Language = Language.ENGLISH,
        // Named, not positional: adding a parameter above must not silently
        // rebind this one. It did exactly that when `detect` was introduced.
    ) : this(frames = frames, brain = { brain }, speech = speech, language = { language })

    /**
     * Handle one press. Returns what was spoken, so callers (and tests) can see
     * the outcome. Never throws for capture or brain failure.
     *
     * @throws Exception only if speech itself fails — with no offline TTS in the
     * MVP (ADR-0012) there is nothing left to degrade to, and swallowing it
     * would hide a silent app behind a green log line.
     */
    suspend fun onPress(question: String? = null): String {
        val frame = try {
            frames.capture()
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            speech.speak(FALLBACK, language())
            return FALLBACK
        }
        return answerAbout(frame, question)
    }

    /**
     * Say the device is awake and listening.
     *
     * Spoken on an NFC tap because the user cannot see that anything happened.
     * Touching the band and hearing nothing is indistinguishable from touching
     * it and it not working — and the natural response to that is to tap again,
     * which is how a device teaches someone to distrust it.
     *
     * Failure here is swallowed: this is an acknowledgement, not an answer, and
     * a broken greeting must not stop the app from opening.
     */
    suspend fun speakReady() {
        try {
            speech.speak(READY, language())
        } catch (e: Exception) {
            Log.w(TAG, "could not speak the greeting", e)
        }
    }

    /**
     * Capture the scene the user is asking about, at the moment they press
     * down (#9, ADR-0011).
     *
     * Separate from [answerAbout] because of *when*, not how: on a hold, the
     * question is not known until release, and by then the user may have turned
     * their head or the person they were asking about may have walked on. The
     * frame must be the one that was in front of them when they decided to ask.
     */
    suspend fun captureNow(): Frame? = try {
        frames.capture()
    } catch (e: Exception) {
        Log.w(TAG, "capture failed", e)
        null
    }

    /**
     * Answer about an already-captured [frame], then speak it.
     *
     * [question] null means describe. A hold that produced no usable
     * transcript passes null and therefore gets a description rather than an
     * apology (#11) — someone who held the button and was not understood is
     * better served by hearing what is in front of them than by being told
     * their question failed.
     */
    suspend fun answerAbout(frame: Frame?, question: String? = null): String {
        val current = brain()
        val text = if (frame == null) FALLBACK else try {
            // Measure before describing: whether the thing ahead is a flat
            // plane is a fact the model cannot see for itself, and it changes
            // the description rather than being appended to it.
            val measured = measure(frame)
            // Only when EVERY person in frame is flat. A scene can hold both a
            // poster and real people - a hall with a banner on the wall and
            // people sitting in front of it - and an `any` rule hijacked the
            // whole description to talk about the banner, losing the people who
            // were actually there. That is a worse failure than the one it fixed.
            //
            // Only "person": a flat "chair" against a wall is a measurement
            // artefact worth ignoring; a flat person is a poster, and that is
            // the sentence the user heard go wrong.
            val people = measured.filter { it.isKnown && it.detection.label == "person" }
            val describingAPicture = people.isNotEmpty() && people.none { it.isSolid }
            if (describingAPicture) {
                Log.i(TAG, "every person in frame is flat — describing it as a picture")
            }

            // A blank answer is as dead as a crash — models do return empty
            // strings — so it degrades the same way.
            val raw = current.describe(frame, question, describingAPicture).spoken.ifBlank {
                Log.w(TAG, "${current.name} returned a blank answer")
                FALLBACK
            }
            // A second, separate pass names what is in each direction, so a
            // measured distance can carry a noun the detector cannot supply.
            // Asked after the description exists, so it cannot spoil it, and
            // skipped entirely when the brain declines (the default).
            val named = current.nameDirections(frame)
                ?.let { DirectionNames.parse(it).copy(description = raw) }
                ?: DirectionNames.parse(raw)
            withMeasuredDistance(frame, measured, named)
        } catch (e: Exception) {
            Log.w(TAG, "press degraded to fallback", e)
            FALLBACK
        }

        speech.speak(text, language())
        return text
    }

    /** Detections with their measured distance and flatness, or empty. */
    private fun measure(frame: Frame): List<ObjectDistance.Measured> {
        val detector = detect ?: return emptyList()
        return try {
            ObjectDistance.measure(detector(frame.rgbJpeg), frame.depthMillimetres)
        } catch (e: Exception) {
            Log.w(TAG, "detection failed — continuing without object distances", e)
            emptyList()
        }
    }

    /**
     * Distance first, then the description (ADR-0011's two-stage answer).
     *
     * This is the other half of the VLM's "never state a distance" rule. The
     * model is forbidden from guessing so that this number, when it is spoken,
     * is always a measurement — and when depth cannot reach a direction,
     * nothing is said about how far, while the description still names
     * whatever was seen there.
     *
     * A failure here must not cost the user their answer. Depth is an
     * enhancement to a sentence we already have; if measuring throws, the press
     * still speaks (hard rule #4).
     */
    private fun withMeasuredDistance(
        frame: Frame,
        measured: List<ObjectDistance.Measured>,
        named: DirectionNames.Named,
    ): String {
        val described = named.description
        if (described == FALLBACK) return described
        return try {
            val clause = distanceClauseFor(frame, measured, named)
            if (clause.isBlank()) described else "$clause $described"
        } catch (e: Exception) {
            Log.w(TAG, "distance unavailable — speaking the description alone", e)
            described
        }
    }

    /**
     * Named objects when the detector found any it could measure, otherwise the
     * per-direction reading.
     *
     * Preferring objects is the whole point of running a detector: "a person
     * two metres in front of you" tells the user to stop, while "two metres
     * ahead" only tells them something is there. But the fallback is not a
     * consolation - COCO has no class for a wall, a doorway or a stair, and
     * those are most of what a corridor contains.
     */
    private fun distanceClauseFor(
        frame: Frame,
        measured: List<ObjectDistance.Measured>,
        named: DirectionNames.Named,
    ): String {
        if (measured.isNotEmpty()) {
            val named = DistanceSpeech.clauseForObjects(measured)
            val unmeasured = measured.count { !it.isKnown }
            if (unmeasured > 0) {
                // Not an error. The camera sees wider than depth reaches, and
                // glass and screens read invalid - those objects stay in the
                // VLM's sentence, carrying no number.
                Log.i(TAG, "$unmeasured detected object(s) had no usable depth")
            }
            if (named.isNotBlank()) return named
        }
        // Fall back to per-direction, but carrying the VLM's own noun where it
        // gave one: "a doorway four metres ahead" rather than "four metres
        // ahead". COCO cannot name a doorway; the VLM can.
        return DistanceSpeech.clauseForNamed(RegionDistance.measure(frame.depthMillimetres), named)
    }

    companion object {
        private const val TAG = "Orchestrator"

        /** Spoken when the pipeline fails. Honest, not reassuring. */
        const val FALLBACK = "Sorry, I could not see that. Please try again."

        /** Short on purpose — it is heard before every single use. */
        const val READY = "TactileSight is ready."
    }
}
