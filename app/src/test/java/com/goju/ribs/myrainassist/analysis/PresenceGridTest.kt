package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.RadarLegend
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceGridTest {

    // 5x5 grid of "약한 비" (index 22 = 0.1mm/h) with a single isolated index-1 (90mm/h) pixel in
    // the middle, matching the pattern seen in the on-device log for the 2026-07-20 05:49 false
    // alarm: intensity read as 90mm/h, then reverted to 0.1mm/h six minutes later at the same spot.
    private fun weakGridWithLoneExtremePixel(): PresenceGrid {
        val data = ByteArray(25) { 22 }
        data[2 * 5 + 2] = 1
        return PresenceGrid(5, 5, data)
    }

    // Same size grid, but the extreme pixel is part of a genuine 2x2 intense core.
    private fun weakGridWithCorroboratedExtremeCluster(): PresenceGrid {
        val data = ByteArray(25) { 22 }
        data[2 * 5 + 2] = 1
        data[2 * 5 + 3] = 1
        data[1 * 5 + 2] = 1
        data[1 * 5 + 3] = 1
        return PresenceGrid(5, 5, data)
    }

    @Test
    fun `lone extreme pixel is downgraded to a corroborated neighbor value`() {
        val grid = weakGridWithLoneExtremePixel()

        assertEquals(90.0, grid.mmhAt(2, 2), 0.0)
        assertEquals(0.1, grid.corroboratedMmhAt(2, 2), 0.0)
    }

    @Test
    fun `genuine extreme cluster is not downgraded`() {
        val grid = weakGridWithCorroboratedExtremeCluster()

        assertEquals(90.0, grid.corroboratedMmhAt(2, 2), 0.0)
    }

    @Test
    fun `connected components peakMmh ignores an uncorroborated extreme pixel`() {
        val grid = weakGridWithLoneExtremePixel()

        val blobs = ConnectedComponents.findBlobs(grid, minSizeCells = 1)

        assertEquals(1, blobs.size)
        assertEquals(RadarLegend.mmhForIndex(22), blobs[0].peakMmh, 0.0)
    }
}
