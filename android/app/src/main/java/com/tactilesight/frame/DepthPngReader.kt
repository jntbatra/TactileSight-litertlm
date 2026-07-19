package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Reads the band's `depth_b64` — a 16-bit greyscale PNG where each pixel value
 * *is* the distance in millimetres.
 *
 * ### Why this is hand-rolled rather than handed to BitmapFactory
 *
 * Android's PNG decoder cannot give back 16 bits per channel. `BitmapFactory`
 * decodes into `ARGB_8888` (or at best `RGBA_F16`, which is a *half float* and
 * normalised), so a 16-bit greyscale PNG comes back with its low byte thrown
 * away — 4000 mm decodes to the 8-bit value 15, and 4001 mm decodes to the same
 * 15. The reading survives; the precision does not. Rounding every distance to
 * the nearest 256 mm and then speaking it aloud is exactly the confident-wrong
 * number ADR-0013 exists to prevent, and it would have been invisible in
 * testing because the depth *preview* would still have looked correct.
 *
 * So the pixels are inflated and unfiltered here, which is the only way to see
 * the full 16 bits on this platform. It costs one small class and no new
 * dependency.
 *
 * ### What the board actually sends, and how we know
 *
 * `linux/haptic_depth_server.py` encodes with
 * `png.Writer(width=640, height=480, bitdepth=16, greyscale=True)` over the raw
 * `uint16` OpenNI depth buffer, so the value is millimetres with no scale
 * factor and 0 means *no reading*. A live capture from the board confirms the
 * IHDR: 640×480, bit depth 16, colour type 0, non-interlaced.
 *
 * Note this contradicts the JavaScript snippet in the board's `app.md`, which
 * reads `R = high byte, G = low byte` as though the depth were packed into an
 * 8-bit RGB image. That snippet is wrong — it describes what a browser *canvas*
 * hands back after it has already down-converted the real 16-bit file. The
 * prose in the same document ("16-bit grayscale — pixel value = distance in
 * mm") is correct, and matches both the encoder and the bytes on the wire.
 *
 * Anything other than that exact format throws rather than being decoded on a
 * guess: a silently misread depth map is the one failure mode that produces
 * plausible wrong distances instead of an obvious error.
 */
object DepthPngReader {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Bytes per pixel: one 16-bit greyscale channel. Drives the PNG filters. */
    private const val BYTES_PER_PIXEL = 2

    fun readDepthMap(png: ByteArray): DepthMap {
        require(png.size > SIGNATURE.size) { "depth PNG is empty" }
        require(png.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
            "not a PNG — first bytes were ${png.take(8).joinToString(" ") { "%02x".format(it) }}"
        }

        var offset = SIGNATURE.size
        var width = 0
        var height = 0
        val compressed = ByteArrayOutputStream()

        // Walk the chunk list. IDAT may be split across any number of chunks
        // and must be inflated as one continuous zlib stream, which is why the
        // parts are gathered before anything is decompressed.
        while (offset + 8 <= png.size) {
            val length = readInt(png, offset)
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val dataAt = offset + 8
            require(dataAt + length <= png.size) { "truncated $type chunk in depth PNG" }

            when (type) {
                "IHDR" -> {
                    width = readInt(png, dataAt)
                    height = readInt(png, dataAt + 4)
                    val bitDepth = png[dataAt + 8].toInt() and 0xFF
                    val colourType = png[dataAt + 9].toInt() and 0xFF
                    val interlace = png[dataAt + 12].toInt() and 0xFF

                    require(bitDepth == 16 && colourType == 0) {
                        "expected 16-bit greyscale depth, got bit depth $bitDepth " +
                            "colour type $colourType — refusing to guess millimetres"
                    }
                    require(interlace == 0) {
                        "interlaced depth PNG is not supported (the band does not send one)"
                    }
                }

                "IDAT" -> compressed.write(png, dataAt, length)

                "IEND" -> return DepthMap(
                    width = width,
                    height = height,
                    millimetres = unfilter(inflate(compressed.toByteArray()), width, height),
                )
            }

            offset = dataAt + length + 4 // + CRC, which zlib already covers for us
        }

        error("depth PNG ended without an IEND chunk")
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val produced = inflater.inflate(buffer)
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    error("depth PNG pixel data is incomplete")
                }
                out.write(buffer, 0, produced)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /**
     * Reverse the per-scanline PNG filters and read big-endian uint16 pixels.
     *
     * Every scanline carries a leading filter byte and is reconstructed against
     * the *already reconstructed* line above it, so this cannot be parallelised
     * or skipped — filter type 0 on one line says nothing about the next.
     */
    private fun unfilter(raw: ByteArray, width: Int, height: Int): ShortArray {
        val stride = width * BYTES_PER_PIXEL
        require(raw.size >= (stride + 1) * height) {
            "depth PNG has ${raw.size} bytes of pixel data, expected ${(stride + 1) * height} " +
                "for ${width}x$height"
        }

        val pixels = ShortArray(width * height)
        var previous = ByteArray(stride)
        val current = ByteArray(stride)

        for (y in 0 until height) {
            val lineAt = y * (stride + 1)
            val filter = raw[lineAt].toInt() and 0xFF
            System.arraycopy(raw, lineAt + 1, current, 0, stride)

            for (x in 0 until stride) {
                val a = if (x >= BYTES_PER_PIXEL) current[x - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                val b = previous[x].toInt() and 0xFF
                val c = if (x >= BYTES_PER_PIXEL) previous[x - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                val value = current[x].toInt() and 0xFF

                current[x] = when (filter) {
                    0 -> value
                    1 -> value + a
                    2 -> value + b
                    3 -> value + (a + b) / 2
                    4 -> value + paeth(a, b, c)
                    else -> error("unknown PNG filter type $filter on row $y")
                }.toByte()
            }

            // Big-endian, per the PNG spec: high byte first.
            for (x in 0 until width) {
                val hi = current[x * 2].toInt() and 0xFF
                val lo = current[x * 2 + 1].toInt() and 0xFF
                pixels[y * width + x] = ((hi shl 8) or lo).toShort()
            }

            previous = current.copyOf()
        }

        return pixels
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
