package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarResponse
import com.goju.ribs.myrainassist.data.haversineKm
import com.goju.ribs.myrainassist.data.bearingDeg
import com.goju.ribs.myrainassist.geo.QuadMapper
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Combines geo mapping, blob detection and motion estimation into a single forecast for the
 * user's location. Returns null when the user's location cannot be resolved onto the radar grid
 * (e.g. outside coverage, or a degenerate quad) — the caller should skip this poll cycle.
 */
object ForecastEngine {

    private const val MIN_BLOB_SIZE_CELLS = 9
    private const val MIN_ARRIVAL_THRESHOLD_KM = 3.0
    private const val ARRIVAL_THRESHOLD_CELL_MULTIPLIER = 1.5
    private const val MAX_FORECAST_MINUTES = 60
    private const val FORECAST_STEP_MINUTES = 5
    private const val PATH_SAMPLE_INTERVAL_MINUTES = 15
    private const val KM_PER_DEGREE_LAT = 111.32

    fun computeForecast(response: RadarResponse, userLocation: LatLon, nowEpochMs: Long): RainForecastResult? {
        if (response.frames.size < 2) return null
        val mapper = QuadMapper(response.corners)
        val userUV = mapper.inverseMap(userLocation) ?: return null

        val latestFrame = response.frames.last()
        val latestGrid = PresenceGrid.from(latestFrame)
        val userRow = (userUV.v * (latestGrid.height - 1)).roundToInt()
        val userCol = (userUV.u * (latestGrid.width - 1)).roundToInt()
        val isRainingNow = latestGrid.inBounds(userRow, userCol) && latestGrid.isPresent(userRow, userCol)

        // The latest frame's tm can lag behind the actual current time (e.g. the upstream KMA
        // feed stalling for a while before a fresh frame shows up), so blob positions are known
        // only as of tm, not as of now. Extrapolate forward by this lag before projecting arrival
        // times, otherwise ETA/state would be computed as if tm were the current moment.
        val lagMinutes = ((nowEpochMs / 60_000) - latestFrame.epochMinute)
            .coerceIn(0, MAX_FORECAST_MINUTES.toLong())
            .toInt()

        val blobs = ConnectedComponents.findBlobs(latestGrid, MIN_BLOB_SIZE_CELLS)
        val earlierFrames = response.frames.dropLast(1)
        val earlierGrids = earlierFrames.map { PresenceGrid.from(it) }
        val minuteDeltas = earlierFrames.map { latestFrame.epochMinute - it.epochMinute }

        val motions = MotionEstimator.estimate(blobs, earlierGrids, minuteDeltas)
        val blobForecasts = motions.map { motion -> buildBlobForecast(motion, mapper, latestGrid, userLocation, lagMinutes) }

        // Distance uses every detected blob (not just ones with an estimated velocity), since a
        // nearby-but-unmatched blob is still "rain nearby" for the purposes of the stop condition.
        val nearestRainDistanceKm = blobs.minOfOrNull { blob ->
            val centroid = mapper.forwardMap(blob.centroidCol / (latestGrid.width - 1), blob.centroidRow / (latestGrid.height - 1))
            haversineKm(centroid, userLocation)
        }

        val minEta = blobForecasts.mapNotNull { it.arrivalMinutes }.minOrNull()
        val etaRounded = minEta?.let { roundToNearest(it, 10) }
        // A lag-corrected blob forecast can already place a blob on top of the user (minEta == 0)
        // even though the stale grid's isRainingNow check hasn't caught up yet.
        val activeNow = isRainingNow || minEta == 0
        val state = when {
            activeNow -> ForecastState.ACTIVE
            etaRounded != null -> ForecastState.INCOMING
            else -> ForecastState.NONE
        }

        return RainForecastResult(
            generatedAtEpochMs = nowEpochMs,
            userLocation = userLocation,
            state = state,
            etaMinutes = if (activeNow) 0 else etaRounded,
            blobs = blobForecasts,
            nearestRainDistanceKm = nearestRainDistanceKm,
        )
    }

    private fun buildBlobForecast(
        motion: MotionEstimator.BlobMotion,
        mapper: QuadMapper,
        grid: PresenceGrid,
        userLocation: LatLon,
        lagMinutes: Int,
    ): BlobForecast {
        val blob = motion.blob
        val u = blob.centroidCol / (grid.width - 1)
        val v = blob.centroidRow / (grid.height - 1)
        val centroidAtTm = mapper.forwardMap(u, v)

        val du = 1.0 / (grid.width - 1)
        val dv = 1.0 / (grid.height - 1)
        val eastU = if (u + du <= 1.0) u + du else u - du
        val southV = if (v + dv <= 1.0) v + dv else v - dv
        val kmPerCellX = haversineKm(centroidAtTm, mapper.forwardMap(eastU, v))
        val kmPerCellY = haversineKm(centroidAtTm, mapper.forwardMap(u, southV))
        val cellDiagonalKm = sqrt(kmPerCellX * kmPerCellX + kmPerCellY * kmPerCellY)
        val thresholdKm = max(MIN_ARRIVAL_THRESHOLD_KM, ARRIVAL_THRESHOLD_CELL_MULTIPLIER * cellDiagonalKm)

        val speedKmh = sqrt(
            (motion.vxCellsPerMin * kmPerCellX) * (motion.vxCellsPerMin * kmPerCellX) +
                (motion.vyCellsPerMin * kmPerCellY) * (motion.vyCellsPerMin * kmPerCellY),
        ) * 60.0
        // row increases southward, so northward displacement is the negative of vy.
        val headingDeg = bearingDeg(eastKm = motion.vxCellsPerMin * kmPerCellX, northKm = -motion.vyCellsPerMin * kmPerCellY)

        // positionAt(minutesFromTm) walks the blob forward from its last-observed (tm) position;
        // minutesFromTm = lagMinutes + t gives the position "t minutes from now".
        fun positionAt(minutesFromTm: Int): LatLon = offsetLatLon(
            centroidAtTm,
            eastKm = motion.vxCellsPerMin * kmPerCellX * minutesFromTm,
            northKm = -motion.vyCellsPerMin * kmPerCellY * minutesFromTm,
        )

        val centroidNow = positionAt(lagMinutes)
        val path = mutableListOf(PathPoint(0, centroidNow))
        var arrivalMinutes: Int? = if (haversineKm(centroidNow, userLocation) <= thresholdKm) 0 else null
        var t = FORECAST_STEP_MINUTES
        while (t <= MAX_FORECAST_MINUTES) {
            val pos = positionAt(lagMinutes + t)
            if (arrivalMinutes == null && haversineKm(pos, userLocation) <= thresholdKm) {
                arrivalMinutes = t
            }
            if (t % PATH_SAMPLE_INTERVAL_MINUTES == 0) path.add(PathPoint(t, pos))
            t += FORECAST_STEP_MINUTES
        }

        return BlobForecast(
            id = "blob-${blob.id}",
            sizeCells = blob.sizeCells,
            centroid = centroidNow,
            headingDeg = headingDeg,
            speedKmh = speedKmh,
            path = path,
            arrivalMinutes = arrivalMinutes,
        )
    }

    private fun offsetLatLon(origin: LatLon, eastKm: Double, northKm: Double): LatLon {
        val dLat = northKm / KM_PER_DEGREE_LAT
        val dLon = eastKm / (KM_PER_DEGREE_LAT * cos(Math.toRadians(origin.lat)))
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }

    private fun roundToNearest(value: Int, step: Int): Int = ((value + step / 2) / step) * step
}
