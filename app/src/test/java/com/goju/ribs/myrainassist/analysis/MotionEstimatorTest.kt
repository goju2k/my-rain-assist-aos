package com.goju.ribs.myrainassist.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEstimatorTest {

    // A blob with no corresponding rain anywhere in the earlier frame — BlockMatcher.match will
    // never clear MIN_CONFIDENCE, so this exercises the "no earlier frame matched" fallback.
    private fun blobWithNoHistory(): Blob {
        val cells = listOf(intArrayOf(2, 2), intArrayOf(2, 3), intArrayOf(3, 2), intArrayOf(3, 3))
        return Blob(id = 0, cells = cells, centroidRow = 2.5, centroidCol = 2.5, sizeCells = cells.size, peakMmh = 1.0)
    }

    private fun emptyGrid(): PresenceGrid = PresenceGrid(10, 10, ByteArray(100) { 23 })

    @Test
    fun `unmatched blob falls back to stationary motion instead of being dropped`() {
        val blob = blobWithNoHistory()

        val motions = MotionEstimator.estimate(
            blobs = listOf(blob),
            earlierGrids = listOf(emptyGrid()),
            minuteDeltas = listOf(5L),
        )

        assertEquals(1, motions.size)
        val motion = motions[0]
        assertEquals(0.0, motion.vxCellsPerMin, 0.0)
        assertEquals(0.0, motion.vyCellsPerMin, 0.0)
        assertEquals(0.0, motion.confidence, 0.0)
        assertEquals(0L, motion.trackedSpanMinutes)
        assertTrue(motion.matches.isEmpty())
    }
}
