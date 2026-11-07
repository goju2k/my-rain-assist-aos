package com.goju.ribs.myrainassist.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CloudPeaksTest {

    // Index 20 = 1 mm/h (green), 16 = 5 mm/h (yellow), 10 = 15 mm/h (orange).
    private fun grid(size: Int, base: Int, patches: List<Triple<Int, Int, Int>>, patchRadius: Int): PresenceGrid {
        val data = ByteArray(size * size) { base.toByte() }
        for ((row, col, index) in patches) {
            for (r in row - patchRadius..row + patchRadius) for (c in col - patchRadius..col + patchRadius) {
                if (r in 0 until size && c in 0 until size) data[r * size + c] = index.toByte()
            }
        }
        return PresenceGrid(size, size, data)
    }

    @Test
    fun `a wide uniform rain area has no cloud to point at`() {
        val g = grid(200, base = 20, patches = emptyList(), patchRadius = 0)
        // Away from the window's edge (which reads as dry beyond it) nothing stands out.
        val inner = CloudPeaks.find(g, 20, 15, 100.0, 100.0).filter { it.row in 70..130 && it.col in 70..130 }
        assertTrue(inner.isEmpty())
    }

    @Test
    fun `each darker patch inside a wide band gets its own peak at its center`() {
        // One connected >= 1 mm/h band with two heavier patches 80 cells apart: the old
        // connected-area rule saw a single cloud whose center fell between them.
        val g = grid(240, base = 20, patches = listOf(Triple(120, 80, 16), Triple(120, 160, 10)), patchRadius = 5)
        val peaks = CloudPeaks.find(g, 20, 15, 120.0, 120.0).filter { it.row in 40..200 && it.col in 40..200 }
        assertEquals(2, peaks.size)
        for (center in listOf(80, 160)) {
            val p = peaks.minBy { abs(it.col - center) }
            assertTrue("peak $p not on patch at col $center", abs(p.col - center) <= 2 && abs(p.row - 120) <= 2)
        }
    }

    @Test
    fun `a nearer cloud is drawn before a stronger far one when only one fits`() {
        val g = grid(400, base = 23, patches = listOf(Triple(200, 220, 16), Triple(200, 380, 10)), patchRadius = 5)
        val peaks = CloudPeaks.find(g, 20, 1, 200.0, 200.0)
        assertTrue("picked ${peaks.single()}", abs(peaks.single().col - 220) <= 2)
    }
}
