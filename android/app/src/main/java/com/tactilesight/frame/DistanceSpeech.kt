package com.tactilesight.frame

import kotlin.math.roundToInt

/**
 * Turns measured depth into the clause spoken *before* the description
 * (ADR-0011's two-stage answer): distance first, because it is what decides the
 * next step, then what the thing actually is.
 *
 * ### What gets said, and what does not
 *
 * Three numbers on every press is not information, it is noise — and a user who
 * hears noise stops listening, which costs us the one press that mattered. So:
 *
 * - **Ahead is always spoken when known.** That is where the next step goes.
 * - **A side is spoken only when it is close** ([CLOSE_ENOUGH_MM]) **and nearer
 *   than ahead.** A wall three metres to the left while the path runs on is not
 *   news; a wall an arm's length away is.
 * - **Nothing is said about a direction with no reading.** Not "distance
 *   unknown to your left" — silence. The description still names whatever the
 *   VLM saw there, which is exactly the behaviour asked for: an object the
 *   camera sees but depth cannot reach is still spoken, just without a number.
 *
 * ### Why the rounding is coarse
 *
 * The measurement is the nearest surface in a third of the frame, matched to
 * the description by horizontal position (ADR-0013, option 2). It is good to
 * roughly a half-metre, not to a centimetre. Saying "1.43 metres" would claim a
 * precision the method does not have, and to someone who cannot check it, a
 * confident decimal is a lie told fluently. Half-metres up close, whole metres
 * further out, where the error is larger and the exactness matters less.
 */
object DistanceSpeech {

    /**
     * The leading clause, or empty when nothing can be said honestly — in which
     * case the description is spoken alone and no distance is implied.
     */
    fun clauseFor(readings: List<RegionDistance.Reading>): String {
        val ahead = readings.firstOrNull { it.region == RegionDistance.Region.AHEAD }
        val closeSide = readings
            .filter { it.region != RegionDistance.Region.AHEAD && it.isKnown }
            .filter { it.millimetres!! <= CLOSE_ENOUGH_MM }
            .filter { ahead?.millimetres == null || it.millimetres!! < ahead.millimetres }
            .minByOrNull { it.millimetres!! }

        val parts = buildList {
            if (ahead?.isKnown == true) add("${phrase(ahead.millimetres!!)} ahead")
            if (closeSide != null) {
                add("${phrase(closeSide.millimetres!!)} ${closeSide.region.spoken}")
            }
        }

        return if (parts.isEmpty()) "" else parts.joinToString(", ") + "."
    }

    /**
     * The named-object clause: *"a person two metres to your left"*.
     *
     * Preferred over [clauseFor] when the detector found something, because a
     * distance attached to a thing is worth more than a distance attached to a
     * direction — "a person at two metres" tells you to stop; "two metres
     * ahead" only tells you something is there.
     *
     * Deliberately narrow:
     *
     * - **Only objects depth could measure.** An object the camera saw but
     *   depth could not reach is left entirely to the VLM's sentence, which
     *   still names it — the requirement being that it is spoken *without* a
     *   number, never with a guessed one.
     * - **Nearest first, and few.** [MAX_SPOKEN] of them. The detector will
     *   happily return eight chairs; a person listening needs the two things
     *   that affect their next step, and a list is how you make someone stop
     *   listening.
     * - **Duplicates collapsed by label and side**, so a row of chairs becomes
     *   one phrase rather than four.
     */
    fun clauseForObjects(measured: List<ObjectDistance.Measured>): String {
        val spoken = measured
            .filter { it.isKnown }
            .filter { it.detection.label in NAMEABLE }
            // A poster of a person is detected correctly, sits at a real
            // distance and is the right apparent size. Only its flatness gives
            // it away - see ObjectDistance.Measured.isSolid.
            .filter { it.isSolid }
            .sortedBy { it.millimetres }
            .distinctBy { it.detection.label to sideOf(it.detection.centreX) }
            .take(MAX_SPOKEN)

        if (spoken.isEmpty()) return ""

        return spoken.joinToString(", ") {
            "${article(it.detection.label)} ${phrase(it.millimetres!!)} ${sideOf(it.detection.centreX)}"
        } + "."
    }

    private fun sideOf(centreX: Float): String = when {
        centreX < LEFT_EDGE -> "to your left"
        centreX > RIGHT_EDGE -> "to your right"
        else -> "in front of you"
    }

    /** "a person", "an umbrella" — spoken English, not a label dump. */
    private fun article(label: String): String =
        if (label.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) {
            "an $label"
        } else {
            "a $label"
        }

    /** Spoken form of a distance. Words, not digits — this is read aloud. */
    private fun phrase(millimetres: Int): String {
        val metres = millimetres / 1000f
        return when {
            metres < 1f -> "less than a metre"
            metres < 3f -> {
                // Half-metre steps: "a metre and a half", "two metres".
                val halves = (metres * 2).roundToInt()
                if (halves % 2 == 0) "${wholeWord(halves / 2)} metre${plural(halves / 2)}"
                else "${wholeWord(halves / 2)} and a half metres"
            }
            else -> "about ${wholeWord(metres.roundToInt())} metres"
        }
    }

    private fun wholeWord(n: Int): String = when (n) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        else -> n.toString()
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /**
     * Close enough that a surface beside you changes what you do. Beyond this a
     * side reading is scenery, and saying it every press trains the user to
     * stop listening.
     */
    private const val CLOSE_ENOUGH_MM = 2_000

    /** Thirds, matching RegionDistance so the two never disagree about "left". */
    private const val LEFT_EDGE = 0.33f
    private const val RIGHT_EDGE = 0.67f

    /**
     * Two named objects is a sentence; four is an inventory. The detector
     * returns up to eight and a listener acts on the first one.
     */
    private const val MAX_SPOKEN = 2

    /**
     * The only classes we will speak by name.
     *
     * **This exists because the detector confidently misnames things.** On the
     * hackathon-hall captures YOLO called the bean bags *"a suitcase"* at 0.60
     * confidence — the distance was right, the noun was wrong, and a blind user
     * has no way to notice. The same reasoning that removed guessed gender
     * applies: a confident wrong detail is worse than a missing one, because
     * the whole device rests on the user trusting what it says.
     *
     * `person` is kept because it is what YOLO is genuinely best at, it is the
     * single most navigation-critical thing in a frame, and it is a class where
     * a near-miss is not possible — something either is a person or it is not.
     *
     * Everything else still contributes: an unnameable object falls through to
     * the per-direction reading, which states a distance without claiming to
     * know what is there. The VLM's own sentence supplies the identity, and it
     * called those bean bags correctly.
     *
     * Widen this only with measurements on real captures, one class at a time.
     */
    private val NAMEABLE = setOf("person")
}
