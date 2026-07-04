package com.goju.ribs.myrainassist.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_KM = 6371.0088

fun haversineKm(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_KM * atan2(sqrt(h), sqrt(1 - h))
}

/** Compass bearing (0=N, 90=E) for a displacement given in local east/north km components. */
fun bearingDeg(eastKm: Double, northKm: Double): Double {
    val deg = Math.toDegrees(atan2(eastKm, northKm))
    return (deg + 360.0) % 360.0
}
