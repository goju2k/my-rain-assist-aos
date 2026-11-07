package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarResponse
import com.goju.ribs.myrainassist.geo.QuadMapper
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class DisplayPathTest {

    private val size = 1453
    private val tms = listOf("202610181800", "202610181805", "202610181810", "202610181815")

    @Test
    fun `drawn paths follow the radar grid, which is rotated away from lon 126`() {
        // Near Busan the grid's columns point ~2° off true north. A cloud moving straight along a
        // grid row must stay on that row when its drawn path is mapped back onto the grid — moving
        // it "east" in lat/lon instead drifted the 30-minute end ~1.4 cells (~1 km) sideways.
        val user = LatLon(35.18, 129.07)
        val uv = QuadMapper.KMA_COMPOSITE.inverseMap(user)!!
        val ur = (uv.v * (size - 1)).roundToInt()
        val uc = (uv.u * (size - 1)).roundToInt()
        val frames = tms.mapIndexed { i, tm ->
            val data = ByteArray(size * size) { 23 }
            val c0 = uc - 80 - 5 * (3 - i)
            for (r in ur - 10 until ur + 10) for (c in c0 until c0 + 20) data[r * size + c] = 10
            RadarFrame(tm, size, size, data)
        }
        val now = (frames.last().epochMinute + 8) * 60_000L
        val result = ForecastEngine.computeForecast(RadarResponse(emptyList(), frames), user, now)!!

        val blob = result.blobs.first()
        val rows = blob.path.map { QuadMapper.KMA_COMPOSITE.inverseMap(it.position)!!.v * (size - 1) }
        val spread = rows.max() - rows.min()
        assertTrue("path drifts ${"%.2f".format(spread)} cells across the grid row", spread < 0.3)
    }
}
