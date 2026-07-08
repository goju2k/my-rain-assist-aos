package com.goju.ribs.myrainassist.data

import android.graphics.Bitmap

/**
 * Converts a decoded radar frame PNG into the row-major byte grid [PresenceGrid] expects: each
 * cell holds the nearest [RadarLegend] color-table index for that pixel. A fully transparent
 * pixel (alpha 0) is the only reliable "no data" signal — within radar coverage the PNG always
 * paints an opaque color, even for the zero-rainfall tier.
 */
object RadarPngDecoder {

    fun decode(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArray(pixels.size) { i ->
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) {
                RadarLegend.NO_DATA_INDEX.toByte()
            } else {
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                RadarLegend.nearestIndex(r, g, b).toByte()
            }
        }
    }
}
