package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarFrame

/**
 * Wraps a radar frame's raw byte grid. Byte value 255 means "no precipitation echo"; any value
 * 2..250 means an echo is present at that cell. Values outside this range were never observed in
 * sampled real responses, so they are treated as "no data" (not present) rather than guessed at.
 */
class PresenceGrid(val width: Int, val height: Int, private val data: ByteArray) {

    fun valueAt(row: Int, col: Int): Int = data[row * width + col].toInt() and 0xFF

    fun isPresent(row: Int, col: Int): Boolean {
        val v = valueAt(row, col)
        return v in 2..250
    }

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    companion object {
        fun from(frame: RadarFrame): PresenceGrid = PresenceGrid(frame.gridWidth, frame.gridHeight, frame.grid)
    }
}
