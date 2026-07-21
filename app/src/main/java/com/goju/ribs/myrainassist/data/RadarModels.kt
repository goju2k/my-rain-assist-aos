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

/** One radar mosaic frame. [grid] is row-major, one byte per cell, size == gridWidth*gridHeight. */
data class RadarFrame(
    val tm: String,
    val gridWidth: Int,
    val gridHeight: Int,
    val grid: ByteArray,
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
