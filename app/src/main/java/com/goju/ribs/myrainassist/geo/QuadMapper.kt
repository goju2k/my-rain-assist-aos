package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon

/** Fractional position within the radar grid quad. u: 0=west edge, 1=east edge. v: 0=north edge, 1=south edge. */
data class UV(val u: Double, val v: Double)

/**
 * Maps between lat/lon and the radar grid's fractional (u,v) space.
 *
 * The 1453px radar image is a plain axis-aligned rectangle in KMA's own Lambert Conformal Conic
 * projection (`+proj=lcc +lat_1=30 +lat_2=60 +lat_0=0 +lon_0=126 +datum=WGS84`) — which is why its
 * corners look like a tilted quad in raw lat/lon. Mapping is a linear box lookup in LCC meters,
 * exact everywhere. Use [KMA_COMPOSITE] (KMA's declared extent); the corners constructor only
 * remains for the frozen pre-v2 engine.
 */
class QuadMapper private constructor(
    private val xMin: Double,
    private val xMax: Double,
    private val yMin: Double,
    private val yMax: Double,
) {

    private val lcc = LambertConformalConic()

    /**
     * Fits the box from 4 corner lat/lons. Kept for the frozen pre-v2 engine in the backtest —
     * the corners the server has always shipped (hand-derived long ago for the web overlay) put
     * this box 2-5 km inside the real one on every edge, i.e. every location ~5-6 km off on the
     * image. Use [KMA_COMPOSITE].
     */
    constructor(corners: List<LatLon>) : this(fitBox(corners))

    private constructor(box: DoubleArray) : this(box[0], box[1], box[2], box[3])

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

    companion object {
        /**
         * The 1453px national composite's exact extent in LCC meters, as KMA's own map declares it
         * (weather.go.kr `kmap.bb.js`, `mapInfo["radar.extent"]`, rendered there via OpenLayers
         * ImageStatic) — the same box the web overlay draws the image with (ribs-app
         * rain/util/radarGrid.ts RADAR_MOSAIC_BOX), so app positions and the drawn image agree.
         */
        val KMA_COMPOSITE = QuadMapper(-440000.00000000227, 584000.0000000008, 3797382.7212162036, 4821382.721216239)

        private fun fitBox(corners: List<LatLon>): DoubleArray {
            val lcc = LambertConformalConic()
            val sw = lcc.project(corners[0])
            val nw = lcc.project(corners[1])
            val ne = lcc.project(corners[2])
            val se = lcc.project(corners[3])
            // Opposite edges agree to within ~0.03% of the box size — averaging them fits the best
            // axis-aligned box rather than favoring one pair of corners over the other.
            return doubleArrayOf((sw.x + nw.x) / 2, (ne.x + se.x) / 2, (sw.y + se.y) / 2, (nw.y + ne.y) / 2)
        }
    }
}

fun UV.toGridCol(gridWidth: Int): Double = u * (gridWidth - 1)
fun UV.toGridRow(gridHeight: Int): Double = v * (gridHeight - 1)
