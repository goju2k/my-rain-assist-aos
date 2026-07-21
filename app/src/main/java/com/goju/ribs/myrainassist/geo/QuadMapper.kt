package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon

/** Fractional position within the radar grid quad. u: 0=west edge, 1=east edge. v: 0=north edge, 1=south edge. */
data class UV(val u: Double, val v: Double)

/**
 * Maps between lat/lon and the radar grid's fractional (u,v) space.
 *
 * `corners` is the 4-point quad returned by the radar-frames API, ordered
 * [southWest, northWest, northEast, southEast] (index 0..3). In raw lat/lon terms these look like
 * a tilted quad (e.g. the NW and NE corners don't share a latitude) — but that's an artifact of
 * projection, not a real tilt: the underlying grid is a plain axis-aligned square in KMA's own
 * Lambert Conformal Conic projection (`+proj=lcc +lat_1=30 +lat_2=60 +lat_0=0 +lon_0=126
 * +datum=WGS84`, confirmed against weather.go.kr's own map config and by reprojecting `corners`
 * into that CRS — the 4 points line up into a rectangle there to within ~0.03% of its size, i.e.
 * rounding noise). So rather than bilinearly warping lat/lon across 4 corners (an approximation,
 * exact only at the corners themselves), this reprojects into LCC meters, fits the axis-aligned
 * box from all 4 corners, and does plain linear interpolation there before unprojecting back —
 * exact everywhere, not just at the corners.
 */
class QuadMapper(corners: List<LatLon>) {

    private val lcc = LambertConformalConic()
    private val xMin: Double
    private val xMax: Double
    private val yMin: Double
    private val yMax: Double

    init {
        val sw = lcc.project(corners[0])
        val nw = lcc.project(corners[1])
        val ne = lcc.project(corners[2])
        val se = lcc.project(corners[3])
        // Opposite edges agree to within ~0.03% of the box size (see class doc) — averaging them
        // fits the best axis-aligned box rather than favoring one pair of corners over the other.
        xMin = (sw.x + nw.x) / 2
        xMax = (ne.x + se.x) / 2
        yMin = (sw.y + se.y) / 2
        yMax = (nw.y + ne.y) / 2
    }

    fun forwardMap(u: Double, v: Double): LatLon {
        val x = xMin + u * (xMax - xMin)
        val y = yMax - v * (yMax - yMin)
        return lcc.unproject(LambertConformalConic.Meters(x, y))
    }

    /** Exact closed-form inverse (no iterative solver needed): project to LCC meters, then a plain linear box lookup. */
    fun inverseMap(target: LatLon): UV? {
        val p = lcc.project(target)
        val u = (p.x - xMin) / (xMax - xMin)
        val v = (yMax - p.y) / (yMax - yMin)
        return clampIfClose(u, v)
    }

    private fun clampIfClose(u: Double, v: Double): UV? {
        if (u < -0.05 || u > 1.05 || v < -0.05 || v > 1.05) return null
        return UV(u.coerceIn(0.0, 1.0), v.coerceIn(0.0, 1.0))
    }
}

fun UV.toGridCol(gridWidth: Int): Double = u * (gridWidth - 1)
fun UV.toGridRow(gridHeight: Int): Double = v * (gridHeight - 1)
