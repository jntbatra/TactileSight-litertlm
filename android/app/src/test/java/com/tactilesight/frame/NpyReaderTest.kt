package com.tactilesight.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NpyReaderTest {

    /** Builds a NumPy v1 `<u2` file the way the band's capture script writes one. */
    private fun npyBytes(
        width: Int,
        height: Int,
        pixels: ShortArray,
        descr: String = "<u2",
    ): ByteArray {
        val header = "{'descr': '$descr', 'fortran_order': False, 'shape': ($height, $width), }"
        // The header is padded so the data starts on a 64-byte boundary.
        val unpadded = 6 + 2 + 2 + header.length + 1
        val padding = (64 - unpadded % 64) % 64
        val paddedHeader = header + " ".repeat(padding) + "\n"

        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
            'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
        out.write(1); out.write(0)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(paddedHeader.length.toShort()).array())
        out.write(paddedHeader.toByteArray())
        val data = ByteBuffer.allocate(pixels.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pixels.forEach { data.putShort(it) }
        out.write(data.array())
        return out.toByteArray()
    }

    @Test
    fun `reads shape and millimetre values`() {
        val bytes = npyBytes(3, 2, shortArrayOf(1000, 2000, 3000, 4000, 5000, 6000))

        val depth = NpyReader.readDepthMap(ByteArrayInputStream(bytes))

        assertEquals(3, depth.width)
        assertEquals(2, depth.height)
        assertEquals(1000, depth.at(0, 0))
        assertEquals(3000, depth.at(2, 0))
        assertEquals(6000, depth.at(2, 1))
    }

    @Test
    fun `zero means no reading, not a distance of zero`() {
        val depth = NpyReader.readDepthMap(
            ByteArrayInputStream(npyBytes(2, 1, shortArrayOf(0, 1500))),
        )

        assertNull("a depth hole must not read as 0 mm", depth.at(0, 0))
        assertEquals(1500, depth.at(1, 0))
    }

    /** uint16 above 32767 wraps negative in a signed Short — must survive. */
    @Test
    fun `distances beyond 32767 mm stay positive`() {
        val depth = NpyReader.readDepthMap(
            ByteArrayInputStream(npyBytes(1, 1, shortArrayOf(40000.toShort()))),
        )

        assertEquals(40000, depth.at(0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a dtype it cannot read rather than misreading depth`() {
        NpyReader.readDepthMap(
            ByteArrayInputStream(npyBytes(1, 1, shortArrayOf(1), descr = "<f4")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a file that is not npy`() {
        NpyReader.readDepthMap(ByteArrayInputStream("not an npy file".toByteArray()))
    }

    /**
     * The real thing: a capture straight from the band, still in the APK assets.
     * Guards against the synthetic fixture drifting from what the band writes.
     */
    @Test
    fun `parses a real Astra Pro Plus capture`() {
        val captures = File("src/main/assets/captures")
        // Only skip when the assets are genuinely absent (e.g. a source-only
        // checkout). If they exist, a rename must fail loudly rather than
        // quietly skipping and still reading green.
        assumeTrue("assets not present at ${captures.absolutePath}", captures.isDirectory)

        val file = captures.listFiles()?.sorted()?.firstNotNullOfOrNull { dir ->
            File(dir, "depth_raw.npy").takeIf { it.exists() }
        }
        assertNotNull("no depth_raw.npy under ${captures.absolutePath}", file)

        val depth = file!!.inputStream().use(NpyReader::readDepthMap)

        assertEquals(640, depth.width)
        assertEquals(480, depth.height)

        val valid = (0 until depth.height).sumOf { y ->
            (0 until depth.width).count { x -> depth.at(x, y) != null }
        }
        val total = depth.width * depth.height
        assertTrue("expected some valid depth, got $valid/$total", valid > 0)

        // ADR-0013: metric depth spans 0.45–9.94 m across the 21 captures.
        val readings = (0 until depth.height).flatMap { y ->
            (0 until depth.width).mapNotNull { x -> depth.at(x, y) }
        }
        assertTrue("min depth ${readings.min()} mm out of range", readings.min() >= 100)
        assertTrue("max depth ${readings.max()} mm out of range", readings.max() <= 15000)

        println("${file.parentFile?.name}: ${valid * 100 / total}% valid, " +
            "${readings.min()}–${readings.max()} mm")
    }
}
