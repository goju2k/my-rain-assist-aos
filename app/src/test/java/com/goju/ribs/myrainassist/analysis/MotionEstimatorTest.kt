package com.goju.ribs.myrainassist.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEstimatorTest {

    private val size = 160
    private val kmPerCell = 0.705

    /** Legend-index grid with rectangles (row, col, height, width) painted at index 10 (15 mm/h). */
    private fun grid(vararg rects: IntArray): PresenceGrid {
        val data = ByteArray(size * size) { 23 }
        for (rect in rects) {
            val (r0, c0, h, w) = rect
            for (r in r0 until r0 + h) for (c in c0 until c0 + w) data[r * size + c] = 10
        }
        return PresenceGrid(size, size, data)
    }

    private fun estimate(latest: PresenceGrid, vararg earlier: Pair<PresenceGrid, Long>): List<MotionEstimator.BlobMotion> {
        val labels = IntArray(size * size) { -1 }
        val blobs = ConnectedComponents.findBlobs(latest, 9, labels)
        return MotionEstimator.estimate(
            blobs = blobs,
            labels = labels,
            latest = LevelGrid.from(latest),
            earlier = earlier.map { LevelGrid.from(it.first) },
            minuteDeltas = earlier.map { it.second },
            kmPerCell = kmPerCell,
        )
    }

    @Test
    fun `unmatched blob falls back to stationary motion instead of being dropped`() {
        val motions = estimate(grid(intArrayOf(10, 10, 4, 4)), grid() to 5L)

        assertEquals(1, motions.size)
        val motion = motions[0]
        assertEquals(0.0, motion.vxCellsPerMin, 0.0)
        assertEquals(0.0, motion.vyCellsPerMin, 0.0)
        assertEquals(0L, motion.trackedSpanMinutes)
        assertTrue(motion.matches.isEmpty())
    }

    @Test
    fun `tracks a fast echo well beyond the old 34 kmh search cap`() {
        // 7 cells east per 5 min ≈ 59 km/h, seen in three earlier frames.
        val latest = grid(intArrayOf(70, 90, 12, 12))
        val motion = estimate(
            latest,
            grid(intArrayOf(70, 83, 12, 12)) to 5L,
            grid(intArrayOf(70, 76, 12, 12)) to 10L,
            grid(intArrayOf(70, 69, 12, 12)) to 15L,
        ).single()

        assertEquals(7.0 / 5.0, motion.vxCellsPerMin, 1e-9)
        assertEquals(0.0, motion.vyCellsPerMin, 1e-9)
        assertEquals(15L, motion.trackedSpanMinutes)
        assertTrue(motion.vxCellsPerMin * kmPerCell * 60 > 55)
    }

    @Test
    fun `large blob is tracked through the coarse-to-fine path`() {
        // Big enough that the exhaustive search budget is exceeded, moving 8 east / 6 south
        // (10 cells ≈ 7 km in 5 min ≈ 85 km/h, inside the 100 km/h cap).
        val latest = grid(intArrayOf(60, 60, 60, 70))
        val motion = estimate(latest, grid(intArrayOf(54, 52, 60, 70)) to 5L).single()

        assertEquals(8.0 / 5.0, motion.vxCellsPerMin, 1e-9)
        assertEquals(6.0 / 5.0, motion.vyCellsPerMin, 1e-9)
    }

    @Test
    fun `motion faster than the speed cap is not reported`() {
        // 9 east / 9 south in 5 min ≈ 107 km/h — over MAX_SPEED_KMH even though each axis alone is
        // within it; the old square search window accepted this.
        val latest = grid(intArrayOf(60, 60, 30, 30))
        val motion = estimate(latest, grid(intArrayOf(51, 51, 30, 30)) to 5L).single()

        val speedKmh = Math.hypot(motion.vxCellsPerMin, motion.vyCellsPerMin) * kmPerCell * 60
        assertTrue("speed $speedKmh", speedKmh <= MotionEstimator.MAX_SPEED_KMH)
    }

    @Test
    fun `blob sitting inside a wider earlier rain area is not dragged to the search corner`() {
        // The old overlap score gave every shift inside the earlier area a perfect score and
        // kept the first one tried — the top-left corner of the search window.
        val latest = grid(intArrayOf(75, 75, 6, 6))
        val motion = estimate(latest, grid(intArrayOf(40, 40, 80, 80)) to 5L).single()

        assertEquals(0.0, motion.vxCellsPerMin, 1e-9)
        assertEquals(0.0, motion.vyCellsPerMin, 1e-9)
    }
}
