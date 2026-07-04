package com.goju.ribs.myrainassist.data

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

/** One radar mosaic frame. [grid] is row-major, one byte per cell, size == gridWidth*gridHeight. */
data class RadarFrame(
    val tm: String,
    val gridWidth: Int,
    val gridHeight: Int,
    val grid: ByteArray,
) {
    /** Epoch minutes derived from [tm], used to compute actual inter-frame deltas. */
    val epochMinute: Long by lazy {
        LocalDateTime.parse(tm, TM_FORMAT).toEpochSecond(ZoneOffset.UTC) / 60
    }
}

data class RadarResponse(
    val corners: List<LatLon>,
    val frames: List<RadarFrame>,
)
