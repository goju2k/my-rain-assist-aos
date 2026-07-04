package com.goju.ribs.myrainassist.analysis

data class Blob(
    val id: Int,
    val cells: List<IntArray>,
    val centroidRow: Double,
    val centroidCol: Double,
    val sizeCells: Int,
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
                var weightSum = 0.0
                var rowAcc = 0.0
                var colAcc = 0.0
                val queue = ArrayDeque<IntArray>()
                queue.add(intArrayOf(row, col))
                visited[row][col] = true

                while (queue.isNotEmpty()) {
                    val (r, c) = queue.removeFirst()
                    cells.add(intArrayOf(r, c))
                    val weight = (255 - grid.valueAt(r, c)).toDouble()
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
                    val centroidRow = if (weightSum > 0) rowAcc / weightSum else cells.map { it[0] }.average()
                    val centroidCol = if (weightSum > 0) colAcc / weightSum else cells.map { it[1] }.average()
                    blobs.add(Blob(nextId++, cells, centroidRow, centroidCol, cells.size))
                }
            }
        }
        return blobs
    }
}
