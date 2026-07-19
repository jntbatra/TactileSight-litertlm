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
 * - *"as "a person" or "people" — never guess their gender"* — the model
 *   reached for *"a man sitting on it"*, *"a man walking toward you"* on every
 *   figure it saw. It cannot know that; it is inferring gender from clothing
 *   and build. Our user cannot see the person to notice the mistake, and a
 *   confident wrong detail is worse than a missing one — the whole device rests
 *   on the user trusting what it says. "A person" costs nothing and is never
 *   wrong.
 * - *"Do not mention colours"* — added to the existing grouping rule after the
 *   model answered a lounge with *"a red bean bag chair, a green bean bag
 *   chair, a blue bean bag chair, a yellow bean bag chair"*. Colour is the
 *   detail it reaches for when it has nothing useful to say.
 *
 * ### Two rules this prompt learned the hard way — do not undo them
 *
 * **Never put an example phrase in this prompt.** A version that read *"say
 * several similar things as one group, like "a few chairs to your left""* got
 * *"a few chairs to your left"* echoed verbatim into all four test scenes,
 * including ones with no chairs — and, worse, that same version started
 * inventing signs (*a sign on the wall reading "EXIT"*, *"Welcome to the
 * Plaza"*) in scenes that had none. `prompt.py` warned about echoed examples;
 * it was measured here.
 *
 * **Every "always report X" needs its "say nothing when there is no X".** This
 * bit twice, identically. Instructing the model to report people made it
 * announce their absence; instructing it to *"say the exact words in quotes —
 * never just say that a sign is there"* produced *"no sign or board visible
 * nearby"*, and then a fabricated sign reading *"in front of you"* — the
 * prompt's own words quoted back as if painted on a wall. Asking for quotes
 * invites manufacturing quotable text. The working form asks for the words and
 * pairs it with silence-on-absence.
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
        "You are guiding a blind person. In one short, natural sentence, say what is ahead, using \"in front of you\", \"to your left\", \"to your right\". Lead with whatever affects their next step, including anything on the floor. If people are there, say where, as \"a person\" or \"people\" — never guess their gender; if there are none, say nothing about people. If a sign has words on it, read the words out; if not, say nothing about signs. Do not mention colours or distance. Only describe what is really there. Answer in English."

    /** The user asked something specific about the scene. */
    fun query(question: String): String =
        "Look at the image and answer in one short sentence, in English. " +
            "Do not mention distance. Question: $question"

    fun forRequest(question: String?): String =
        if (question.isNullOrBlank()) describe() else query(question)
}
