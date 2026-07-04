package com.goju.ribs.myrainassist.geo

import com.goju.ribs.myrainassist.data.LatLon
import kotlin.math.abs

/** Fractional position within the radar grid quad. u: 0=west edge, 1=east edge. v: 0=north edge, 1=south edge. */
data class UV(val u: Double, val v: Double)

/**
 * Maps between lat/lon and the radar grid's fractional (u,v) space.
 *
 * `corners` is the 4-point quad returned by the radar-frames API, ordered
 * [southWest, northWest, northEast, southEast] (index 0..3), which we infer from the observed
 * sample coordinates. This ordering is an inference — verify empirically against the web map
 * before trusting real notifications (see project plan for the verification procedure).
 */
class QuadMapper(corners: List<LatLon>) {

    // u=0,v=0 -> north-west ; u=1,v=0 -> north-east ; u=0,v=1 -> south-west ; u=1,v=1 -> south-east
    private val p00 = corners[1]
    private val p10 = corners[2]
    private val p01 = corners[0]
    private val p11 = corners[3]

    fun forwardMap(u: Double, v: Double): LatLon {
        val w00 = (1 - u) * (1 - v)
        val w10 = u * (1 - v)
        val w01 = (1 - u) * v
        val w11 = u * v
        val lat = w00 * p00.lat + w10 * p10.lat + w01 * p01.lat + w11 * p11.lat
        val lon = w00 * p00.lon + w10 * p10.lon + w01 * p01.lon + w11 * p11.lon
        return LatLon(lat, lon)
    }

    /** Inverse of [forwardMap] via Newton's method. Returns null if it fails to converge inside the quad. */
    fun inverseMap(target: LatLon, maxIter: Int = 8, eps: Double = 1e-7): UV? {
        var u = 0.5
        var v = 0.5
        repeat(maxIter) {
            val p = forwardMap(u, v)
            val rLat = p.lat - target.lat
            val rLon = p.lon - target.lon
            if (abs(rLat) < eps && abs(rLon) < eps) {
                return clampIfClose(u, v)
            }

            val dLatDu = -(1 - v) * p00.lat + (1 - v) * p10.lat - v * p01.lat + v * p11.lat
            val dLonDu = -(1 - v) * p00.lon + (1 - v) * p10.lon - v * p01.lon + v * p11.lon
            val dLatDv = -(1 - u) * p00.lat - u * p10.lat + (1 - u) * p01.lat + u * p11.lat
            val dLonDv = -(1 - u) * p00.lon - u * p10.lon + (1 - u) * p01.lon + u * p11.lon

            val det = dLatDu * dLonDv - dLatDv * dLonDu
            if (abs(det) < 1e-12) return null

            val du = (-rLat * dLonDv + rLon * dLatDv) / det
            val dv = (-dLatDu * rLon + dLonDu * rLat) / det
            u += du
            v += dv
        }
        val p = forwardMap(u, v)
        return if (abs(p.lat - target.lat) < eps * 10 && abs(p.lon - target.lon) < eps * 10) {
            clampIfClose(u, v)
        } else {
            null
        }
    }

    private fun clampIfClose(u: Double, v: Double): UV? {
        if (u < -0.05 || u > 1.05 || v < -0.05 || v > 1.05) return null
        return UV(u.coerceIn(0.0, 1.0), v.coerceIn(0.0, 1.0))
    }
}

fun UV.toGridCol(gridWidth: Int): Double = u * (gridWidth - 1)
fun UV.toGridRow(gridHeight: Int): Double = v * (gridHeight - 1)
