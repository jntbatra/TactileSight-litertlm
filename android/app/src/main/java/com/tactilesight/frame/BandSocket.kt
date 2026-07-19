package com.tactilesight.frame

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.io.Closeable

/**
 * The smallest WebSocket client that can receive one capture bundle from the
 * band, spoken over a plain socket.
 *
 * ### Why hand-rolled
 *
 * There is no WebSocket client on this app's classpath — no OkHttp, no Ktor —
 * and the app already hand-rolls HTTP against `HttpURLConnection` rather than
 * taking a library for it (`brain/ServerCheck.kt`, `speech/SarvamAsr.kt`).
 * Pulling in OkHttp to read a few thousand frames of JSON would add a
 * dependency and an APK's worth of transitive code to solve a problem that is
 * one handshake and one frame header wide.
 *
 * This is deliberately *not* a general WebSocket implementation. It reads;
 * it never sends application data, so client-side masking never has to be
 * correct for anything but the close frame. It does not reconnect, because
 * capture is on demand (ADR-0009) and a socket per press is simpler to reason
 * about than a socket that might have gone stale between presses.
 *
 * ### Ownership
 *
 * The caller opens, reads one message, and closes. [close] is safe to call
 * twice and never throws, so a failed read still releases the socket.
 */
class BandSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {

    /**
     * Read one whole application message as bytes.
     *
     * Handles the two things the board's `websockets` server legitimately does
     * that a naive reader gets wrong: it may split a large bundle across
     * continuation frames, and it may interleave a ping that must not be
     * mistaken for payload. A 640×480 depth PNG plus a JPEG is comfortably over
     * any fragmentation threshold, so this is a real case, not defensive
     * padding.
     */
    fun readMessage(): ByteArray {
        val message = ByteArrayOutputStream()

        while (true) {
            val first = readByte()
            val isFinal = first and 0x80 != 0
            val opcode = first and 0x0F

            val second = readByte()
            val isMasked = second and 0x80 != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte().toLong() shl 8) or readByte().toLong())
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            require(length <= MAX_MESSAGE_BYTES) { "band sent an implausible $length byte frame" }

            // A conforming server never masks, but unmasking costs nothing and
            // a wrong assumption here would corrupt depth rather than fail.
            val mask = if (isMasked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            mask?.let { for (i in payload.indices) payload[i] = (payload[i].toInt() xor it[i % 4].toInt()).toByte() }

            when (opcode) {
                OPCODE_CLOSE -> error("band closed the connection before sending a capture")
                OPCODE_PING, OPCODE_PONG -> continue // not payload; keep waiting
                else -> {
                    message.write(payload)
                    if (isFinal) return message.toByteArray()
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) error("band closed the connection mid-frame")
        return value
    }

    /** `read` may return short; a depth frame must be read whole. */
    private fun readFully(into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) error("band closed the connection after $offset of ${into.size} bytes")
            offset += read
        }
    }

    companion object {

        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        /**
         * A sane ceiling for one bundle. A verified capture is ~117 kB; this
         * bounds a corrupt length field so it fails instead of trying to
         * allocate gigabytes on a phone.
         */
        private const val MAX_MESSAGE_BYTES = 32L * 1024 * 1024

        /**
         * Connect and complete the opening handshake.
         *
         * [timeoutMillis] bounds both the connect and every subsequent read: a
         * venue network that accepts the TCP connection and then goes quiet is
         * the failure this guards, and it is more likely than an outright
         * refusal.
         */
        fun connect(host: String, port: Int, timeoutMillis: Int): BandSocket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis

                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    .let { Base64.getEncoder().encodeToString(it) }

                output.write(
                    buildString {
                        append("GET / HTTP/1.1\r\n")
                        append("Host: $host:$port\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $key\r\n")
                        append("Sec-WebSocket-Version: 13\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII),
                )
                output.flush()

                val status = readHandshake(input)
                check("101" in status.substringBefore("\r\n")) {
                    "band refused the WebSocket upgrade: ${status.substringBefore("\r\n")}"
                }

                return BandSocket(socket, input, output)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }

        /**
         * Read exactly up to the end of the HTTP response headers.
         *
         * Byte at a time on purpose: the first WebSocket frame can follow the
         * header terminator immediately, and a buffered reader would swallow it
         * into a buffer this class then has no way to read back.
         */
        private fun readHandshake(input: InputStream): String {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val value = input.read()
                if (value < 0) error("band closed the connection during the handshake")
                header.append(value.toChar())
                require(header.length <= 8192) { "band sent an oversized handshake response" }
            }
            return header.toString()
        }
    }
}
