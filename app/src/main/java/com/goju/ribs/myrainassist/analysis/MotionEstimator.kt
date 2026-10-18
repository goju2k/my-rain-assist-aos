package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarLegend
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Per-cell rain intensity on a rough log scale for motion matching: 0 = no rain (or no data),
 * 1 = the faintest 0.1 mm/h tier ... 23 = the heaviest. Row-major, same layout as [PresenceGrid].
 * Reads outside the grid return 0.
 */
class LevelGrid(val width: Int, val height: Int, private val levels: ByteArray) {

    fun at(row: Int, col: Int): Int =
        if (row in 0 until height && col in 0 until width) levels[row * width + col].toInt() else 0

    /**
     * Block-mean downsample by [factor]. Block boundaries are aligned so that a block starts at
     * row [phaseRow] / col [phaseCol]: the original cell (r,c) lands in coarse cell
     * ((r - phaseRow).floorDiv(factor) + 1, (c - phaseCol).floorDiv(factor) + 1).
     */
    fun downsample(factor: Int, phaseRow: Int, phaseCol: Int): LevelGrid {
        val outW = (width - 1 - phaseCol) / factor + 2
        val outH = (height - 1 - phaseRow) / factor + 2
        val out = ByteArray(outW * outH)
        for (oy in 0 until outH) for (ox in 0 until outW) {
            val rowStart = phaseRow + (oy - 1) * factor
            val colStart = phaseCol + (ox - 1) * factor
            var sum = 0
            for (dy in 0 until factor) for (dx in 0 until factor) sum += at(rowStart + dy, colStart + dx)
            out[oy * outW + ox] = ((sum + factor * factor / 2) / (factor * factor)).toByte()
        }
        return LevelGrid(outW, outH, out)
    }

    companion object {
        fun from(grid: PresenceGrid): LevelGrid = LevelGrid(
            grid.width,
            grid.height,
            ByteArray(grid.width * grid.height) { i -> levelForIndex(grid.valueAt(i / grid.width, i % grid.width)).toByte() },
        )

        fun levelForIndex(index: Int): Int = if (index <= RadarLegend.RAIN_THRESHOLD_INDEX) 23 - index else 0
    }
}

/**
 * Estimates each blob's velocity in grid cells/minute (TREC-style): the blob's own intensity
 * pattern, inside a padded bounding box, is compared against each earlier frame at every
 * plausible shift, scoring the sum of absolute intensity differences over the WHOLE box — so
 * shifting the blob into an area that was raining before but isn't part of the blob now costs as
 * much as missing the blob's own cells. (The previous presence-overlap score rewarded any shift
 * that landed inside a bigger earlier rain area, broke ties toward the search corner, and capped
 * the search at ~34 km/h — tuned for an 8x-downsampled grid the app no longer uses.) Searches up to
 * [MAX_SPEED_KMH], coarse-to-fine for large boxes. Per-frame estimates are combined with a median
 * for robustness against any single spurious match.
 */
object MotionEstimator {

    /** One earlier frame's actual match result — a real observation, not a linear-model guess. */
    data class FrameMatch(val minutesAgo: Long, val dxCells: Int, val dyCells: Int, val confidence: Double)

    /** [trackedSpanMinutes] is the longest confirmed match span (the oldest earlier frame this blob was actually matched against) — how far back its motion is backed by real observation, not just linear-model guesswork. */
    data class BlobMotion(
        val blob: Blob,
        val vxCellsPerMin: Double,
        val vyCellsPerMin: Double,
        val confidence: Double,
        val trackedSpanMinutes: Long,
        /** One entry per earlier frame that matched, oldest first — the blob's actual observed track, as opposed to the linear-model forward projection. */
        val matches: List<FrameMatch>,
    )

    // Fast frontal and typhoon rain bands over Korea run 50-80 km/h; anything beyond this is far
    // more likely a mismatch than real motion.
    const val MAX_SPEED_KMH = 100.0
    private const val PAD_CELLS = 6
    private const val COARSE_FACTOR = 4
    // Exhaustive full-resolution search while (box cells x shifts) stays under this; coarse-to-fine above.
    private const val EXHAUSTIVE_BUDGET = 1_000_000L
    // Follow-up frames search this fraction of their full range around the first frame's prediction
    // (lets speed/heading drift ~±25 km/h between frames).
    private const val FOLLOW_UP_RADIUS_FRACTION = 0.25
    private const val MIN_FOLLOW_UP_RADIUS = 3
    private const val MIN_SIMILARITY = 0.3

    private data class Match(val dx: Int, val dy: Int, val similarity: Double)

    /**
     * @param labels blob id per cell of the latest frame (see [ConnectedComponents.findBlobs]).
     * @param earlier earlier frames' level grids, same window as [latest].
     * @param minuteDeltas parallel to [earlier]: minutes between that frame and the latest (must be > 0).
     * @param kmPerCell approximate ground size of one cell, for turning [MAX_SPEED_KMH] into a search radius.
     * @param needsMotion blobs for which this returns false are reported stationary without
     *   searching — for blobs whose motion can't change the forecast, since a rainy area can hold
     *   hundreds of small cells and searching each one at full range is what dominates a cycle.
     */
    fun estimate(
        blobs: List<Blob>,
        labels: IntArray,
        latest: LevelGrid,
        earlier: List<LevelGrid>,
        minuteDeltas: List<Long>,
        kmPerCell: Double,
        needsMotion: (Blob) -> Boolean = { true },
    ): List<BlobMotion> {
        val coarseCache = earlier.map { HashMap<Int, LevelGrid>() }
        // Nearest frame first: its match (searched at full speed range) predicts where to look in
        // the older frames, which then only need a narrow search around that prediction.
        val order = earlier.indices.filter { minuteDeltas[it] > 0 }.sortedBy { minuteDeltas[it] }
        return blobs.map { blob ->
            if (!needsMotion(blob)) return@map BlobMotion(blob, 0.0, 0.0, 0.0, 0, emptyList())
            val matches = mutableListOf<FrameMatch>()
            for (index in order) {
                val deltaMinutes = minuteDeltas[index]
                val maxShift = ceil(MAX_SPEED_KMH / 60.0 * deltaMinutes / kmPerCell).toInt().coerceAtLeast(2)
                val first = matches.firstOrNull()
                val window = if (first == null) {
                    SearchWindow(0, 0, maxShift)
                } else {
                    SearchWindow(
                        (first.dxCells.toDouble() / first.minutesAgo * deltaMinutes).roundToInt(),
                        (first.dyCells.toDouble() / first.minutesAgo * deltaMinutes).roundToInt(),
                        ceil(maxShift * FOLLOW_UP_RADIUS_FRACTION).toInt().coerceAtLeast(MIN_FOLLOW_UP_RADIUS),
                    )
                }
                val match = matchBlob(blob, labels, latest, earlier[index], maxShift, window, coarseCache[index]) ?: continue
                matches.add(FrameMatch(deltaMinutes, match.dx, match.dy, match.similarity))
            }

            // No earlier frame matched well enough to back a velocity estimate — rather than
            // dropping the blob entirely (which would silently exclude it from arrival/ETA math
            // even while it sits right on top of the user), fall back to assuming it's
            // stationary. That won't fabricate an approach that isn't backed by observation, but
            // a blob already over the user still reports it.
            if (matches.isEmpty()) {
                return@map BlobMotion(blob, 0.0, 0.0, 0.0, 0, emptyList())
            }
            BlobMotion(
                blob = blob,
                vxCellsPerMin = median(matches.map { it.dxCells / it.minutesAgo.toDouble() }),
                vyCellsPerMin = median(matches.map { it.dyCells / it.minutesAgo.toDouble() }),
                confidence = matches.map { it.confidence }.average(),
                trackedSpanMinutes = matches.maxOf { it.minutesAgo },
                matches = matches.sortedByDescending { it.minutesAgo },
            )
        }
    }

    /** Shifts to try: within [radius] of ([centerDx], [centerDy]). */
    private class SearchWindow(val centerDx: Int, val centerDy: Int, val radius: Int)

    private class Box(val row0: Int, val col0: Int, val width: Int, val height: Int, val pattern: ByteArray) {
        val sum: Long = pattern.sumOf { it.toLong() }

        fun downsample(f: Int): Box {
            val w = (width + f - 1) / f
            val h = (height + f - 1) / f
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0
                for (dy in 0 until f) for (dx in 0 until f) {
                    val yy = y * f + dy
                    val xx = x * f + dx
                    if (yy < height && xx < width) s += pattern[yy * width + xx]
                }
                out[y * w + x] = ((s + f * f / 2) / (f * f)).toByte()
            }
            return Box(0, 0, w, h, out)
        }
    }

    /**
     * Finds the shift (dx,dy) such that latest(r,c) ≈ older(r-dy, c-dx) over the blob's padded
     * bounding box, i.e. the blob moved by (+dx,+dy) cells since [older].
     */
    private fun matchBlob(
        blob: Blob,
        labels: IntArray,
        latest: LevelGrid,
        older: LevelGrid,
        maxShift: Int,
        window: SearchWindow,
        coarseCache: HashMap<Int, LevelGrid>,
    ): Match? {
        var r0 = Int.MAX_VALUE
        var r1 = Int.MIN_VALUE
        var c0 = Int.MAX_VALUE
        var c1 = Int.MIN_VALUE
        for (cell in blob.cells) {
            r0 = minOf(r0, cell[0]); r1 = maxOf(r1, cell[0])
            c0 = minOf(c0, cell[1]); c1 = maxOf(c1, cell[1])
        }
        r0 = (r0 - PAD_CELLS).coerceAtLeast(0)
        r1 = (r1 + PAD_CELLS).coerceAtMost(latest.height - 1)
        c0 = (c0 - PAD_CELLS).coerceAtLeast(0)
        c1 = (c1 + PAD_CELLS).coerceAtMost(latest.width - 1)
        val bw = c1 - c0 + 1
        val bh = r1 - r0 + 1
        // The blob's own pattern; everything else in the box (other blobs, dry cells) counts as 0.
        val pattern = ByteArray(bw * bh) { i ->
            val r = r0 + i / bw
            val c = c0 + i % bw
            if (labels[r * latest.width + c] == blob.id) latest.at(r, c).toByte() else 0
        }
        val box = Box(r0, c0, bw, bh, pattern)

        val side = 2L * window.radius + 1
        var centerDx = window.centerDx
        var centerDy = window.centerDy
        var radius = window.radius
        if (bw.toLong() * bh * side * side > EXHAUSTIVE_BUDGET) {
            val f = COARSE_FACTOR
            val phaseRow = r0 % f
            val phaseCol = c0 % f
            val olderCoarse = coarseCache.getOrPut(phaseRow * f + phaseCol) { older.downsample(f, phaseRow, phaseCol) }
            val coarse = search(
                box.downsample(f), olderCoarse, (r0 - phaseRow) / f + 1, (c0 - phaseCol) / f + 1,
                Math.floorDiv(window.centerDx, f), Math.floorDiv(window.centerDy, f), (window.radius + f - 1) / f, (maxShift + f - 1) / f,
            )
            centerDx = coarse.dx * f
            centerDy = coarse.dy * f
            radius = f
        }
        val best = search(box, older, r0, c0, centerDx, centerDy, radius, maxShift)
        return if (best.similarity < MIN_SIMILARITY) null else best
    }

    /** SAD search over shifts within [radius] of (centerDx,centerDy), clamped to ±[maxShift]; ties go to the smaller shift. */
    private fun search(box: Box, older: LevelGrid, originRow: Int, originCol: Int, centerDx: Int, centerDy: Int, radius: Int, maxShift: Int): Match {
        var bestSad = Long.MAX_VALUE
        var bestDist = Int.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var bestOlderSum = 0L
        for (dy in (centerDy - radius).coerceAtLeast(-maxShift)..(centerDy + radius).coerceAtMost(maxShift)) {
            for (dx in (centerDx - radius).coerceAtLeast(-maxShift)..(centerDx + radius).coerceAtMost(maxShift)) {
                var sad = 0L
                var olderSum = 0L
                var aborted = false
                for (y in 0 until box.height) {
                    val olderRow = originRow + y - dy
                    val base = y * box.width
                    for (x in 0 until box.width) {
                        val b = older.at(olderRow, originCol + x - dx)
                        olderSum += b
                        sad += abs(box.pattern[base + x] - b)
                    }
                    if (sad > bestSad) {
                        aborted = true
                        break
                    }
                }
                if (aborted) continue
                val dist = dx * dx + dy * dy
                if (sad < bestSad || (sad == bestSad && dist < bestDist)) {
                    bestSad = sad
                    bestDist = dist
                    bestDx = dx
                    bestDy = dy
                    bestOlderSum = olderSum
                }
            }
        }
        val total = box.sum + bestOlderSum
        val similarity = if (total == 0L) 0.0 else 1.0 - bestSad.toDouble() / total
        return Match(bestDx, bestDy, similarity)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
