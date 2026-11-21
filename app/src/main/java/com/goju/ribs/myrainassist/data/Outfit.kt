package com.goju.ribs.myrainassist.data

import kotlin.math.roundToInt

/**
 * What to wear at a given temperature — the same table as the web's temperature chip
 * (ribs-home rain-assist/components/ForecastPanel.tsx OUTFITS); keep the two in step.
 */
object Outfit {
    private val TABLE = listOf(
        -5 to "두꺼운 패딩",
        5 to "패딩",
        10 to "얇은 패딩",
        20 to "자켓",
        23 to "긴팔옷",
        30 to "반팔",
    )

    /** "패딩 추천", or "폭염 주의" above the table. */
    fun text(celsius: Double): String {
        val rounded = celsius.roundToInt()
        return TABLE.firstOrNull { rounded <= it.first }?.let { "${it.second} 추천" } ?: "폭염 주의"
    }
}
