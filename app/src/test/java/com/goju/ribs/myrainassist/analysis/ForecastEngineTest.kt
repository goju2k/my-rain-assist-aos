package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarResponse
import com.goju.ribs.myrainassist.geo.QuadMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class ForecastEngineTest {

    private val corners = listOf(
        LatLon(30.8101038494, 121.3322516155),
        LatLon(40.1670385352, 120.609116658),
        LatLon(40.0701181652, 133.0225827684),
        LatLon(30.7283967663, 132.0821758282),
    )
    private val size = 1453
    private val user = LatLon(37.621, 127.157)
    private val userCell: Pair<Int, Int> = QuadMapper(corners).inverseMap(user)!!.let {
        (it.v * (size - 1)).roundToInt() to (it.u * (size - 1)).roundToInt()
    }
    private val tms = listOf("202610181800", "202610181805", "202610181810", "202610181815")

    /** A frame with one rectangle of 15 mm/h rain, given relative to the user's cell. */
    private fun frame(tm: String, rowOffset: Int, colOffset: Int, height: Int, width: Int): RadarFrame {
        val data = ByteArray(size * size) { 23 }
        val (ur, uc) = userCell
        for (r in ur + rowOffset until ur + rowOffset + height) {
            for (c in uc + colOffset until uc + colOffset + width) data[r * size + c] = 10
        }
        return RadarFrame(tm, size, size, data)
    }

    private fun forecast(frames: List<RadarFrame>, lagMinutes: Int = 8): RainForecastResult {
        val now = (frames.last().epochMinute + lagMinutes) * 60_000L
        return ForecastEngine.computeForecast(RadarResponse(corners, frames), user, now)!!
    }

    @Test
    fun `a stationary cloud 2-3 km away is not reported as raining here`() {
        // Regression for 2026-10-18 18:24: "지금 비가 오고 있어요" fired with the nearest echo
        // 2.35 km away, because the old engine called any blob whose center came within 3 km "overhead".
        // 3x3-cell cloud whose center is ~2.8 km east (old engine: "overhead"), nearest edge ~2.1 km.
        val frames = tms.map { frame(it, rowOffset = -1, colOffset = 3, height = 3, width = 3) }

        val result = forecast(frames)

        assertEquals(ForecastState.NONE, result.state)
        assertNull(result.etaMinutes)
    }

    @Test
    fun `a cloud covering the user is raining now`() {
        val frames = tms.map { frame(it, rowOffset = -10, colOffset = -10, height = 20, width = 20) }

        val result = forecast(frames)

        assertEquals(ForecastState.ACTIVE, result.state)
        assertEquals(0, result.etaMinutes)
        assertEquals(15.0, result.intensityMmh!!, 0.0)
    }

    @Test
    fun `a wide band is forecast from its leading edge, not its center`() {
        // 60-cell (~42 km) wide band moving east 5 cells per 5 min (~42 km/h); at the latest tm its
        // leading (east) edge is 15 cells (~10 km) west of the user. With 8 min lag the edge is
        // 7 cells away "now" and enters the user's 1-cell neighborhood 6 minutes later (-> "5분")
        // — while the band's middle is still ~37 min out, beyond the 30-min horizon.
        val frames = tms.mapIndexed { i, tm -> frame(tm, rowOffset = -100, colOffset = -74 - 5 * (3 - i), height = 200, width = 60) }

        val result = forecast(frames)

        assertEquals(ForecastState.INCOMING, result.state)
        assertEquals(5, result.etaMinutes)
    }

    @Test
    fun `rain end is predicted from the trailing edge`() {
        // Same band, but the user is already inside it, 10 cells from its trailing (west) edge.
        val frames = tms.mapIndexed { i, tm -> frame(tm, rowOffset = -100, colOffset = -10 - 8 - 5 * (3 - i), height = 200, width = 60) }

        val result = forecast(frames)

        assertEquals(ForecastState.ACTIVE, result.state)
        val end = assertNotNullAndGet(result.rainEndMinutes)
        // Trailing edge 18 cells west at tm, moving 1 cell/min: it passes the user's 1-cell
        // neighborhood after 19 minutes from tm, i.e. 11 minutes after now (8 min lag) -> clear at 12.
        assertEquals(12, end)
    }

    private fun assertNotNullAndGet(value: Int?): Int {
        assertNotNull(value)
        return value!!
    }
}
