package com.goju.ribs.myrainassist.analysis

/**
 * Estimates each blob's velocity in grid cells/minute by matching the blob's cells (as found in
 * the latest frame) against each earlier frame independently, scaling the search radius with the
 * time gap. Multiple independent estimates are combined with a median for robustness against any
 * single spurious match.
 */
object MotionEstimator {

    /** One earlier frame's actual block-match result — a real observation, not a linear-model guess. */
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

    private const val BASE_SEARCH_RADIUS = 4

    /**
     * @param earlierGrids earlier frames' presence grids, any order.
     * @param minuteDeltas parallel to [earlierGrids]: minutes between that frame and the latest frame (must be > 0).
     */
    fun estimate(
        blobs: List<Blob>,
        earlierGrids: List<PresenceGrid>,
        minuteDeltas: List<Long>,
    ): List<BlobMotion> {
        return blobs.mapNotNull { blob ->
            val vxSamples = mutableListOf<Double>()
            val vySamples = mutableListOf<Double>()
            val confidences = mutableListOf<Double>()
            val matches = mutableListOf<FrameMatch>()

            earlierGrids.forEachIndexed { index, older ->
                val deltaMinutes = minuteDeltas[index]
                if (deltaMinutes <= 0) return@forEachIndexed
                val steps = (deltaMinutes / 5.0).coerceAtLeast(1.0)
                val searchRadius = (BASE_SEARCH_RADIUS * steps).toInt().coerceAtLeast(BASE_SEARCH_RADIUS)
                val match = BlockMatcher.match(blob, older, searchRadius) ?: return@forEachIndexed
                vxSamples.add(match.dx / deltaMinutes.toDouble())
                vySamples.add(match.dy / deltaMinutes.toDouble())
                confidences.add(match.confidence)
                matches.add(FrameMatch(deltaMinutes, match.dx, match.dy, match.confidence))
            }

            if (vxSamples.isEmpty()) return@mapNotNull null
            BlobMotion(
                blob = blob,
                vxCellsPerMin = median(vxSamples),
                vyCellsPerMin = median(vySamples),
                confidence = confidences.average(),
                trackedSpanMinutes = matches.maxOf { it.minutesAgo },
                matches = matches.sortedByDescending { it.minutesAgo },
            )
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
