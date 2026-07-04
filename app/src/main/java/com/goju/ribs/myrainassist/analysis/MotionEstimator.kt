package com.goju.ribs.myrainassist.analysis

/**
 * Estimates each blob's velocity in grid cells/minute by matching the blob's cells (as found in
 * the latest frame) against each earlier frame independently, scaling the search radius with the
 * time gap. Multiple independent estimates are combined with a median for robustness against any
 * single spurious match.
 */
object MotionEstimator {

    data class BlobMotion(val blob: Blob, val vxCellsPerMin: Double, val vyCellsPerMin: Double, val confidence: Double)

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

            earlierGrids.forEachIndexed { index, older ->
                val deltaMinutes = minuteDeltas[index]
                if (deltaMinutes <= 0) return@forEachIndexed
                val steps = (deltaMinutes / 5.0).coerceAtLeast(1.0)
                val searchRadius = (BASE_SEARCH_RADIUS * steps).toInt().coerceAtLeast(BASE_SEARCH_RADIUS)
                val match = BlockMatcher.match(blob, older, searchRadius) ?: return@forEachIndexed
                vxSamples.add(match.dx / deltaMinutes.toDouble())
                vySamples.add(match.dy / deltaMinutes.toDouble())
                confidences.add(match.confidence)
            }

            if (vxSamples.isEmpty()) return@mapNotNull null
            BlobMotion(
                blob = blob,
                vxCellsPerMin = median(vxSamples),
                vyCellsPerMin = median(vySamples),
                confidence = confidences.average(),
            )
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
