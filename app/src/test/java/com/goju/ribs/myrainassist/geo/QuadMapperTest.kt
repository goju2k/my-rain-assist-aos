package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadMapperTest {

    // A live snapshot of the radar API's corners field (2026-07-22), ordered [SW, NW, NE, SE].
    private val corners = listOf(
        LatLon(30.8101038494, 121.3322516155),
        LatLon(40.1670385352, 120.609116658),
        LatLon(40.0701181652, 133.0225827684),
        LatLon(30.7283967663, 132.0821758282),
    )
    private val mapper = QuadMapper(corners)

    @Test
    fun `forward-then-inverse round-trips for interior points`() {
        val samples = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 0.5 to 0.5, 0.25 to 0.7)
        for ((u, v) in samples) {
            val latLon = mapper.forwardMap(u, v)
            val uv = mapper.inverseMap(latLon)
            assertNotNull("inverseMap should resolve a point forwardMap just produced", uv)
            assertEquals(u, uv!!.u, 1e-6)
            assertEquals(v, uv.v, 1e-6)
        }
    }

    @Test
    fun `forward corners land within the API's own corner coordinates`() {
        // The 4 raw corners only approximate a perfect axis-aligned LCC box (opposite edges agree
        // to ~0.03% of the box size, per LambertConformalConic's doc), so this checks "close", not exact.
        val toleranceDeg = 0.01
        assertNear(corners[1], mapper.forwardMap(0.0, 0.0), toleranceDeg) // NW
        assertNear(corners[2], mapper.forwardMap(1.0, 0.0), toleranceDeg) // NE
        assertNear(corners[0], mapper.forwardMap(0.0, 1.0), toleranceDeg) // SW
        assertNear(corners[3], mapper.forwardMap(1.0, 1.0), toleranceDeg) // SE
    }

    @Test
    fun `inverseMap rejects points far outside the grid`() {
        assertTrue(mapper.inverseMap(LatLon(0.0, 0.0)) == null)
    }

    private fun assertNear(expected: LatLon, actual: LatLon, toleranceDeg: Double) {
        assertEquals(expected.lat, actual.lat, toleranceDeg)
        assertEquals(expected.lon, actual.lon, toleranceDeg)
    }

    @Test
    fun `KMA composite extent places known points on the pixels KMA's own map uses`() {
        // Expected pixels computed independently with proj4 from KMA's declared radar.extent
        // (weather.go.kr kmap.bb.js) on the 1453px image.
        val uv = QuadMapper.KMA_COMPOSITE.inverseMap(LatLon(37.621, 127.157))!!
        assertEquals(765.1, uv.u * 1452, 0.2)
        assertEquals(417.3, uv.v * 1452, 0.2)
        // ...and the old corner-fit box is the ~6 km-off one the app used to use.
        val old = mapper.inverseMap(LatLon(37.621, 127.157))!!
        assertEquals(771.2, old.u * 1452, 0.2)
        assertEquals(411.3, old.v * 1452, 0.2)
    }
}
