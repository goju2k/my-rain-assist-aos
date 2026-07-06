package com.goju.ribs.myrainassist.data

import android.graphics.Bitmap

/**
 * Converts a decoded radar frame PNG into the row-major presence byte array [PresenceGrid]
 * expects: a pixel is "raining" if its alpha channel is non-zero, "no rain / no data" otherwise.
 * The PNG's palette colors only distinguish rendering intensity, not data validity, so alpha is
 * the only reliable presence signal.
 */
object RadarPngDecoder {

    /** Matches [com.goju.ribs.myrainassist.analysis.PresenceGrid]'s "no rain / no data" sentinel. */
    private const val NO_RAIN: Byte = 0xFF.toByte()
    private const val RAIN: Byte = 0

    fun decode(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArray(pixels.size) { i ->
            val alpha = (pixels[i] ushr 24) and 0xFF
            if (alpha != 0) RAIN else NO_RAIN
        }
    }
}
