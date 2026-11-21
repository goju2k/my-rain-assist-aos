package com.goju.ribs.myrainassist.data

/**
 * Whether precipitation at the user's spot should be worded as 비, 눈 or 눈/비. Radar alone can't
 * tell, so this comes from the forecast air temperature ([KmaTemperature], whole degrees); unknown
 * temperature reads as rain. Same thresholds as the web page (ribs-home rain/util/kma-temperature.ts).
 */
enum class PrecipKind(val noun: String, val subject: String) {
    RAIN("비", "비가"),
    SNOW("눈", "눈이"),
    MIXED("눈/비", "눈/비가");

    companion object {
        /**
         * User's call (2026-11-21, after real snow at 대천 3℃ and 군산 4℃ while this said 비):
         * 3℃ or below is snow, 4℃ is 눈/비, above that rain.
         */
        const val SNOW_MAX_CELSIUS = 3.0
        const val MIXED_MAX_CELSIUS = 4.0

        fun forCelsius(celsius: Double?): PrecipKind = when {
            celsius == null -> RAIN
            celsius <= SNOW_MAX_CELSIUS -> SNOW
            celsius <= MIXED_MAX_CELSIUS -> MIXED
            else -> RAIN
        }

        /** Stored name back to a kind; unknown → RAIN. */
        fun fromName(name: String?): PrecipKind = entries.firstOrNull { it.name == name } ?: RAIN
    }
}
