package com.goju.ribs.myrainassist.data

/**
 * KMA's radar precipitation-intensity color palette ("rdr_rain"), sourced from
 * https://www.weather.go.kr/w/resources/lib/kmap/legend.js. Index order is severity-ranked, index
 * 0 being the most intense; index 23 ("0 mm/h") is a real rendered color for the no-precipitation
 * tier and must not be confused with a fully transparent (no-data) pixel.
 */
object RadarLegend {

    data class Level(val index: Int, val mmh: Double, val r: Int, val g: Int, val b: Int)

    val LEVELS = listOf(
        Level(0, 110.0, 51, 51, 51),
        Level(1, 90.0, 0, 3, 144),
        Level(2, 80.0, 76, 78, 177),
        Level(3, 70.0, 179, 180, 222),
        Level(4, 60.0, 147, 0, 228),
        Level(5, 50.0, 179, 41, 255),
        Level(6, 40.0, 201, 105, 255),
        Level(7, 30.0, 224, 169, 255),
        Level(8, 25.0, 180, 0, 0),
        Level(9, 20.0, 210, 0, 0),
        Level(10, 15.0, 255, 50, 0),
        Level(11, 10.0, 255, 102, 0),
        Level(12, 9.0, 204, 170, 0),
        Level(13, 8.0, 224, 185, 0),
        Level(14, 7.0, 249, 205, 0),
        Level(15, 6.0, 255, 220, 31),
        Level(16, 5.0, 255, 225, 0),
        Level(17, 4.0, 0, 90, 0),
        Level(18, 3.0, 0, 140, 0),
        Level(19, 2.0, 0, 190, 0),
        Level(20, 1.0, 0, 255, 0),
        Level(21, 0.5, 0, 51, 245),
        Level(22, 0.1, 0, 155, 245),
        Level(23, 0.0, 0, 200, 255),
    )

    /** Indices at or below this are a real rain tier; index 23 (0 mm/h) and the no-data sentinel are not. */
    const val RAIN_THRESHOLD_INDEX = 22
    const val NO_DATA_INDEX = 255

    /** Nearest-color match against [LEVELS] — PNG edge pixels get anti-aliased, so an exact match isn't guaranteed. */
    fun nearestIndex(r: Int, g: Int, b: Int): Int {
        var bestIndex = LEVELS[0].index
        var bestDist = Int.MAX_VALUE
        for (level in LEVELS) {
            val dr = r - level.r
            val dg = g - level.g
            val db = b - level.b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = level.index
            }
        }
        return bestIndex
    }

    fun mmhForIndex(index: Int): Double = LEVELS.getOrNull(index)?.mmh ?: 0.0

    /** Matches KMA's own 약한/보통/강한/매우 강한 precipitation-intensity wording. */
    fun labelForMmh(mmh: Double): String = when {
        mmh >= 30.0 -> "매우 강한 비"
        mmh >= 15.0 -> "강한 비"
        mmh >= 3.0 -> "보통 비"
        else -> "약한 비"
    }

    /** Adjective to prepend to "비" in a notification, e.g. "강한 비가 옵니다". "보통" reads as filler in Korean, so it's omitted. */
    fun intensityPrefix(mmh: Double?): String = when {
        mmh == null -> ""
        mmh >= 30.0 -> "매우 강한 "
        mmh >= 15.0 -> "강한 "
        mmh < 3.0 -> "약한 "
        else -> ""
    }

    /** Coarse severity bucket matching the same 약한/보통/강한/매우 강한 boundaries above, for detecting a wording-relevant intensity change while rain is already active. */
    fun intensityTier(mmh: Double?): Int = when {
        mmh == null -> -1
        mmh >= 30.0 -> 3
        mmh >= 15.0 -> 2
        mmh >= 3.0 -> 1
        else -> 0
    }
}
