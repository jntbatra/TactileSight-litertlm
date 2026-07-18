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
 * - *"If people are there… if there are none, say nothing about people at all"*
 *   — both halves are needed and they fix opposite failures. Listing "people"
 *   among the things to lead with was not enough on its own: the model
 *   described a room of seated people as furniture. But an instruction to
 *   report people invites the negative, and *"there is no one in front of you"*
 *   spoken on every empty corridor is noise that trains the user to stop
 *   listening. Absence of people is the default state of the world and does not
 *   need saying.
 *
 * The VLM always answers in **English** — small VLMs are markedly weak in Indic
 * languages, so translation happens afterwards via Sarvam (ADR-0012).
 */
object VlmPrompt {

    /**
     * No question — describe what is ahead.
     *
     * Reads as instructions to a guide rather than to a detector, which is what
     * produces "a doorway to your left" instead of "door, left, 1". Every
     * clause earns its place; the prompt was **shorter by two sentences** than
     * the version it replaced, because length itself is a failure mode here —
     * an elaborate multi-rule prompt makes a reasoning model narrate its
     * thinking and hit the token cap before it answers.
     */
    fun describe(): String =
        "You are guiding a blind person. In one short, natural sentence, say what is ahead, " +
            "using \"in front of you\", \"to your left\", \"to your right\". " +
            "Lead with whatever affects their next step: obstacles, people, doorways, stairs, " +
            "and any objects or animals on the floor. " +
            "If people are there, say where they are and refer to them in the third person. " +
            "If there are none, say nothing about people at all — never say that nobody is there. " +
            "Read out any sign, board or written text, especially directions and danger warnings. " +
            "Group related things into one phrase rather than listing them. No preamble. " +
            "Only describe what is really there. Never state a distance. Answer in English."

    /** The user asked something specific about the scene. */
    fun query(question: String): String =
        "Look at the image and answer in one short sentence, in English. " +
            "Do not mention distance. Question: $question"

    fun forRequest(question: String?): String =
        if (question.isNullOrBlank()) describe() else query(question)
}
