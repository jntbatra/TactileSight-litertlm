package com.tactilesight.core

/**
 * What the scene contains, in English. The brain never states a distance — it
 * cannot measure one. Distance is appended by the phone from depth (ADR-0013).
 */
data class Answer(
    val spoken: String,
    val confident: Boolean = true,
)

/**
 * The primary seam. Three on-device engines implement this (LiteRT-LM, GenieX,
 * ExecuTorch) plus a cloud fallback, selected at runtime — ADR-0010.
 *
 * Lifecycle is a hard rule, not a preference: implementations load their model
 * **once** and keep it resident, lock-guarded, releasing only in [close] when
 * the engine or model actually changes — never on Activity recreation. The old
 * app dropped its model on every rotation and was OOM-killed six times.
 */
interface SemanticBrain {

    /** Human-readable engine name, for the dev picker and the benchmark. */
    val name: String

    /**
     * Describe [frame], optionally answering [question]. Answers in English;
     * translation to the user's language happens in [SpeechIO] (ADR-0012).
     */
    /**
     * [surfaceIsFlat] carries one measured fact the model cannot see for
     * itself: the thing it is looking at fits a plane, so a scene or people
     * depicted there are a poster or a screen rather than the real thing.
     * A parameter rather than a second method - the alternative was two
     * describe() calls that differed by one sentence.
     */
    suspend fun describe(
        frame: Frame,
        question: String? = null,
        surfaceIsFlat: Boolean = false,
    ): Answer

    /**
     * Name what is in each direction, for attaching a measured distance to a
     * noun the detector cannot supply — a wall, a doorway, a stair.
     *
     * Returns null when this brain cannot or will not do it. That is the
     * default: the feature must never be the reason a press fails, and a tier
     * that answers slowly over a venue network should not pay for a second
     * round trip.
     */
    suspend fun nameDirections(frame: Frame): String? = null

    /** Release the model. Called only on engine/model switch. */
    fun close() {}
}
