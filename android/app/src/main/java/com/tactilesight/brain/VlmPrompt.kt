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
 * - *"Where things repeat or crowd together, describe them as one group"* — the
 *   grouping rule, widened from *"group related things into one phrase"* to name
 *   the case it keeps failing on: a busy scene. Listing is what the model falls
 *   back to when there is too much to say, and a list is unusable to someone
 *   walking. Note the phrasing carries no example — see the hard-won rule below.
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
        "You are guiding a blind person. In one short, natural sentence, say what is ahead, " +
            "using \"in front of you\", \"to your left\", \"to your right\". " +
            "Lead with whatever affects their next step: obstacles, people, doorways, stairs, " +
            "and any objects or animals on the floor. " +
            "Where things repeat or crowd together, describe them as one group rather than " +
            "listing them. " +
            "Say where people are, as \"a person\" or \"people\" — never guess their gender; " +
            "if there are none, say nothing about people at all. " +
            "If a sign or board has words on it, read the words out; if it has none, say " +
            "nothing about signs. " +
            "Do not mention colours. No preamble. " +
            "Only describe what is really there. Never state a distance. Answer in English."

    /** The user asked something specific about the scene. */
    fun query(question: String): String =
        "Look at the image and answer in one short sentence, in English. " +
            "Do not mention distance. Question: $question"

    /**
     * What to say when depth has established the thing ahead is a flat plane.
     *
     * **Replaces the describe prompt rather than appending to it**, and that is
     * the whole point. The first attempt added the fact as a final sentence to
     * the existing ~120-word prompt and the model ignored it completely — it
     * still answered "a person to your right with an object on their left".
     * Measured, not assumed: the flag fired, the sentence was in the prompt,
     * and the output was unchanged.
     *
     * That is the same length failure the rest of this file documents. A rider
     * on a long prompt competes with every clause above it, and "say where
     * people are" wins because it is stated three times over. A short prompt
     * with one job does not have that problem.
     */
    private const val FLAT_SURFACE =
        "You are guiding a blind person. The depth sensor has measured the surface ahead " +
            "and it is completely flat, so what you can see is printed or on a screen — " +
            "a poster, photograph, television or sign — not a real scene. " +
            "In one short sentence, say that there is a poster or screen ahead and what it " +
            "shows. Never describe the people or objects in it as if they were really there. " +
            "Answer in English."

    fun forRequest(question: String?): String =
        if (question.isNullOrBlank()) describe() else query(question)

    /**
     * A fact the phone measured, handed to the model so it stops describing a
     * picture as if it were the room.
     *
     * The failure this fixes: a life-size poster of two people. The VLM has no
     * way to tell it from the real thing — it is a photograph of a photograph —
     * and answered "a person holds an object near their body". Depth knows
     * better, because a poster fits a plane to within sensor noise while a body
     * does not (ObjectDistance.Measured.isSolid).
     *
     * **This is a measurement, not a hint.** It is only appended when the depth
     * sensor actually established flatness, so the model is never asked to
     * reconcile the image with a guess.
     *
     * Deliberately *not* done by feeding the depth image alongside the colour
     * one. The runtime supports two images, but a 4B VLM has never been trained
     * to read a JET colormap as distance and would most likely describe it as a
     * colourful abstract picture — and it would double the vision tokens on
     * every press. We already computed the answer; fifteen words of English is
     * a more reliable channel than hoping the model infers it.
     */
    fun withFlatSurface(prompt: String): String = FLAT_SURFACE
}
