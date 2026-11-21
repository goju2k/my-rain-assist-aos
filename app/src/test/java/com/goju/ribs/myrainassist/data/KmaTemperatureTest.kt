package com.goju.ribs.myrainassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmaTemperatureTest {
    @Test fun paletteFollowsForecastMonthInKst() {
        assertEquals("E_IDFE_T_9", KmaTemperature.paletteIdFor("2026-11-19T01:00:00"))
        // 11/30 16:00 UTC = 12/1 01:00 KST → winter palette.
        assertEquals("E_IDFE_T_12", KmaTemperature.paletteIdFor("2026-11-30T16:00:00"))
        assertEquals("E_IDFE_T_3", KmaTemperature.paletteIdFor("2026-03-01T00:00:00"))
        assertEquals("E_IDFE_T_6", KmaTemperature.paletteIdFor("2026-07-15T00:00:00"))
    }

    @Test fun exactLegendColourMapsToItsKey() {
        val palette = KmaTemperatureLegends.PALETTES.getValue("E_IDFE_T_9")
        val i = palette.celsius.indexOfFirst { it == 1.0 }
        val argb = (0xFF shl 24) or palette.colors[i]
        assertEquals(1.0f, KmaTemperature.celsiusForPixel(argb, palette))
    }

    @Test fun transparentPixelHasNoValue() {
        val palette = KmaTemperatureLegends.PALETTES.getValue("E_IDFE_T_9")
        assertTrue(KmaTemperature.celsiusForPixel(palette.colors[10], palette).isNaN())
    }

    @Test fun pixelIndexIsNorthUp() {
        assertEquals(0, KmaTemperature.pixelIndex(1, 253))
        assertEquals(252 * 149 + 148, KmaTemperature.pixelIndex(149, 1))
    }

    @Test fun snowAtTwoDegreesOrBelow() {
        assertEquals(PrecipKind.SNOW, PrecipKind.forCelsius(3.0))
        assertEquals(PrecipKind.MIXED, PrecipKind.forCelsius(4.0))
        assertEquals(PrecipKind.RAIN, PrecipKind.forCelsius(5.0))
        assertEquals(PrecipKind.RAIN, PrecipKind.forCelsius(null))
    }
}
