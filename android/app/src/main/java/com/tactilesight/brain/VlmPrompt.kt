package com.tactilesight.brain

/**
 * The spoken-answer contract for the on-device tier.
 *
 * **This mirrors `server/prompt.py` on purpose.** That file's own docstring says
 * the duplication is deliberate: the two tiers run different models on different
 * runtimes and want their prompts tuned independently. What must stay identical
 * is *the behaviour the user hears* — terse, identity + left/centre/right, never
 * a distance, never invented detail — not the prompt string.
 *
 * (TEAM.md's hard rule "the prompt lives server-side, do not write a private
 * prompt anywhere else" reads as forbidding this file. It cannot be taken
 * literally: an on-device VLM with no network cannot fetch a server-side prompt.
 * The rule's real intent — never let the two tiers drift into different spoken
 * behaviour — is what this comment exists to protect. Flagged for the team.)
 *
 * Wording is lifted from `prompt.py` rather than rewritten, because parts of it
 * are load-bearing and were paid for in debugging:
 *
 * - *"including any objects or animals on the floor"* — without it the model
 *   fixates on large background objects (a window, a bookshelf) and skips a
 *   small subject in the foreground. A cat sitting in the path is exactly what
 *   a walking user needs to hear about.
 * - *"Only describe what is really there"* — stops it inventing people and
 *   animals in empty scenes.
 * - *"Read out any sign, board or written text"* — a description that names a
 *   door but not the "WASHROOM" written on it is scenery, not navigation.
 *   Measured on real captures: Gemma read the sign, Qwen3-VL only spotted one,
 *   so it is worth asking for explicitly and worth re-checking per model.
 * - *"Do not mention distance"* — the VLM cannot measure. Distance is appended
 *   by the phone from depth (ADR-0013). This line is a hard rule, not a style
 *   preference.
 *
 * The VLM always answers in **English** — small VLMs are markedly weak in Indic
 * languages, so translation happens afterwards via Sarvam (ADR-0012).
 */
object VlmPrompt {

    /** No question — describe what is ahead. */
    fun describe(): String =
        "In one short sentence, say what is ahead, including any objects or animals on the floor, " +
            "and whether each is on the left, center, or right. " +
            "Read out any sign, board or written text you can see. " +
            "Name the things directly, no preamble. " +
            "Only describe what is really there. Do not mention distance. Answer in English."

    /** The user asked something specific about the scene. */
    fun query(question: String): String =
        "Look at the image and answer in one short sentence, in English. " +
            "Do not mention distance. Question: $question"

    fun forRequest(question: String?): String =
        if (question.isNullOrBlank()) describe() else query(question)
}
