package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing the one structured line we ask a 4B model for.
 *
 * The governing rule is that **every failure degrades to the current
 * behaviour**: no names means the answer falls back to the per-direction
 * clause that already works. Nothing the model can emit here should make the
 * spoken answer worse than it is without this feature.
 */
class DirectionNamesTest {

    @Test
    fun `reads the names and strips the line from what is spoken`() {
        val parsed = DirectionNames.parse(
            "AHEAD=doorway; LEFT=wall; RIGHT=people\nA doorway ahead with people to your right.",
        )
        assertEquals("doorway", parsed.ahead)
        assertEquals("wall", parsed.left)
        assertEquals("people", parsed.right)
        assertEquals("A doorway ahead with people to your right.", parsed.description)
    }

    @Test
    fun `a model that ignores the format still gets its sentence spoken`() {
        val plain = "In front of you is a doorway and a sign reading WASHROOM."
        val parsed = DirectionNames.parse(plain)
        assertNull(parsed.ahead)
        assertEquals(plain, parsed.description)
    }

    @Test
    fun `NONE and its cousins name nothing`() {
        val parsed = DirectionNames.parse("AHEAD=NONE; LEFT=n/a; RIGHT=clear\nNothing ahead.")
        assertNull(parsed.ahead)
        assertNull(parsed.left)
        assertNull(parsed.right)
    }

    @Test
    fun `a sentence where a noun was asked for is refused`() {
        // Guards against "a there is a large open area with several chairs
        // four metres ahead" - the model answering the wrong question in the
        // right slot.
        val parsed = DirectionNames.parse(
            "AHEAD=there is a large open area with several chairs; LEFT=wall\nOpen area ahead.",
        )
        assertNull("a run-on was accepted as a name", parsed.ahead)
        assertEquals("wall", parsed.left)
    }

    @Test
    fun `missing directions are simply absent`() {
        val parsed = DirectionNames.parse("AHEAD=stairs\nStairs ahead.")
        assertEquals("stairs", parsed.ahead)
        assertNull(parsed.left)
        assertNull(parsed.right)
    }

    @Test
    fun `a line with no sentence after it still speaks something`() {
        // Better to say the raw answer than to fall silent (hard rule #4).
        val parsed = DirectionNames.parse("AHEAD=wall; LEFT=NONE; RIGHT=NONE")
        assertEquals("wall", parsed.ahead)
        assertEquals("AHEAD=wall; LEFT=NONE; RIGHT=NONE", parsed.description)
    }
}
