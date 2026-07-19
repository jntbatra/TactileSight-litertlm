package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceSpeechTest {

    private fun reading(region: RegionDistance.Region, mm: Int?) =
        RegionDistance.Reading(region, mm, validFraction = if (mm == null) 0.02f else 0.8f)

    private fun all(left: Int?, ahead: Int?, right: Int?) = listOf(
        reading(RegionDistance.Region.LEFT, left),
        reading(RegionDistance.Region.AHEAD, ahead),
        reading(RegionDistance.Region.RIGHT, right),
    )

    @Test
    fun `speaks what is ahead, because that is where the next step goes`() {
        assertEquals("two metres ahead.", DistanceSpeech.clauseFor(all(null, 2_000, null)))
    }

    @Test
    fun `mentions a side only when it is close and nearer than ahead`() {
        // A wall at arm's length changes what you do. One three metres away
        // while the path runs on does not, and saying it every press is how a
        // user learns to stop listening.
        val close = DistanceSpeech.clauseFor(all(1_000, 4_000, null))
        assertTrue(close, close.contains("to your left"))

        val far = DistanceSpeech.clauseFor(all(3_000, 4_000, null))
        assertFalse(far, far.contains("to your left"))
    }

    @Test
    fun `says nothing at all when no direction could be measured`() {
        // Not "distance unknown" three times over. Silence, and the
        // description is spoken alone - the VLM still names what it saw, it
        // just carries no number.
        assertEquals("", DistanceSpeech.clauseFor(all(null, null, null)))
    }

    @Test
    fun `an unmeasurable direction is simply not mentioned`() {
        val clause = DistanceSpeech.clauseFor(all(null, 2_000, null))
        assertFalse(clause, clause.contains("left"))
        assertFalse(clause, clause.contains("right"))
        assertFalse(clause, clause.contains("unknown"))
    }

    @Test
    fun `distances are spoken as words, never as digits`() {
        // This is read aloud by a text-to-speech engine and then translated.
        // "1.5 m" is a rendering problem in both.
        val clause = DistanceSpeech.clauseFor(all(null, 1_500, null))
        assertTrue(clause, clause.none { it.isDigit() })
    }

    @Test
    fun `rounds to half metres up close and whole metres further out`() {
        assertEquals("one and a half metres ahead.", DistanceSpeech.clauseFor(all(null, 1_500, null)))
        assertEquals("two metres ahead.", DistanceSpeech.clauseFor(all(null, 2_100, null)))
        assertEquals("about five metres ahead.", DistanceSpeech.clauseFor(all(null, 4_900, null)))
    }

    @Test
    fun `does not claim precision the method does not have`() {
        // Nearest surface in a third of the frame, matched by direction. Good
        // to roughly half a metre. A confident decimal, to someone who cannot
        // check it, is a lie told fluently.
        // Not `contains(".")` — the clause ends in a full stop. What must not
        // appear is a decimal *number*.
        assertFalse(
            DistanceSpeech.clauseFor(all(null, 1_430, null)),
            Regex("""\d+\.\d""").containsMatchIn(DistanceSpeech.clauseFor(all(null, 1_430, null))),
        )
        assertEquals(
            DistanceSpeech.clauseFor(all(null, 1_480, null)),
            DistanceSpeech.clauseFor(all(null, 1_520, null)),
        )
    }

    @Test
    fun `very close reads as less than a metre rather than a number`() {
        assertEquals("less than a metre ahead.", DistanceSpeech.clauseFor(all(null, 700, null)))
    }

    @Test
    fun `picks the nearer side when both are close`() {
        val clause = DistanceSpeech.clauseFor(all(1_800, 5_000, 900))
        assertTrue(clause, clause.contains("to your right"))
        assertFalse(clause, clause.contains("to your left"))
    }
}
