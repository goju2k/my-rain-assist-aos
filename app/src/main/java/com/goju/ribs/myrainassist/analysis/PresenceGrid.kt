package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarLegend

/**
 * Wraps a radar frame's raw byte grid, decoded from the source PNG by
 * [com.goju.ribs.myrainassist.data.RadarPngDecoder]: each cell holds a [RadarLegend] color-table
 * index (0 = heaviest rain tier, 23 = the rendered "0 mm/h" tier, [RadarLegend.NO_DATA_INDEX] =
 * no data / outside coverage).
 */
class PresenceGrid(val width: Int, val height: Int, private val data: ByteArray) {

    fun valueAt(row: Int, col: Int): Int = data[row * width + col].toInt() and 0xFF

    /** True for any real precipitation tier — excludes both the "0 mm/h" tier and no-data. */
    fun isPresent(row: Int, col: Int): Boolean = valueAt(row, col) <= RadarLegend.RAIN_THRESHOLD_INDEX

    fun mmhAt(row: Int, col: Int): Double = RadarLegend.mmhForIndex(valueAt(row, col))

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    companion object {
        fun from(frame: RadarFrame): PresenceGrid = PresenceGrid(frame.gridWidth, frame.gridHeight, frame.grid)
    }
}
