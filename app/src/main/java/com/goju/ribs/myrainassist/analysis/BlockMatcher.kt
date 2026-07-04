package com.goju.ribs.myrainassist.analysis

/**
 * TREC-lite block matching: finds the integer cell shift (dx,dy) that best explains a blob's
 * cells (from the latest frame) as having moved from an earlier frame, by maximizing overlap
 * between the blob shifted back by (dx,dy) and the earlier frame's presence mask.
 */
object BlockMatcher {

    data class MatchResult(val dx: Int, val dy: Int, val confidence: Double)

    private const val MIN_CONFIDENCE = 0.3

    fun match(blob: Blob, older: PresenceGrid, searchRadius: Int): MatchResult? {
        var bestScore = -1
        var bestDx = 0
        var bestDy = 0

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var score = 0
                for (cell in blob.cells) {
                    val or = cell[0] - dy
                    val oc = cell[1] - dx
                    if (older.inBounds(or, oc) && older.isPresent(or, oc)) score++
                }
                if (score > bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val confidence = bestScore.toDouble() / blob.sizeCells
        return if (confidence < MIN_CONFIDENCE) null else MatchResult(bestDx, bestDy, confidence)
    }
}
