package com.goju.ribs.myrainassist.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
// tm is KST wall-clock, not UTC — confirmed by checking a live frame against the real clock (a
// frame timestamped "as UTC" always came out ~9h in the future, which a live feed can't do).
// Parsing it as UTC silently made lagMinutes negative on every cycle, which coerceIn(0, ..)
// floored to exactly 0 — the lag-correction path documented in DEVELOPMENT.md 4.5 (extrapolating
// a blob's position forward to "now" before projecting arrival) has never actually engaged.
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * One radar mosaic frame, or a rectangular window of one. [grid] is row-major, one byte per cell,
 * size == gridWidth*gridHeight. A window (assembled from tiles around the user) sits at
 * [originCol]/[originRow] inside the full [fullWidth]x[fullHeight] mosaic; a whole frame is just
 * the window at origin 0 covering everything.
 */
data class RadarFrame(
    val tm: String,
    val gridWidth: Int,
    val gridHeight: Int,
    val grid: ByteArray,
    val originCol: Int = 0,
    val originRow: Int = 0,
    val fullWidth: Int = gridWidth,
    val fullHeight: Int = gridHeight,
) {
    /** Epoch minutes derived from [tm], used to compute actual inter-frame deltas. */
    val epochMinute: Long by lazy {
        LocalDateTime.parse(tm, TM_FORMAT).atZone(KST).toEpochSecond() / 60
    }
}

data class RadarResponse(
    val corners: List<LatLon>,
    val frames: List<RadarFrame>,
)
