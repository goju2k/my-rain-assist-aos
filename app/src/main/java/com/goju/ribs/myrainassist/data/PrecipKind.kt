package com.goju.ribs.myrainassist.data

/**
 * Whether precipitation at the user's spot should be worded as 비 or 눈. Radar alone can't tell, so
 * this comes from the forecast air temperature ([KmaTemperature]); unknown temperature reads as rain.
 * Same threshold as the web page (ribs-home rain/util/kma-temperature.ts SNOW_MAX_CELSIUS).
 */
enum class PrecipKind(val noun: String, val subject: String) {
    RAIN("비", "비가"),
    SNOW("눈", "눈이");

    companion object {
        /** User's call: at +2℃ or below, what falls is snow. */
        const val SNOW_MAX_CELSIUS = 2.0

        fun forCelsius(celsius: Double?): PrecipKind =
            if (celsius != null && celsius <= SNOW_MAX_CELSIUS) SNOW else RAIN
    }
}
