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
 * ## Current state: the team's role-and-rules prompt, shipped to be measured
 *
 * **2026-07-19 (day 2).** [describe] is now the team's own prompt, adopted
 * verbatim at their request to get one real run behind it. It is a deliberate
 * departure from what preceded it and from `server/prompt.py`, which is
 * **left unchanged as the control** — so the two tiers can be compared on the
 * same captures instead of both moving at once. That drift is temporary and
 * intentional; collapse it once there is a result.
 *
 * What it adds, and these are real improvements:
 *
 * - *"Sound like a human guide, not an object detector"* — states the design
 *   intent outright rather than gesturing at it.
 * - *"always refer to them in the third person"* — without it a VLM produces
 *   "you are standing near a man" and it is ambiguous who is who.
 * - *"Ignore colors and unimportant objects"* — noise suppression the previous
 *   prompt never asked for.
 * - Crosswalks, elevators, Wet Floor — wider hazard coverage.
 *
 * ### What it drops, recorded so a regression is recognisable
 *
 * Two clauses previously treated as hard rules are gone. Both were removed on
 * purpose, with reasons; neither removal is proven safe yet.
 *
 * - **"Never state a distance"** — the VLM still cannot measure, so anything it
 *   says about distance is invented, and spoken aloud it is indistinguishable
 *   from a figure the depth sensor actually produced (ADR-0013). The team's
 *   position is that distance becomes a measured field once depth is linked to
 *   object detection, at which point the clause is moot. Until that lands,
 *   **watch every output for a guessed distance** — that is the failure this
 *   clause existed to prevent.
 * - **"Answer in English"** — the language dropdown drives *Sarvam*, not the
 *   VLM: the pipeline is VLM → English → `translate` → text-to-speech, and
 *   `SarvamSpeechIO.SOURCE_LANGUAGE` is hardcoded `en-IN`. An English prompt
 *   gets an English answer regardless, so this is low risk in practice, but
 *   nothing now *guards* against drift. A non-English answer would be
 *   translated as though it were English.
 *
 * ### Length is itself a failure mode
 *
 * This prompt is ~180 words against the previous ~83, and carries markdown
 * headers. Both are risks worth naming: an elaborate multi-rule prompt makes a
 * reasoning model narrate its thinking and exhaust its token budget before
 * answering — measured against gemma-4-12b-qat, which returned `content: ""`
 * with 61 reasoning tokens — and header syntax invites a markdown reply where
 * a plain spoken sentence is wanted. Qwen3-VL on QAIRT is not that model, which
 * is why this is shippable; re-check it if the model changes.
 *
 * Clauses previously paid for in debugging and **not** carried over: *"any
 * objects or animals on the floor"* (without it the model fixates on a window
 * or bookshelf and skips the cat sitting in the walking path) and *"Only
 * describe what is really there"* (which, with a low temperature, stopped it
 * inventing people in empty corridors). "Mention only what is visible" and
 * "Never guess or invent details" cover similar ground in the abstract; whether
 * they work as well in practice is exactly what this run measures.
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
        """
        # Role

        You are a real-time navigation assistant for a blind person.

        # Instructions

        - Speak directly to the user as "you."
        - Keep responses very short (one natural sentence).
        - Prioritize safety first.
        - Mention only what helps navigation:
          1. Immediate obstacles or hazards
          2. Clear walking path
          3. People (only if they affect movement; always refer to them in the third person)
          4. Entrances, exits, stairs, elevators, crosswalks
          5. Important signs or readable text (Exit, Entrance, Wet Floor, No Entry, etc.)
        - Use directions like "In front of you," "To your left," and "To your right."
        - Combine related objects into one description.
        - Mention only what is visible.
        - Never guess or invent details.
        - Ignore colors and unimportant objects unless requested.
        - Sound like a human guide, not an object detector.
        """.trimIndent()

    /** The user asked something specific about the scene. */
    fun query(question: String): String =
        "Look at the image and answer in one short sentence, in English. " +
            "Do not mention distance. Question: $question"

    fun forRequest(question: String?): String =
        if (question.isNullOrBlank()) describe() else query(question)
}
