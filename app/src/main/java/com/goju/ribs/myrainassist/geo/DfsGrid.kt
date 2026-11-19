package com.goju.ribs.myrainassist.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * KMA 동네예보 (DFS) 5km grid — the spherical Lambert conformal conic KMA publishes for its
 * forecast grid (not the radar mosaic's ellipsoidal one in [LambertConformalConic]). Grid numbers
 * are 1-based: nx 1..149 west→east, ny 1..253 south→north; Seoul City Hall ≈ (60, 127).
 */
object DfsGrid {
    const val NX = 149
    const val NY = 253

    private const val RE = 6371.00877 // earth radius (km)
    private const val GRID = 5.0 // grid spacing (km)
    private const val XO = 43.0
    private const val YO = 136.0
    private const val DEG = PI / 180.0
    private val slat1 = 30.0 * DEG
    private val slat2 = 60.0 * DEG
    private val olon = 126.0 * DEG
    private val olat = 38.0 * DEG
    private val re = RE / GRID
    private val sn = ln(cos(slat1) / cos(slat2)) / ln(tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5))
    private val sf = tan(PI * 0.25 + slat1 * 0.5).pow(sn) * cos(slat1) / sn
    private val ro = re * sf / tan(PI * 0.25 + olat * 0.5).pow(sn)

    /** Nearest grid cell (nx, ny) for a point, or null outside the 149x253 grid. */
    fun cellFor(lat: Double, lon: Double): Pair<Int, Int>? {
        val ra = re * sf / tan(PI * 0.25 + lat * DEG * 0.5).pow(sn)
        var theta = lon * DEG - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn
        val nx = (ra * sin(theta) + XO).roundToInt()
        val ny = (ro - ra * cos(theta) + YO).roundToInt()
        return if (nx in 1..NX && ny in 1..NY) nx to ny else null
    }
}
