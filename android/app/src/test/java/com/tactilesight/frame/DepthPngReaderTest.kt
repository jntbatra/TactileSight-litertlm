package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

class DepthPngReaderTest {

    /**
     * Writes a 16-bit greyscale PNG the way the board's `png.Writer` does.
     *
     * [filter] is applied to every scanline, so the same pixels can be encoded
     * five different ways and must decode identically — which is the property
     * that actually matters, since the board's encoder is free to pick any of
     * them per row.
     */
    private fun pngBytes(
        width: Int,
        height: Int,
        pixels: ShortArray,
        filter: Int = 0,
        bitDepth: Int = 16,
        colourType: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(),
            'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArrayOutputStream().apply {
            writeInt(width); writeInt(height)
            write(bitDepth); write(colourType)
            write(0); write(0); write(0) // compression, filter, interlace
        }.toByteArray()
        out.writeChunk("IHDR", ihdr)

        // Raw scanlines: a filter byte, then big-endian uint16 pixels.
        val stride = width * 2
        val raw = ByteArrayOutputStream()
        val previous = ByteArray(stride)
        for (y in 0 until height) {
            val line = ByteArray(stride)
            for (x in 0 until width) {
                val value = pixels[y * width + x].toInt() and 0xFFFF
                line[x * 2] = (value shr 8).toByte()
                line[x * 2 + 1] = value.toByte()
            }
            raw.write(filter)
            for (x in 0 until stride) {
                val a = if (x >= 2) line[x - 2].toInt() and 0xFF else 0
                val b = previous[x].toInt() and 0xFF
                val c = if (x >= 2) previous[x - 2].toInt() and 0xFF else 0
                val v = line[x].toInt() and 0xFF
                val encoded = when (filter) {
                    0 -> v
                    1 -> v - a
                    2 -> v - b
                    3 -> v - (a + b) / 2
                    4 -> v - paeth(a, b, c)
                    else -> throw IllegalArgumentException("bad filter")
                }
                raw.write(encoded and 0xFF)
            }
            System.arraycopy(line, 0, previous, 0, stride)
        }

        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        out.writeChunk("IDAT", compressed.toByteArray())
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24); write(value ushr 16); write(value ushr 8); write(value)
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        val typed = type.toByteArray(Charsets.US_ASCII) + data
        write(typed)
        val crc = CRC32().apply { update(typed) }.value
        writeInt(crc.toInt())
    }

    @Test
    fun `reads shape and millimetre values`() {
        val pixels = shortArrayOf(1000, 2000, 3000, 4000, 5000, 6000)
        val depth = DepthPngReader.readDepthMap(pngBytes(3, 2, pixels))

        assertEquals(3, depth.width)
        assertEquals(2, depth.height)
        assertEquals(1000, depth.at(0, 0))
        assertEquals(3000, depth.at(2, 0))
        assertEquals(6000, depth.at(2, 1))
    }

    /**
     * The whole reason this decoder exists rather than BitmapFactory.
     *
     * These values differ only in their low byte. An 8-bit decode collapses all
     * three to the same reading, which is a 255 mm error spoken as fact.
     */
    @Test
    fun `keeps the low byte that an 8-bit decode would discard`() {
        val pixels = shortArrayOf(4000, 4001, 4255, 4256)
        val depth = DepthPngReader.readDepthMap(pngBytes(4, 1, pixels))

        assertEquals(4000, depth.at(0, 0))
        assertEquals(4001, depth.at(1, 0))
        assertEquals(4255, depth.at(2, 0))
        assertEquals(4256, depth.at(3, 0))
    }

    /** Distances past 32767 mm must not come back negative through Short. */
    @Test
    fun `reads values above the signed short range`() {
        val depth = DepthPngReader.readDepthMap(pngBytes(2, 1, shortArrayOf(40000.toShort(), 65535.toShort())))

        assertEquals(40000, depth.at(0, 0))
        assertEquals(65535, depth.at(1, 0))
    }

    /** Zero is *no reading*, never a distance of zero — see DepthMap. */
    @Test
    fun `zero decodes as no reading`() {
        val depth = DepthPngReader.readDepthMap(pngBytes(2, 1, shortArrayOf(0, 1500)))

        assertNull(depth.at(0, 0))
        assertEquals(1500, depth.at(1, 0))
    }

    /** Every PNG filter type must reconstruct the same pixels. */
    @Test
    fun `decodes every scanline filter identically`() {
        val pixels = ShortArray(6 * 4) { (300 + it * 137).toShort() }

        for (filter in 0..4) {
            val depth = DepthPngReader.readDepthMap(pngBytes(6, 4, pixels, filter = filter))
            for (y in 0 until 4) {
                for (x in 0 until 6) {
                    assertEquals(
                        "filter $filter at ($x, $y)",
                        pixels[y * 6 + x].toInt() and 0xFFFF,
                        depth.at(x, y),
                    )
                }
            }
        }
    }

    /** The board's real geometry, so a full-size frame is exercised once. */
    @Test
    fun `decodes a 640x480 frame`() {
        val pixels = ShortArray(640 * 480) { (it % 6000).toShort() }
        val depth = DepthPngReader.readDepthMap(pngBytes(640, 480, pixels, filter = 4))

        assertEquals(640, depth.width)
        assertEquals(480, depth.height)
        assertEquals(5000, depth.at(5000 % 640, 5000 / 640))
    }

    /**
     * An 8-bit file must throw rather than decode.
     *
     * This is the `app.md` JavaScript snippet's implied format (depth packed
     * into RGB bytes). If the board ever actually sent that, silently reading
     * it as millimetres would produce distances wrong by a factor of 256.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `refuses an 8-bit image rather than guessing millimetres`() {
        DepthPngReader.readDepthMap(pngBytes(2, 1, shortArrayOf(1, 2), bitDepth = 8))
    }

    /**
     * Cross-check against an encoder this repo did not write.
     *
     * Every other test here round-trips through [pngBytes], which is our own
     * code — so a misunderstanding of the format shared by encoder and decoder
     * would pass all of them and still misread the band. This fixture was
     * produced by Pillow (`Image.fromarray(uint16, mode='I;16').save(...)`),
     * the same 16-bit greyscale PNG the board's `pypng` writer emits, and its
     * expected values were read back independently by Pillow before being
     * pasted here.
     *
     * The values are chosen to catch the two mistakes that would otherwise look
     * plausible: byte order (256 and 1 are byte-swaps of each other) and
     * signedness (40000 and 65535 are negative as a Kotlin `Short`).
     */
    @Test
    fun `decodes a PNG written by an independent encoder`() {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADEAAAAADBDy1ZAAAAJElEQVR4nGNgYGBgZPjPyMDA" +
                "eod/YQPD//8McxwYddj/Mz8HAFPoCAo6JTSJAAAAAElFTkSuQmCC"
        )

        val depth = DepthPngReader.readDepthMap(png)

        assertEquals(4, depth.width)
        assertEquals(3, depth.height)

        val expected = listOf(
            0, 1, 255, 256,
            1500, 4001, 32768, 65535,
            40000, 300, 2047, 999,
        )
        for (y in 0 until 3) {
            for (x in 0 until 4) {
                val want = expected[y * 4 + x]
                assertEquals("at ($x, $y)", if (want == 0) null else want, depth.at(x, y))
            }
        }
    }

    /**
     * A PNG captured from the band itself, over the WebSocket, on 2026-07-19.
     *
     * The synthetic tests prove the decoder is self-consistent; this proves it
     * agrees with the hardware. The expected values were read out of the same
     * file by Pillow — an independent decoder — so a bug in this repo cannot
     * make both sides agree.
     *
     * The distances are the real check: a scene 2.0–4.1 m away. Every mistake
     * this decoder could plausibly make lands somewhere obviously wrong. Byte
     * order would report ~35 m, an 8-bit decode ~8 mm, treating the value as a
     * signed short would go negative past 32.7 m. Only the correct reading puts
     * a room-sized scene in a room-sized range.
     */
    @Test
    fun `decodes a real capture from the band`() {
        val file = java.io.File("src/test/fixtures/band_depth_640x480.png")
        assumeTrue("band fixture not present", file.exists())

        val depth = DepthPngReader.readDepthMap(file.readBytes())

        assertEquals(640, depth.width)
        assertEquals(480, depth.height)

        // Read back independently by Pillow from the same file.
        assertEquals(2187, depth.at(320, 240))
        assertNull(depth.at(0, 0))

        val valid = (0 until depth.height).flatMap { y ->
            (0 until depth.width).mapNotNull { x -> depth.at(x, y) }
        }
        assertEquals(293_688, valid.size)
        assertEquals(1987, valid.min())
        assertEquals(4140, valid.max())
        assertEquals(796_077_054L, valid.sumOf { it.toLong() })
    }

    /**
     * The whole path, wire to spoken clause, on real band bytes.
     *
     * The test above proves the pixels decode correctly; this proves the
     * decoded pixels are usable by the distance code that speaks to the user.
     * Without it the decoder could be perfectly correct and still hand
     * [RegionDistance] something it refuses to measure, and the band would go
     * silently numberless with every individual test passing.
     */
    @Test
    fun `a real band capture reaches a spoken distance`() {
        val file = java.io.File("src/test/fixtures/band_depth_640x480.png")
        assumeTrue("band fixture not present", file.exists())

        val readings = RegionDistance.measure(DepthPngReader.readDepthMap(file.readBytes()))
        val clause = DistanceSpeech.clauseFor(readings)

        val measured = readings.filter { it.isKnown }
        assertTrue("no region measured from a 95.6% valid frame", measured.isNotEmpty())
        measured.forEach {
            assertTrue(
                "${it.region} spoke ${it.millimetres} mm, outside the sensor's range",
                it.millimetres!! in 400..8_000,
            )
        }
        assertTrue("clause was blank", clause.isNotBlank())
        println("real band capture -> " + clause)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses something that is not a PNG`() {
        DepthPngReader.readDepthMap("not a png at all".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses an empty payload`() {
        DepthPngReader.readDepthMap(ByteArray(0))
    }
}
