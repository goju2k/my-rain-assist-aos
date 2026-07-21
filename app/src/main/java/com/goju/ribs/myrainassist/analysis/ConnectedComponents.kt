package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarLegend

data class Blob(
    val id: Int,
    val cells: List<IntArray>,
    val centroidRow: Double,
    val centroidCol: Double,
    val sizeCells: Int,
    /** Heaviest rain tier found anywhere in this blob. */
    val peakMmh: Double,
)

/** 8-connected flood-fill blob extraction over a [PresenceGrid], dropping specks below [minSizeCells]. */
object ConnectedComponents {

    private val NEIGHBORS = arrayOf(
        -1 to -1, -1 to 0, -1 to 1,
        0 to -1, 0 to 1,
        1 to -1, 1 to 0, 1 to 1,
    )

    fun findBlobs(grid: PresenceGrid, minSizeCells: Int = 9): List<Blob> {
        val visited = Array(grid.height) { BooleanArray(grid.width) }
        val blobs = mutableListOf<Blob>()
        var nextId = 0

        for (row in 0 until grid.height) {
            for (col in 0 until grid.width) {
                if (visited[row][col] || !grid.isPresent(row, col)) continue

                val cells = mutableListOf<IntArray>()
                val correctedValues = mutableListOf<Int>()
                var weightSum = 0.0
                var rowAcc = 0.0
                var colAcc = 0.0
                var minIndexSeen = Int.MAX_VALUE
                val queue = ArrayDeque<IntArray>()
                queue.add(intArrayOf(row, col))
                visited[row][col] = true

                while (queue.isNotEmpty()) {
                    val (r, c) = queue.removeFirst()
                    cells.add(intArrayOf(r, c))
                    val value = grid.valueAt(r, c)
                    val correctedValue = grid.corroboratedValueAt(r, c)
                    correctedValues.add(correctedValue)
                    if (correctedValue < minIndexSeen) minIndexSeen = correctedValue
                    val weight = (255 - value).toDouble()
                    weightSum += weight
                    rowAcc += r * weight
                    colAcc += c * weight

                    for ((dr, dc) in NEIGHBORS) {
                        val nr = r + dr
                        val nc = c + dc
                        if (grid.inBounds(nr, nc) && !visited[nr][nc] && grid.isPresent(nr, nc)) {
                            visited[nr][nc] = true
                            queue.add(intArrayOf(nr, nc))
                        }
                    }
                }

                if (cells.size >= minSizeCells) {
                    val rawCentroidRow = if (weightSum > 0) rowAcc / weightSum else cells.map { it[0] }.average()
                    val rawCentroidCol = if (weightSum > 0) colAcc / weightSum else cells.map { it[1] }.average()
                    fun nearestTo(candidates: List<IntArray>): IntArray = candidates.minByOrNull { cell ->
                        val dr = cell[0] - rawCentroidRow
                        val dc = cell[1] - rawCentroidCol
                        dr * dr + dc * dc
                    }!!
                    // Korea's fronts mostly track west-to-east, which stretches a lot of blobs into
                    // long, thin shapes — the plain weighted-mean position ends up somewhere along
                    // that length that often doesn't read as "the cloud" on the map. When the blob
                    // actually varies in intensity, anchor on its most intense cell instead (ties
                    // broken by proximity to the mean, to stay representative rather than arbitrary)
                    // — that's the part a person looking at the radar would call the cloud's center.
                    // A uniformly-colored blob has no such peak to anchor on, so it keeps the old
                    // mean-position-snapped-to-nearest-cell behavior.
                    val hasIntensityVariation = correctedValues.any { it != correctedValues[0] }
                    val centroidCell = if (hasIntensityVariation) {
                        val peakCells = cells.indices.filter { correctedValues[it] == minIndexSeen }.map { cells[it] }
                        nearestTo(peakCells)
                    } else {
                        nearestTo(cells)
                    }
                    val centroidRow = centroidCell[0].toDouble()
                    val centroidCol = centroidCell[1].toDouble()
                    val peakMmh = RadarLegend.mmhForIndex(minIndexSeen)
                    blobs.add(Blob(nextId++, cells, centroidRow, centroidCol, cells.size, peakMmh))
                }
            }
        }
        return blobs
    }
}
