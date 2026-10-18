package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarLegend
import com.goju.ribs.myrainassist.data.RadarResponse
import com.goju.ribs.myrainassist.data.bearingDeg
import com.goju.ribs.myrainassist.data.haversineKm
import com.goju.ribs.myrainassist.geo.QuadMapper
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a short radar history into a forecast for one location. Only a window of
 * [ROI_RADIUS_KM] around the user is analyzed — nothing farther can reach them within the forecast
 * horizon at any plausible echo speed — which keeps each cycle cheap enough to run on a phone.
 *
 * Arrival is decided by tracing backwards along each blob's motion (semi-Lagrangian): "will this
 * blob cover me in t minutes" is the same question as "is there a cell of this blob, right now,
 * at my position minus its velocity x t". That answers for the blob's leading edge and for its
 * full width — unlike following a single representative point, which only notices a broad rain
 * band once its core gets close, and which called "raining now" whenever that point merely came
 * within a few km. Returns null when the location can't be placed on the radar grid.
 */
object ForecastEngine {

    /** How far around the user the radar is analyzed (also what the app downloads). */
    const val ROI_RADIUS_KM = 150.0

    private const val MIN_BLOB_SIZE_CELLS = 9
    // The radar API only ever gives ~4 frames of history (~15-20 tracked minutes), so a long
    // straight-line extrapolation off that little confirmed motion reads as far more confident
    // than it is. Capped to how far a real measurement run could plausibly extend.
    private const val MAX_FORECAST_MINUTES = 30
    // Upper bound for lagMinutes (how stale the latest radar frame is vs. now) — unrelated to how
    // far into the future a blob's path is drawn.
    private const val MAX_LAG_MINUTES = 90
    private const val PATH_SAMPLE_INTERVAL_MINUTES = 15
    private const val KM_PER_DEGREE_LAT = 111.32

    // A blob counts as covering the user when one of its cells falls within this many cells
    // (Chebyshev) of the user's cell: 1 = the 3x3 neighborhood, about ±0.7 km — enough to absorb
    // location/projection error without calling a cloud kilometers away "overhead".
    private const val COVER_RADIUS_CELLS = 1

    // Weak echo (< "약한 비" ceiling) still this far out is more likely to dissipate before it
    // ever arrives than to actually reach the user, so it isn't worth an ETA-based forecast/alert.
    private const val WEAK_FAR_SUPPRESS_DISTANCE_KM = 10.0
    private const val WEAK_RAIN_MAX_MMH = 3.0

    // Small cells are usually short-lived (they scatter/dissipate rather than travel far), so they
    // only get to extrapolate as far forward as their motion is backed by observation.
    private const val LARGE_BLOB_MIN_CELLS = 50
    private const val MIN_FORECAST_HORIZON_MINUTES = PATH_SAMPLE_INTERVAL_MINUTES

    // An incoming band's edge is usually its weakest part; report the heaviest rain the user is
    // expected to get over this long after arrival rather than just the first drizzle.
    private const val INTENSITY_LOOKAHEAD_MINUTES = 15
    private const val ETA_ROUNDING_MINUTES = 5

    // Map arrows (see displayBlobs): cores of >= 1 mm/h, at least ~12 km², at most this many.
    private const val DISPLAY_MAX_INDEX = 20
    private const val DISPLAY_MIN_CELLS = 25
    private const val MAX_DISPLAY_BLOBS = 15

    /** The analyzed window, in full-mosaic cell coordinates. */
    data class Roi(val row0: Int, val col0: Int, val width: Int, val height: Int)

    /** Where the user sits on the mosaic and how big a cell is there. */
    private class Placement(val row: Int, val col: Int, val kmPerCellX: Double, val kmPerCellY: Double, val roi: Roi)

    private fun place(mapper: QuadMapper, userLocation: LatLon, fullWidth: Int, fullHeight: Int): Placement? {
        val uv = mapper.inverseMap(userLocation) ?: return null
        val row = (uv.v * (fullHeight - 1)).roundToInt()
        val col = (uv.u * (fullWidth - 1)).roundToInt()
        val (kmPerCellX, kmPerCellY) = cellSizeKm(mapper, uv.u, uv.v, fullWidth, fullHeight)
        val radius = ceil(ROI_RADIUS_KM / ((kmPerCellX + kmPerCellY) / 2)).toInt()
        return Placement(row, col, kmPerCellX, kmPerCellY, roiAround(row, col, radius, fullWidth, fullHeight))
    }

    /**
     * The window [computeForecast] will analyze for [userLocation] — exactly what the app needs to
     * download (see RadarRepository). Null when the location is off the radar grid.
     */
    fun roiFor(userLocation: LatLon, fullWidth: Int, fullHeight: Int): Roi? =
        place(QuadMapper.KMA_COMPOSITE, userLocation, fullWidth, fullHeight)?.roi

    private class Scene(
        val mapper: QuadMapper,
        val roi: Roi,
        val fullWidth: Int,
        val fullHeight: Int,
        val grid: PresenceGrid,
        val labels: IntArray,
    ) {
        fun latLonOf(row: Double, col: Double): LatLon =
            mapper.forwardMap((roi.col0 + col) / (fullWidth - 1), (roi.row0 + row) / (fullHeight - 1))

        /**
         * Heaviest corroborated reading among [blobId]'s cells within [COVER_RADIUS_CELLS] of
         * (row, col), or null if the blob doesn't cover that point. [labelMap] defaults to the
         * forecast blobs; pass the display cores' map to ask about a core instead.
         */
        fun coverMmh(blobId: Int, row: Double, col: Double, labelMap: IntArray = labels): Double? {
            val r = row.roundToInt()
            val c = col.roundToInt()
            var best: Double? = null
            for (dr in -COVER_RADIUS_CELLS..COVER_RADIUS_CELLS) for (dc in -COVER_RADIUS_CELLS..COVER_RADIUS_CELLS) {
                val rr = r + dr
                val cc = c + dc
                if (!grid.inBounds(rr, cc) || labelMap[rr * grid.width + cc] != blobId) continue
                val mmh = grid.corroboratedMmhAt(rr, cc)
                if (best == null || mmh > best) best = mmh
            }
            return best
        }
    }

    fun computeForecast(response: RadarResponse, userLocation: LatLon, nowEpochMs: Long): RainForecastResult? {
        if (response.frames.size < 2) return null
        // Always KMA's exact extent: the server's `corners` are a 2-5 km-off approximation of it.
        val mapper = QuadMapper.KMA_COMPOSITE
        val latestFrame = response.frames.last()
        val fullWidth = latestFrame.fullWidth
        val fullHeight = latestFrame.fullHeight
        val placement = place(mapper, userLocation, fullWidth, fullHeight) ?: return null
        val kmPerCellX = placement.kmPerCellX
        val kmPerCellY = placement.kmPerCellY
        val kmPerCell = (kmPerCellX + kmPerCellY) / 2
        val roi = placement.roi
        val grids = response.frames.map { crop(it, roi) }
        val latestGrid = grids.last()
        val userRow = (placement.row - roi.row0).toDouble()
        val userCol = (placement.col - roi.col0).toDouble()

        // The latest frame's tm lags behind the actual current time (KMA's publish delay, or the
        // feed stalling for a while), so blob positions are known only as of tm, not as of now.
        // Every lookup below advances by this lag first, so "now" really means now.
        val lagMinutes = ((nowEpochMs / 60_000) - latestFrame.epochMinute)
            .coerceIn(0, MAX_LAG_MINUTES.toLong())
            .toInt()

        val labels = IntArray(latestGrid.width * latestGrid.height) { -1 }
        val blobs = ConnectedComponents.findBlobs(latestGrid, MIN_BLOB_SIZE_CELLS, labels)
        val scene = Scene(mapper, roi, fullWidth, fullHeight, latestGrid, labels)

        val nearestKm = blobs.associate { blob ->
            blob.id to blob.cells.minOf { cell ->
                val dy = (cell[0] - userRow) * kmPerCellY
                val dx = (cell[1] - userCol) * kmPerCellX
                sqrt(dx * dx + dy * dy)
            }
        }
        // A weak, still-distant cell is more likely to dissipate than to arrive, so it only counts
        // if it's already overhead — and since it's neither drawn on the map nor able to arrive,
        // its motion isn't worth estimating at all (a rainy area holds hundreds of these).
        fun isWeakAndFar(blob: Blob) = blob.peakMmh < WEAK_RAIN_MAX_MMH && nearestKm.getValue(blob.id) > WEAK_FAR_SUPPRESS_DISTANCE_KM

        val motions = MotionEstimator.estimate(
            blobs = blobs,
            labels = labels,
            latest = LevelGrid.from(latestGrid),
            earlier = grids.dropLast(1).map { LevelGrid.from(it) },
            minuteDeltas = response.frames.dropLast(1).map { latestFrame.epochMinute - it.epochMinute },
            kmPerCell = kmPerCell,
            needsMotion = { !isWeakAndFar(it) },
        )

        // Where (in ROI cells) the part of this blob that will be over the user t minutes from now sits in the latest frame.
        fun sourceRow(m: MotionEstimator.BlobMotion, t: Int) = userRow - m.vyCellsPerMin * (lagMinutes + t)
        fun sourceCol(m: MotionEstimator.BlobMotion, t: Int) = userCol - m.vxCellsPerMin * (lagMinutes + t)
        fun coverAt(m: MotionEstimator.BlobMotion, t: Int) = scene.coverMmh(m.blob.id, sourceRow(m, t), sourceCol(m, t))

        val arrivals = motions.associateWith { motion ->
            val blob = motion.blob
            val horizon = if (blob.sizeCells >= LARGE_BLOB_MIN_CELLS) {
                MAX_FORECAST_MINUTES
            } else {
                motion.trackedSpanMinutes.toInt().coerceIn(MIN_FORECAST_HORIZON_MINUTES, MAX_FORECAST_MINUTES)
            }
            val lastT = if (isWeakAndFar(blob)) 0 else horizon
            (0..lastT).firstOrNull { t -> coverAt(motion, t) != null }
        }

        val arriving = arrivals.filterValues { it != null }
        val minArrival = arriving.values.minOfOrNull { it!! }
        val state = when {
            minArrival == 0 -> ForecastState.ACTIVE
            minArrival != null -> ForecastState.INCOMING
            else -> ForecastState.NONE
        }

        val intensityMmh: Double? = when (state) {
            ForecastState.ACTIVE -> arriving.filterValues { it == 0 }.keys.mapNotNull { coverAt(it, 0) }.maxOrNull()
            ForecastState.INCOMING -> arriving.filterValues { it == minArrival }.keys.flatMap { m ->
                (minArrival!!..minArrival + INTENSITY_LOOKAHEAD_MINUTES).mapNotNull { t -> coverAt(m, t) }
            }.maxOrNull()
            ForecastState.NONE -> null
        }

        // While it's raining: the first minute at which no blob (each moving its own way) covers
        // the user any more. Null = still raining at the end of the horizon (or not raining).
        val rainEndMinutes = if (state != ForecastState.ACTIVE) {
            null
        } else {
            (1..MAX_FORECAST_MINUTES).firstOrNull { t -> motions.none { coverAt(it, t) != null } }
        }

        // The rain parcel that will reach the user first: where it sits in the latest frame.
        val targetMotion = arriving.entries.minByOrNull { it.value!! }?.key
        val targetParcel = targetMotion?.let { m ->
            Triple(m, sourceRow(m, minArrival!!).roundToInt(), sourceCol(m, minArrival).roundToInt())
        }
        val displayBlobs = displayBlobs(
            scene = scene,
            motions = motions,
            arrivals = arrivals,
            lagMinutes = lagMinutes,
            coreArrival = { coreLabels, coreId, m ->
                (0..MAX_FORECAST_MINUTES).firstOrNull { t -> scene.coverMmh(coreId, sourceRow(m, t), sourceCol(m, t), coreLabels) != null }
            },
            targetParcel = targetParcel,
            targetMmh = intensityMmh,
        )

        return RainForecastResult(
            generatedAtEpochMs = nowEpochMs,
            userLocation = userLocation,
            state = state,
            etaMinutes = minArrival?.let { roundEta(it) },
            blobs = displayBlobs,
            nearestRainDistanceKm = nearestKm.values.minOrNull(),
            intensityMmh = intensityMmh,
            latestFrameTm = latestFrame.tm,
            latestFrameEpochMs = latestFrame.epochMinute * 60_000L,
            frameCount = response.frames.size,
            lagMinutes = lagMinutes,
            rainEndMinutes = rainEndMinutes,
        )
    }

    /** 0 stays 0 ("now"); anything later rounds to the nearest 5 minutes but never down to 0. */
    private fun roundEta(minutes: Int): Int =
        if (minutes == 0) 0 else max(ETA_ROUNDING_MINUTES, ((minutes + ETA_ROUNDING_MINUTES / 2) / ETA_ROUNDING_MINUTES) * ETA_ROUNDING_MINUTES)

    private fun cellSizeKm(mapper: QuadMapper, u: Double, v: Double, fullWidth: Int, fullHeight: Int): Pair<Double, Double> {
        val du = 1.0 / (fullWidth - 1)
        val dv = 1.0 / (fullHeight - 1)
        val here = mapper.forwardMap(u, v)
        val east = mapper.forwardMap(if (u + du <= 1.0) u + du else u - du, v)
        val south = mapper.forwardMap(u, if (v + dv <= 1.0) v + dv else v - dv)
        return haversineKm(here, east) to haversineKm(here, south)
    }

    private fun roiAround(row: Int, col: Int, radius: Int, fullWidth: Int, fullHeight: Int): Roi {
        val row0 = (row - radius).coerceAtLeast(0)
        val col0 = (col - radius).coerceAtLeast(0)
        val row1 = (row + radius).coerceAtMost(fullHeight - 1)
        val col1 = (col + radius).coerceAtMost(fullWidth - 1)
        return Roi(row0, col0, col1 - col0 + 1, row1 - row0 + 1)
    }

    /** Copies [roi] out of [frame] (itself possibly just a window of the mosaic); cells the frame doesn't cover read as no-data. */
    private fun crop(frame: RadarFrame, roi: Roi): PresenceGrid {
        val out = ByteArray(roi.width * roi.height) { RadarLegend.NO_DATA_INDEX.toByte() }
        for (r in 0 until roi.height) {
            val frameRow = roi.row0 + r - frame.originRow
            if (frameRow !in 0 until frame.gridHeight) continue
            for (c in 0 until roi.width) {
                val frameCol = roi.col0 + c - frame.originCol
                if (frameCol !in 0 until frame.gridWidth) continue
                out[r * roi.width + c] = frame.grid[frameRow * frame.gridWidth + frameCol]
            }
        }
        return PresenceGrid(roi.width, roi.height, out)
    }

    /**
     * What the map draws: one arrow per rain area a person would call "a cloud" — a connected
     * core of >= 1 mm/h (green and up; the 0.1-0.5 mm/h tiers are pale blues barely different from
     * the dry background) — starting at that core's visual center in the latest radar frame, and
     * moving with the motion of the forecast blob it belongs to. Forecast blobs themselves are
     * whole connected 0.1 mm/h areas that often span many such cores across tens of km, so their
     * single anchor point rarely sits where the eye puts "the cloud".
     */
    private fun displayBlobs(
        scene: Scene,
        motions: List<MotionEstimator.BlobMotion>,
        arrivals: Map<MotionEstimator.BlobMotion, Int?>,
        lagMinutes: Int,
        coreArrival: (coreLabels: IntArray, coreId: Int, motion: MotionEstimator.BlobMotion) -> Int?,
        targetParcel: Triple<MotionEstimator.BlobMotion, Int, Int>?,
        targetMmh: Double?,
    ): List<BlobForecast> {
        val motionByBlobId = motions.associateBy { it.blob.id }
        val coreLabels = IntArray(scene.grid.width * scene.grid.height) { -1 }
        val cores = ConnectedComponents.findBlobs(scene.grid, DISPLAY_MIN_CELLS, coreLabels, maxIndex = DISPLAY_MAX_INDEX)
            .sortedByDescending { it.sizeCells }
            .take(MAX_DISPLAY_BLOBS)
        val fromCores = cores.mapNotNull { core ->
            val (row, col) = visualCenter(core.cells, scene.grid)
            val motion = motionByBlobId[scene.labels[row * scene.grid.width + col]] ?: return@mapNotNull null
            // Highlighted only if this core itself reaches the user — not every core of a wide
            // band that happens to be raining on them somewhere.
            val arrival = if (arrivals[motion] != null) coreArrival(coreLabels, core.id, motion) else null
            buildBlobForecast("core-${core.id}", row.toDouble(), col.toDouble(), core.peakMmh, motion, scene, lagMinutes, arrival)
        }
        // Rain reaching the user but no drawn core among it (usually a band's light leading
        // edge): one highlighted arrow from exactly the parcel of rain that will arrive.
        val parcel = if (fromCores.none { it.arrivalMinutes != null } && targetParcel != null) {
            val (motion, row, col) = targetParcel
            listOf(buildBlobForecast("target", row.toDouble(), col.toDouble(), targetMmh ?: motion.blob.peakMmh, motion, scene, lagMinutes, arrivals[motion]))
        } else {
            emptyList()
        }
        return fromCores + parcel
    }

    /** Intensity-weighted mean position, snapped to the nearest of [cells] so it's always inside the cloud (a curved band's mean can fall outside it). */
    private fun visualCenter(cells: List<IntArray>, grid: PresenceGrid): Pair<Int, Int> {
        var weightSum = 0.0
        var rowAcc = 0.0
        var colAcc = 0.0
        for (cell in cells) {
            val weight = LevelGrid.levelForIndex(grid.valueAt(cell[0], cell[1])).toDouble()
            weightSum += weight
            rowAcc += cell[0] * weight
            colAcc += cell[1] * weight
        }
        val meanRow = rowAcc / weightSum
        val meanCol = colAcc / weightSum
        val nearest = cells.minBy { (it[0] - meanRow) * (it[0] - meanRow) + (it[1] - meanCol) * (it[1] - meanCol) }
        return nearest[0] to nearest[1]
    }

    private fun buildBlobForecast(
        id: String,
        anchorRow: Double,
        anchorCol: Double,
        peakMmh: Double,
        motion: MotionEstimator.BlobMotion,
        scene: Scene,
        lagMinutes: Int,
        arrivalMinutes: Int?,
    ): BlobForecast {
        val blob = motion.blob
        val centroidAtTm = scene.latLonOf(anchorRow, anchorCol)
        val kmPerCellX = haversineKm(centroidAtTm, scene.latLonOf(anchorRow, anchorCol + 1))
        val kmPerCellY = haversineKm(centroidAtTm, scene.latLonOf(anchorRow + 1, anchorCol))

        val eastKmPerMin = motion.vxCellsPerMin * kmPerCellX
        // row increases southward, so northward displacement is the negative of vy.
        val northKmPerMin = -motion.vyCellsPerMin * kmPerCellY
        val speedKmh = sqrt(eastKmPerMin * eastKmPerMin + northKmPerMin * northKmPerMin) * 60.0
        val headingDeg = bearingDeg(eastKm = eastKmPerMin, northKm = northKmPerMin)

        // minutesFromTm = lagMinutes + t gives the position "t minutes from now".
        fun positionAt(minutesFromTm: Int): LatLon =
            offsetLatLon(centroidAtTm, eastKm = eastKmPerMin * minutesFromTm, northKm = northKmPerMin * minutesFromTm)

        val horizonMinutes = if (blob.sizeCells >= LARGE_BLOB_MIN_CELLS) {
            MAX_FORECAST_MINUTES
        } else {
            motion.trackedSpanMinutes.toInt().coerceIn(MIN_FORECAST_HORIZON_MINUTES, MAX_FORECAST_MINUTES)
        }

        // Real observed track (negative minutesFromNow), one point per earlier frame that actually
        // matched — each from its own match's displacement, so this is the blob's true past path,
        // not a backward projection of the future line. See docs/webview-interface.md 3.1.1.
        val pastPoints = motion.matches.map { match ->
            val pos = offsetLatLon(centroidAtTm, eastKm = -match.dxCells * kmPerCellX, northKm = match.dyCells * kmPerCellY)
            PathPoint(-(lagMinutes + match.minutesAgo.toInt()), pos)
        }
        val futurePoints = (PATH_SAMPLE_INTERVAL_MINUTES..horizonMinutes step PATH_SAMPLE_INTERVAL_MINUTES)
            .map { t -> PathPoint(t, positionAt(lagMinutes + t)) }

        return BlobForecast(
            id = id,
            sizeCells = blob.sizeCells,
            centroid = positionAt(lagMinutes),
            // Position as actually observed in the latest radar frame (tm), before the lag
            // extrapolation — lets the web line up the drawn path with the (possibly stale) radar
            // image it's showing. See docs/webview-interface.md.
            observedCentroid = centroidAtTm,
            headingDeg = headingDeg,
            speedKmh = speedKmh,
            path = pastPoints + PathPoint(0, positionAt(lagMinutes)) + futurePoints,
            arrivalMinutes = arrivalMinutes,
            peakMmh = peakMmh,
        )
    }

    private fun offsetLatLon(origin: LatLon, eastKm: Double, northKm: Double): LatLon {
        val dLat = northKm / KM_PER_DEGREE_LAT
        val dLon = eastKm / (KM_PER_DEGREE_LAT * cos(Math.toRadians(origin.lat)))
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }
}
