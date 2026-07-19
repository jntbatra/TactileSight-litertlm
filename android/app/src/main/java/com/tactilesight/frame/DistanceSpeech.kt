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
}
