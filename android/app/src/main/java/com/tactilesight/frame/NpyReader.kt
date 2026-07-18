package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the band's `depth_raw.npy` — NumPy format v1, `<u2` (little-endian
 * uint16), shape (480, 640), millimetres.
 *
 * We parse the format directly rather than converting the captures at build
 * time: the band writes `.npy`, so keeping the app able to read it means the
 * live WebRTC path and the bundled path stay the same code.
 *
 * Only the one dtype we actually ship is supported — anything else throws
 * loudly rather than silently misreading depth, which would produce confident
 * wrong distances. That is the failure mode ADR-0013 exists to prevent.
 */
object NpyReader {

    private val MAGIC = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
        'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())

    fun readDepthMap(stream: InputStream): DepthMap {
        val magic = ByteArray(6).also { stream.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "not a .npy file" }

        stream.read() // major version
        stream.read() // minor version

        // v1 header length is a 2-byte little-endian unsigned short.
        val lengthBytes = ByteArray(2).also { stream.readFully(it) }
        val headerLength = ByteBuffer.wrap(lengthBytes)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        val header = String(ByteArray(headerLength).also { stream.readFully(it) })

        require("'descr': '<u2'" in header) {
            "expected little-endian uint16 depth, got header: $header"
        }
        require("'fortran_order': False" in header) {
            "expected C ordering, got header: $header"
        }

        val (height, width) = parseShape(header)

        val pixels = ShortArray(width * height)
        val buffer = ByteArray(pixels.size * 2).also { stream.readFully(it) }
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pixels)

        return DepthMap(width = width, height = height, millimetres = pixels)
    }

    /** Pulls (rows, cols) out of e.g. `'shape': (480, 640), `. */
    private fun parseShape(header: String): Pair<Int, Int> {
        val match = Regex("""'shape':\s*\((\d+),\s*(\d+)\)""").find(header)
            ?: error("could not read shape from header: $header")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** InputStream.read may return short; depth data must be read whole. */
    private fun InputStream.readFully(into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val read = read(into, offset, into.size - offset)
            if (read < 0) error("unexpected end of .npy after $offset of ${into.size} bytes")
            offset += read
        }
    }
}
