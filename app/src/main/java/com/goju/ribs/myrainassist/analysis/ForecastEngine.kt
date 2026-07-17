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
    private const val MAX_FORECAST_MINUTES = 90
    private const val FORECAST_STEP_MINUTES = 5
    private const val PATH_SAMPLE_INTERVAL_MINUTES = 15
    private const val KM_PER_DEGREE_LAT = 111.32

    // A strong, sizeable cell this close counts as "raining here" even if the exact pixel under
    // the user is still dry — light scattered rain nearby genuinely isn't raining on you yet, but
    // a large storm core this close almost certainly reaches you before the radar frame catches up.
    private const val NEARBY_STRONG_RADIUS_KM = 3.0
    private const val NEARBY_STRONG_MIN_MMH = 15.0
    private const val NEARBY_STRONG_MIN_CELLS = 30

    // Weak echo (< "약한 비" ceiling) still this far out is more likely to dissipate before it
    // ever arrives than to actually reach the user, so it isn't worth an ETA-based forecast/alert.
    private const val WEAK_FAR_SUPPRESS_DISTANCE_KM = 10.0
    private const val WEAK_RAIN_MAX_MMH = 3.0

    // Small cells are usually short-lived (they scatter/dissipate rather than travel far), so a
    // fast small blob linearly extrapolated 90 minutes out draws a long straight line that reads
    // as a confident forecast but isn't one. Large, established systems keep the full horizon;
    // small ones are capped to how far back we actually confirmed their motion (symmetric
    // past/future window) so the line's length reflects how much real tracking backs it.
    private const val LARGE_BLOB_MIN_CELLS = 50
    private const val MIN_FORECAST_HORIZON_MINUTES = PATH_SAMPLE_INTERVAL_MINUTES

    fun computeForecast(response: RadarResponse, userLocation: LatLon, nowEpochMs: Long): RainForecastResult? {
        if (response.frames.size < 2) return null
        val mapper = QuadMapper(response.corners)
        val userUV = mapper.inverseMap(userLocation) ?: return null

        val latestFrame = response.frames.last()
        val latestGrid = PresenceGrid.from(latestFrame)
        val userRow = (userUV.v * (latestGrid.height - 1)).roundToInt()
        val userCol = (userUV.u * (latestGrid.width - 1)).roundToInt()

        // The latest frame's tm can lag behind the actual current time (e.g. the upstream KMA
        // feed stalling for a while before a fresh frame shows up), so blob positions are known
        // only as of tm, not as of now. Extrapolate forward by this lag before projecting arrival
        // times, otherwise ETA/state would be computed as if tm were the current moment.
        val lagMinutes = ((nowEpochMs / 60_000) - latestFrame.epochMinute)
            .coerceIn(0, MAX_FORECAST_MINUTES.toLong())
            .toInt()

        val blobs = ConnectedComponents.findBlobs(latestGrid, MIN_BLOB_SIZE_CELLS)
        // A raw single-pixel isPresent() check has no noise filtering, unlike every other rain
        // signal here (blobs require MIN_BLOB_SIZE_CELLS connected cells) — an isolated
        // compression/clutter pixel under the user could otherwise flip the state straight to
        // "raining now" without ever being confirmed as a real precipitation feature.
        val isRainingNow = blobs.any { blob -> blob.cells.any { it[0] == userRow && it[1] == userCol } }
        val earlierFrames = response.frames.dropLast(1)
        val earlierGrids = earlierFrames.map { PresenceGrid.from(it) }
        val minuteDeltas = earlierFrames.map { latestFrame.epochMinute - it.epochMinute }

        val motions = MotionEstimator.estimate(blobs, earlierGrids, minuteDeltas)
        val blobForecasts = motions.map { motion -> buildBlobForecast(motion, mapper, latestGrid, userLocation, lagMinutes) }

        // Distance uses every detected blob (not just ones with an estimated velocity), since a
        // nearby-but-unmatched blob is still "rain nearby" for the purposes of the stop condition.
        // Uses the blob's *nearest* cell to the user, not its centroid — a large or elongated blob
        // can have its centroid many km away while its near edge sits right on top of the user,
        // which previously made the "rain stopped/passed" check fire while rain pixels were still
        // visibly overhead.
        val blobDistancesKm = blobs.map { blob ->
            // blob.cells is never empty (ConnectedComponents only keeps blobs with >= minSizeCells).
            val nearestCell = blob.cells.minByOrNull { cell ->
                val dr = cell[0] - userRow
                val dc = cell[1] - userCol
                dr * dr + dc * dc
            }!!
            val nearestPoint = mapper.forwardMap(
                nearestCell[1] / (latestGrid.width - 1).toDouble(),
                nearestCell[0] / (latestGrid.height - 1).toDouble(),
            )
            blob to haversineKm(nearestPoint, userLocation)
        }
        val nearestRainDistanceKm = blobDistancesKm.minOfOrNull { it.second }
        val nearbyStrongBlob = blobDistancesKm
            .filter { (blob, distanceKm) ->
                distanceKm <= NEARBY_STRONG_RADIUS_KM && blob.sizeCells >= NEARBY_STRONG_MIN_CELLS && blob.peakMmh >= NEARBY_STRONG_MIN_MMH
            }
            .minByOrNull { it.second }
            ?.first

        val minEta = blobForecasts.mapNotNull { it.arrivalMinutes }.minOrNull()
        val etaRounded = minEta?.let { roundToNearest(it, 10) }
        // A lag-corrected blob forecast can already place a blob on top of the user (minEta == 0)
        // even though the stale grid's isRainingNow check hasn't caught up yet.
        val activeNow = isRainingNow || minEta == 0 || nearbyStrongBlob != null
        val state = when {
            activeNow -> ForecastState.ACTIVE
            etaRounded != null -> ForecastState.INCOMING
            else -> ForecastState.NONE
        }

        val intensityMmh = when {
            state == ForecastState.ACTIVE && isRainingNow -> latestGrid.mmhAt(userRow, userCol)
            state == ForecastState.ACTIVE && nearbyStrongBlob != null -> nearbyStrongBlob.peakMmh
            state == ForecastState.ACTIVE -> blobForecasts.firstOrNull { it.arrivalMinutes == 0 }?.peakMmh
            state == ForecastState.INCOMING -> blobForecasts.filter { it.arrivalMinutes != null }.minByOrNull { it.arrivalMinutes!! }?.peakMmh
            else -> null
        }

        return RainForecastResult(
            generatedAtEpochMs = nowEpochMs,
            userLocation = userLocation,
            state = state,
            etaMinutes = if (activeNow) 0 else etaRounded,
            blobs = blobForecasts,
            nearestRainDistanceKm = nearestRainDistanceKm,
            intensityMmh = intensityMmh,
            latestFrameTm = latestFrame.tm,
            latestFrameEpochMs = latestFrame.epochMinute * 60_000L,
            frameCount = response.frames.size,
            lagMinutes = lagMinutes,
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

        // A weak, still-distant cell is more likely to dissipate before it ever gets here than to
        // actually arrive, so it's excluded from the arrival/ETA math entirely (it still shows up
        // in `path` for map display, just without feeding a notification-worthy ETA). Re-evaluated
        // fresh every poll cycle, so as soon as it's genuinely within range this stops applying.
        val suppressArrival = blob.peakMmh < WEAK_RAIN_MAX_MMH &&
            haversineKm(centroidNow, userLocation) > WEAK_FAR_SUPPRESS_DISTANCE_KM

        // Small blobs only get to extrapolate as far forward as their motion is actually backed by
        // observation (see [MotionEstimator.BlobMotion.trackedSpanMinutes]) — a floor keeps them
        // from collapsing to a single point when only one earlier frame was matched.
        val horizonMinutes = if (blob.sizeCells >= LARGE_BLOB_MIN_CELLS) {
            MAX_FORECAST_MINUTES
        } else {
            motion.trackedSpanMinutes.toInt().coerceIn(MIN_FORECAST_HORIZON_MINUTES, MAX_FORECAST_MINUTES)
        }

        // Real observed track (negative minutesFromNow), one point per earlier frame that actually
        // matched — as opposed to everything from t=0 onward, which is the linear-model forecast.
        // Each point uses its own match's displacement (not the aggregate median rate), so this is
        // the blob's true past path, not a backward projection of the future line. See
        // docs/webview-interface.md 3.1.1 for how the web should render the past/future split.
        val pastPoints = motion.matches.map { match ->
            val pos = offsetLatLon(
                centroidAtTm,
                eastKm = -match.dxCells * kmPerCellX,
                northKm = match.dyCells * kmPerCellY,
            )
            PathPoint(-(lagMinutes + match.minutesAgo.toInt()), pos)
        }

        val path = (pastPoints + PathPoint(0, centroidNow)).toMutableList()
        var arrivalMinutes: Int? = if (!suppressArrival && haversineKm(centroidNow, userLocation) <= thresholdKm) 0 else null
        var t = FORECAST_STEP_MINUTES
        while (t <= horizonMinutes) {
            val pos = positionAt(lagMinutes + t)
            if (!suppressArrival && arrivalMinutes == null && haversineKm(pos, userLocation) <= thresholdKm) {
                arrivalMinutes = t
            }
            if (t % PATH_SAMPLE_INTERVAL_MINUTES == 0) path.add(PathPoint(t, pos))
            t += FORECAST_STEP_MINUTES
        }

        return BlobForecast(
            id = "blob-${blob.id}",
            sizeCells = blob.sizeCells,
            centroid = centroidNow,
            // Position as actually observed in the latest radar frame (tm), before the lag
            // extrapolation above pushes it forward to "now" — lets the web line up the drawn
            // path with whatever the (possibly stale) radar image is currently showing. See
            // docs/webview-interface.md.
            observedCentroid = centroidAtTm,
            headingDeg = headingDeg,
            speedKmh = speedKmh,
            path = path,
            arrivalMinutes = arrivalMinutes,
            peakMmh = blob.peakMmh,
        )
    }

    private fun offsetLatLon(origin: LatLon, eastKm: Double, northKm: Double): LatLon {
        val dLat = northKm / KM_PER_DEGREE_LAT
        val dLon = eastKm / (KM_PER_DEGREE_LAT * cos(Math.toRadians(origin.lat)))
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }

    private fun roundToNearest(value: Int, step: Int): Int = ((value + step / 2) / step) * step
}
