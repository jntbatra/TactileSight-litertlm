package com.tactilesight.frame

/**
 * What the VLM saw in each direction, so a measured distance can be attached to
 * **a named thing** rather than to a compass point.
 *
 * *"about four metres ahead"* tells a blind user something is there. *"a doorway
 * about four metres ahead"* tells them what to do about it. The difference is
 * the noun, and the noun can only come from the VLM: COCO — and therefore
 * [ObjectDetector] — has no class for a wall, a doorway, a stair or a corridor,
 * which is most of what a building is made of.
 *
 * So the two halves come from different places on purpose:
 *
 * ```
 * what it is   <- the VLM, which can name a doorway
 * how far      <- depth, which the VLM must never guess (ADR-0013)
 * ```
 *
 * ### The format, and why parsing is safe here
 *
 * The model is asked to emit one machine-readable line before its sentence:
 *
 * ```
 * AHEAD=doorway; LEFT=wall; RIGHT=people
 * ```
 *
 * This is the one place we ask a 4B model for structure, and it is deliberately
 * the weakest possible ask — three keys, short values, on the first line, with
 * `NONE` allowed. **Every failure mode degrades to the current behaviour**: a
 * missing line, a malformed line or an unknown key all yield no names, and the
 * answer falls back to the per-direction clause that already works. Nothing
 * here can make the spoken answer worse than it is today.
 *
 * The line is stripped before the description is spoken — the user hears
 * English, never a key-value pair.
 */
object DirectionNames {

    data class Named(
        val ahead: String?,
        val left: String?,
        val right: String?,
        /** The description with the structured line removed. */
        val description: String,
    ) {
        fun forRegion(region: RegionDistance.Region): String? = when (region) {
            RegionDistance.Region.AHEAD -> ahead
            RegionDistance.Region.LEFT -> left
            RegionDistance.Region.RIGHT -> right
        }
    }

    /** Never throws and never returns null — a bad line simply names nothing. */
    fun parse(answer: String): Named {
        val trimmed = answer.trim()
        val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()

        if (!LINE.containsMatchIn(firstLine)) {
            return Named(null, null, null, description = trimmed)
        }

        val values = LINE.findAll(firstLine).associate { match ->
            match.groupValues[1].uppercase() to clean(match.groupValues[2])
        }

        val rest = trimmed.lineSequence().drop(1).joinToString("\n").trim()
        return Named(
            ahead = values["AHEAD"],
            left = values["LEFT"],
            right = values["RIGHT"],
            // If the model gave the line and nothing else, keep what we have
            // rather than speaking an empty sentence.
            description = rest.ifBlank { trimmed },
        )
    }

    /**
     * A value we are willing to say out loud.
     *
     * Rejects the model's ways of saying "nothing": `NONE`, `N/A`, an empty
     * value, and anything long enough to be a sentence rather than a noun —
     * a run-on here would be spoken as "a there is a large open area with
     * several chairs four metres ahead".
     */
    private fun clean(raw: String): String? {
        val value = raw.trim().trim('.', ',', ';', '"').lowercase()
        if (value.isBlank()) return null
        if (value in NOTHING) return null
        if (value.split(' ').size > MAX_WORDS) return null
        return value
    }

    /** `KEY=value` up to a separator. Tolerates spaces and missing entries. */
    private val LINE = Regex("""\b(AHEAD|LEFT|RIGHT)\s*=\s*([^;|\n]+)""", RegexOption.IGNORE_CASE)

    private val NOTHING = setOf("none", "n/a", "na", "nothing", "unknown", "-", "empty", "clear")

    /** "open doorway" is a name; anything longer is a sentence. */
    private const val MAX_WORDS = 3
}
