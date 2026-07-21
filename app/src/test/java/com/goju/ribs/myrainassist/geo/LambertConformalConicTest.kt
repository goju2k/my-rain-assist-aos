package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reference (x,y) values below are from pyproj (PROJ, the reference implementation) for
 * `+proj=lcc +lat_1=30 +lat_2=60 +lat_0=0 +lon_0=126 +x_0=0 +y_0=0 +datum=WGS84 +units=m` — the
 * exact projection weather.go.kr uses for its own radar grid (see `LambertConformalConic` doc).
 */
class LambertConformalConicTest {

    private val lcc = LambertConformalConic()

    private data class Ref(val name: String, val lat: Double, val lon: Double, val x: Double, val y: Double)

    private val references = listOf(
        Ref("Seoul", 37.5665, 126.978, 84249.9069, 4521003.3611),
        Ref("Busan", 35.1796, 129.0756, 274838.8601, 4266714.5110),
        Ref("Sokcho", 38.207, 128.5918, 220995.2391, 4593337.7848),
        Ref("Mokpo", 34.8118, 126.3922, 35252.1473, 4221458.8671),
        Ref("Baengnyeong", 37.967, 124.665, -114278.8055, 4564767.2590),
    )

    @Test
    fun `project matches pyproj reference values`() {
        for (ref in references) {
            val p = lcc.project(LatLon(ref.lat, ref.lon))
            assertEquals("${ref.name} x", ref.x, p.x, 0.05)
            assertEquals("${ref.name} y", ref.y, p.y, 0.05)
        }
    }

    @Test
    fun `unproject is the exact inverse of project`() {
        for (ref in references) {
            val original = LatLon(ref.lat, ref.lon)
            val roundTripped = lcc.unproject(lcc.project(original))
            assertEquals("${ref.name} lat", original.lat, roundTripped.lat, 1e-9)
            assertEquals("${ref.name} lon", original.lon, roundTripped.lon, 1e-9)
        }
    }

    @Test
    fun `unproject matches pyproj reference meters`() {
        for (ref in references) {
            val p = lcc.unproject(LambertConformalConic.Meters(ref.x, ref.y))
            assertEquals("${ref.name} lat", ref.lat, p.lat, 1e-6)
            assertEquals("${ref.name} lon", ref.lon, p.lon, 1e-6)
        }
    }
}
