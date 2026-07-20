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

    /**
     * Like [valueAt], but distrusts an "extreme" (index <= [RadarLegend.EXTREME_INDEX_THRESHOLD],
     * i.e. >=90 mm/h) reading unless at least [minExtremeNeighbors] of its 8 neighbors are also
     * extreme. A single anti-aliased/blended pixel can otherwise snap to the darkest legend colors
     * without any real intense rain around it. Falls back to the next-most-intense neighboring cell
     * when corroboration is missing, so the reading degrades to "still real rain, just not that
     * extreme" rather than vanishing outright.
     */
    fun corroboratedValueAt(row: Int, col: Int, minExtremeNeighbors: Int = 3): Int {
        val index = valueAt(row, col)
        if (index > RadarLegend.EXTREME_INDEX_THRESHOLD) return index

        var extremeNeighbors = 0
        var bestFallback = Int.MAX_VALUE
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = row + dr
                val nc = col + dc
                if (!inBounds(nr, nc)) continue
                val neighbor = valueAt(nr, nc)
                if (neighbor <= RadarLegend.EXTREME_INDEX_THRESHOLD) {
                    extremeNeighbors++
                } else if (neighbor < bestFallback) {
                    bestFallback = neighbor
                }
            }
        }
        return if (extremeNeighbors >= minExtremeNeighbors || bestFallback == Int.MAX_VALUE) index else bestFallback
    }

    fun corroboratedMmhAt(row: Int, col: Int, minExtremeNeighbors: Int = 3): Double =
        RadarLegend.mmhForIndex(corroboratedValueAt(row, col, minExtremeNeighbors))

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    companion object {
        fun from(frame: RadarFrame): PresenceGrid = PresenceGrid(frame.gridWidth, frame.gridHeight, frame.grid)
    }
}
