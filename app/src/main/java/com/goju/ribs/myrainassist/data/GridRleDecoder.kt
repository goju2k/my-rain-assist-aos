package com.goju.ribs.myrainassist.data

/**
 * Decodes a radar frame's grid bytes, transparently handling both formats seen from the API:
 * - legacy raw format: exactly `width*height` bytes, one per cell
 * - RLE format: a 1-byte header followed by (value: u8, count: u16 little-endian) triplets whose
 *   counts sum to `width*height`
 *
 * The API currently returns a mix of both within the same response (older cached frames stay raw,
 * newer frames are RLE-compressed), so format is detected per-frame rather than assumed.
 */
object GridRleDecoder {

    fun decode(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val expectedSize = width * height
        if (bytes.size == expectedSize) return bytes

        require(bytes.size >= 1) { "Grid data too short to contain an RLE header" }
        val body = bytes.size - 1
        require(body % 3 == 0) { "RLE body length $body is not a multiple of 3" }

        val out = ByteArray(expectedSize)
        var outPos = 0
        var i = 1
        while (i < bytes.size) {
            val value = bytes[i]
            val count = (bytes[i + 1].toInt() and 0xFF) or ((bytes[i + 2].toInt() and 0xFF) shl 8)
            val end = (outPos + count).coerceAtMost(expectedSize)
            while (outPos < end) {
                out[outPos] = value
                outPos++
            }
            i += 3
        }
        require(outPos == expectedSize) { "RLE run lengths summed to $outPos, expected $expectedSize" }
        return out
    }
}
