package com.tactilesight.brain

import android.util.Log
import com.tactilesight.core.BrowsableFrameSource

/**
 * Scores two prompt wordings over the same captures, on the same engine.
 *
 * The prompt is load-bearing (see [VlmPrompt]) and until now every change to it
 * has been justified by looking at one or two answers. That is how we ended up
 * unsure whether adding the sign-reading clause is what made the model start
 * padding with "The image shows" — a question nobody could answer from
 * anecdote. On the NPU an answer costs ~260 ms, so measuring is cheap enough
 * that guessing is indefensible.
 *
 * Scoring is deliberately mechanical. It counts things that are objectively
 * checkable — a forbidden preamble, a stated distance, whether sign text was
 * actually read — and leaves the judgement of *which* answer is better to a
 * human reading the logged sentences.
 */
object PromptBenchmark {

    /**
     * The prompt as it stands (2026-07-19): a night of additions —
     * gender-neutral people, silence-on-absence for people and signs, no
     * colours — then trimmed from 141 to 124 words, with the grouping rule
     * widened to name busy scenes.
     *
     * The trim dropped *"in the third person"*, *"never say that nobody is
     * there"* and *"directions and danger warnings matter most"* as redundant
     * with the clauses they sat beside. Redundant on a reading; **unverified on
     * the device**. That is precisely the anecdote-driven change this benchmark
     * exists to catch, so run it before trusting the trim.
     */
    val VARIANT_A: String = VlmPrompt.describe()

    /**
     * The same prompt before that night's clauses — 83 words.
     *
     * This is the control for a specific suspicion: that the additions, each
     * justified on its own, together made the model worse. Every clause was
     * added to fix something real and verified against the scene it fixed. None
     * was checked for what it cost elsewhere, and prompt.py's own docstring
     * warns that an elaborate multi-rule prompt makes a reasoning model narrate
     * and pad. Length is a failure mode; this measures whether we hit it.
     */
    const val VARIANT_B =
        "You are guiding a blind person. In one short, natural sentence, say what is ahead, " +
            "using \"in front of you\", \"to your left\", \"to your right\". " +
            "Lead with whatever affects their next step: obstacles, people, doorways, stairs, " +
            "and any objects or animals on the floor. " +
            "Read out any sign, board or written text, especially directions and danger warnings. " +
            "Group related things into one phrase rather than listing them. No preamble. " +
            "Only describe what is really there. Never state a distance. Answer in English."

    /** Openings the prompt forbids; the model produces them anyway. */
    private val PREAMBLES = listOf(
        "the image shows", "the picture shows", "i see", "this is", "there is a",
        "in the image", "the scene",
    )

    /** Words that mean it stated a distance it cannot measure — hard rule #3. */
    private val DISTANCE_WORDS = listOf(
        "meter", "metre", "feet", "foot", "inches", "inch", "cm", "away from",
    )

    data class Score(
        val variant: String,
        val answers: Int,
        val withPreamble: Int,
        val withDistance: Int,
        val readSignText: Int,
        val meanWords: Double,
        val meanTtftMs: Double,
    )

    /**
     * Runs both variants over the first [captures] scenes and logs a table.
     * Sequential on purpose — one model, one press at a time, as in real use.
     */
    suspend fun run(
        brain: GenieXBrain,
        frames: BrowsableFrameSource,
        captures: Int = 6,
    ): List<Score> {
        val scores = mutableListOf<Score>()

        for ((label, prompt) in listOf("A" to VARIANT_A, "B" to VARIANT_B)) {
            var preamble = 0
            var distance = 0
            var signText = 0
            var words = 0
            var ttft = 0.0
            val answers = mutableListOf<String>()

            for (index in 0 until minOf(captures, frames.sceneIds.size)) {
                val frame = frames.load(frames.sceneIds[index])
                val spoken = brain.describeWith(frame, prompt).spoken
                answers += spoken

                val lower = spoken.lowercase()
                if (PREAMBLES.any { lower.startsWith(it) }) preamble++
                if (DISTANCE_WORDS.any { it in lower }) distance++
                // Sign text read, not merely spotted: quoted text, or capitals
                // that are not just the first word of the sentence.
                if (Regex("\"[^\"]+\"|\\b[A-Z]{4,}\\b").containsMatchIn(spoken)) signText++
                words += spoken.split(Regex("\\s+")).count { it.isNotBlank() }
                ttft += brain.lastProfile?.timeToFirstTokenMs ?: 0.0

                Log.i(TAG, "[$label] ${frames.sceneIds[index]}: $spoken")
            }

            val n = answers.size.coerceAtLeast(1)
            scores += Score(
                variant = label,
                answers = answers.size,
                withPreamble = preamble,
                withDistance = distance,
                readSignText = signText,
                meanWords = words.toDouble() / n,
                meanTtftMs = ttft / n,
            )
        }

        scores.forEach {
            Log.i(
                TAG,
                "RESULT ${it.variant}: n=${it.answers} preamble=${it.withPreamble} " +
                    "distance=${it.withDistance} signText=${it.readSignText} " +
                    "words=${"%.1f".format(it.meanWords)} ttft=${"%.0f".format(it.meanTtftMs)}ms",
            )
        }
        return scores
    }

    private const val TAG = "PromptBenchmark"
}
