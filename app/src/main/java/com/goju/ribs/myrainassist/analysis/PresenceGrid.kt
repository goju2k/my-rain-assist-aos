package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarFrame

/**
 * Wraps a radar frame's raw byte grid, decoded from the source PNG's alpha channel by
 * [com.goju.ribs.myrainassist.data.RadarPngDecoder]: byte value 255 means "no rain / no data" (a
 * transparent pixel), byte value 0 means "raining" (an opaque pixel, regardless of its color).
 */
class PresenceGrid(val width: Int, val height: Int, private val data: ByteArray) {

    fun valueAt(row: Int, col: Int): Int = data[row * width + col].toInt() and 0xFF

    fun isPresent(row: Int, col: Int): Boolean = valueAt(row, col) != NO_RAIN_VALUE

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    companion object {
        private const val NO_RAIN_VALUE = 255

        fun from(frame: RadarFrame): PresenceGrid = PresenceGrid(frame.gridWidth, frame.gridHeight, frame.grid)
    }
}
