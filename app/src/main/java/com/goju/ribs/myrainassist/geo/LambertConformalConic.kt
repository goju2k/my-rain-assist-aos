package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Ellipsoidal Lambert Conformal Conic projection (WGS84), forward and inverse, per Snyder's
 * "Map Projections: A Working Manual" (USGS Professional Paper 1395), section 15.
 *
 * KMA's own radar composite grid is defined in exactly this projection — see
 * `+proj=lcc +lat_1=30 +lat_2=60 +lat_0=0 +lon_0=126 +x_0=0 +y_0=0 +datum=WGS84 +units=m`,
 * lifted from weather.go.kr's own map config (`kmap.bb.js`, `mapInfo["EPSG:980201"]`). The
 * `corners` the radar API hands out are that grid's 4 corners reprojected to lat/lon — which is
 * why they form a "tilted quad" in lat/lon terms despite the underlying grid being a plain
 * axis-aligned square in LCC meters. Treating them as a bilinear-warpable lat/lon quad (the
 * previous approach) was only an approximation; this reconstructs the true grid instead.
 */
class LambertConformalConic(
    lat1Deg: Double = 30.0,
    lat2Deg: Double = 60.0,
    lat0Deg: Double = 0.0,
    private val lon0Deg: Double = 126.0,
) {
    private val a = 6378137.0
    private val f = 1.0 / 298.257223563
    private val e2 = f * (2 - f)
    private val e = sqrt(e2)

    private val n: Double
    private val bigF: Double
    private val rho0: Double

    init {
        val lat1 = Math.toRadians(lat1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val lat0 = Math.toRadians(lat0Deg)
        val m1 = m(lat1)
        val m2 = m(lat2)
        val t1 = t(lat1)
        val t2 = t(lat2)
        n = if (lat1Deg == lat2Deg) sin(lat1) else (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
        bigF = m1 / (n * t1.pow(n))
        rho0 = a * bigF * t(lat0).pow(n)
    }

    private fun m(lat: Double): Double = cos(lat) / sqrt(1 - e2 * sin(lat) * sin(lat))

    private fun t(lat: Double): Double =
        tan(PI / 4 - lat / 2) / ((1 - e * sin(lat)) / (1 + e * sin(lat))).pow(e / 2)

    /** Projects a geodetic lat/lon (degrees) to LCC easting/northing (meters). */
    fun project(location: LatLon): Meters {
        val lat = Math.toRadians(location.lat)
        val lon = Math.toRadians(location.lon)
        val rho = a * bigF * t(lat).pow(n)
        val theta = n * (lon - Math.toRadians(lon0Deg))
        val x = rho * sin(theta)
        val y = rho0 - rho * cos(theta)
        return Meters(x, y)
    }

    /** Inverse of [project]: LCC easting/northing (meters) back to geodetic lat/lon (degrees). */
    fun unproject(point: Meters): LatLon {
        val x = point.x
        val yPrime = rho0 - point.y
        val rhoPrime = (if (n < 0) -1.0 else 1.0) * hypot(x, yPrime)
        val thetaPrime = atan2(if (n < 0) -x else x, if (n < 0) -yPrime else yPrime)
        val tPrime = (rhoPrime / (a * bigF)).pow(1.0 / n)

        var lat = PI / 2 - 2 * atan(tPrime)
        repeat(6) {
            val esinlat = e * sin(lat)
            lat = PI / 2 - 2 * atan(tPrime * ((1 - esinlat) / (1 + esinlat)).pow(e / 2))
        }
        val lon = thetaPrime / n + Math.toRadians(lon0Deg)
        return LatLon(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /** A point in the projection's planar easting/northing space, in meters. */
    data class Meters(val x: Double, val y: Double)
}
