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
     * Whether this brain occupies the memory a *second* model would need.
     *
     * The one-model-resident rule (ADR-0010) exists because two multi-gigabyte
     * VLMs at once got the old app OOM-killed six times. A server brain is not
     * one of those — it is an HTTP client — so evicting four gigabytes of
     * mapped weights to make room for a socket buys nothing and costs a
     * multi-second reload on the way back.
     *
     * So the rule is really: never hold two of *these*. This flag is how the
     * app tells the difference.
     */
    val holdsModel: Boolean get() = false

    /**
     * Get the model into memory, and say whether it made it.
     *
     * Exists because "ready" was a lie. The screen reported
     * `Ready · GenieX (qairt/npu)` the moment the picker resolved, while the
     * model had not been mapped yet — so the first press after launch came back
     * *"Sorry, I could not see that."* The user had done nothing wrong and the
     * screen had told them nothing true.
     *
     * Called at startup, so the wait happens while the phone is still going
     * into a pocket rather than after a press. Idempotent: engines load once
     * under their own lock, so preparing and then pressing does not load twice.
     *
     * Default `true` — a server brain and the stub have nothing to load and are
     * ready the moment they exist.
     */
    suspend fun prepare(): Boolean = true

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
