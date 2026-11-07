package com.goju.ribs.myrainassist.analysis

import kotlin.math.ceil
import kotlin.math.exp

/**
 * Finds where a person looking at the radar map would say "there's a cloud": patches that are
 * darker (heavier) than their surroundings, at roughly the size of a rain cell.
 *
 * Connected areas don't work for this. In widespread rain everything >= 1 mm/h joins into one
 * area hundreds of km across, whose mean position (snapped to its nearest cell) often lands on a
 * pale gap between bands — one arrow, starting from somewhere that doesn't look like any cloud.
 * Instead this is a blob detector: a difference of Gaussians over the legend level (the color
 * steps the eye actually sees), whose local maxima sit on the dark centers of individual cells,
 * and which ignores flat areas however wide or heavy they are.
 */
object CloudPeaks {

    /** ~4 km — the size of a rain cell's dark center. */
    private const val SIGMA_SMALL_CELLS = 6.0
    /** ~14 km — the surroundings it has to stand out from. */
    private const val SIGMA_LARGE_CELLS = 20.0
    private const val MIN_SCORE = 0.5
    /** Peaks closer than this (~25 km) are the same cloud. */
    const val SEPARATION_CELLS = 35

    /**
     * Clouds this far (~80 km) from the user count half as much when choosing which to draw — the
     * map is looked at around the user, and a modest cloud next to them matters more than a
     * stronger one at the edge of the analyzed window.
     */
    private const val FOCUS_HALF_WEIGHT_CELLS = 113.0

    data class Peak(val row: Int, val col: Int, val score: Double)

    /**
     * At most [maxPeaks], each on a cell with legend index <= [maxIndex] that [accept]s it,
     * strongest (weighted toward [focusRow]/[focusCol]) first.
     */
    fun find(
        grid: PresenceGrid,
        maxIndex: Int,
        maxPeaks: Int,
        focusRow: Double,
        focusCol: Double,
        accept: (row: Int, col: Int) -> Boolean = { _, _ -> true },
    ): List<Peak> {
        val w = grid.width
        val h = grid.height
        val level = DoubleArray(w * h) { i -> LevelGrid.levelForIndex(grid.valueAt(i / w, i % w)).toDouble() }
        val small = blur(level, w, h, SIGMA_SMALL_CELLS)
        val large = blur(level, w, h, SIGMA_LARGE_CELLS)

        // Peaks by their own strength first, so each sits on its cloud's dark center; only then
        // pick which to draw with the distance weighting (applied earlier it would favor a strong
        // cloud's near flank over its center).
        val candidates = (0 until w * h).filter { i ->
            small[i] - large[i] > MIN_SCORE && grid.valueAt(i / w, i % w) <= maxIndex && accept(i / w, i % w)
        }.sortedByDescending { small[it] - large[it] }

        val peaks = mutableListOf<Peak>()
        val minDist2 = SEPARATION_CELLS * SEPARATION_CELLS
        for (i in candidates) {
            val row = i / w
            val col = i % w
            if (peaks.any { (it.row - row) * (it.row - row) + (it.col - col) * (it.col - col) <= minDist2 }) continue
            peaks.add(Peak(row, col, small[i] - large[i]))
        }
        fun rank(p: Peak): Double {
            val d2 = (p.row - focusRow) * (p.row - focusRow) + (p.col - focusCol) * (p.col - focusCol)
            return p.score / (1 + d2 / (FOCUS_HALF_WEIGHT_CELLS * FOCUS_HALF_WEIGHT_CELLS))
        }
        return peaks.sortedByDescending { rank(it) }.take(maxPeaks)
    }

    /** Separable Gaussian; outside the grid counts as dry (the analyzed window's edge is unknown, not rainy). */
    private fun blur(src: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        val radius = ceil(3 * sigma).toInt()
        val kernel = DoubleArray(2 * radius + 1) { k -> exp(-0.5 * ((k - radius) / sigma) * ((k - radius) / sigma)) }
        val sum = kernel.sum()
        for (k in kernel.indices) kernel[k] /= sum

        val tmp = DoubleArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            var acc = 0.0
            for (k in -radius..radius) {
                val cc = c + k
                if (cc in 0 until w) acc += src[r * w + cc] * kernel[k + radius]
            }
            tmp[r * w + c] = acc
        }
        val out = DoubleArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            var acc = 0.0
            for (k in -radius..radius) {
                val rr = r + k
                if (rr in 0 until h) acc += tmp[rr * w + c] * kernel[k + radius]
            }
            out[r * w + c] = acc
        }
        return out
    }
}
