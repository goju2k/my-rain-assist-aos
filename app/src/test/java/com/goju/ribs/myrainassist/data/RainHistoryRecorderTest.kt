package com.goju.ribs.myrainassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainHistoryRecorderTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    private fun step(list: List<RainEpisode>, at: Long, active: Boolean = false, stopped: Boolean = false, missed: Boolean = false, snow: Boolean = false) =
        RainHistoryRecorder.update(list, at, active, stopped, missed, if (snow) PrecipKind.SNOW else PrecipKind.RAIN)

    @Test fun opensExtendsAndClosesOnStop() {
        var h = step(emptyList(), t0, active = true, snow = true)
        h = step(h, t0 + 3 * min, active = true)
        assertEquals(1, h.size)
        assertNull(h[0].endMs)
        assertEquals(t0 + 3 * min, h[0].lastSeenMs)
        assertEquals(PrecipKind.SNOW, h[0].kind)
        h = step(h, t0 + 9 * min, stopped = true)
        assertEquals(t0 + 9 * min, h[0].endMs)
        h = step(h, t0 + 12 * min, active = true)
        assertEquals(2, h.size)
    }

    @Test fun staleOpenClosesAtLastSeen() {
        var h = step(emptyList(), t0, active = true)
        h = step(h, t0 + 5 * min, active = true)
        h = step(h, t0 + 60 * min)
        assertEquals(t0 + 5 * min, h[0].endMs)
    }

    @Test fun missedForecastIsItsOwnEntry() {
        val h = step(emptyList(), t0, missed = true)
        assertEquals(1, h.size)
        assertTrue(h[0].missed)
        assertEquals(t0, h[0].endMs)
    }

    @Test fun dropsOlderThanRetention() {
        val old = RainEpisode(t0, t0 + min, t0 + min, kind = PrecipKind.RAIN)
        val h = step(listOf(old), t0 + RainHistoryRecorder.RETENTION_MS + 2 * min)
        assertTrue(h.isEmpty())
    }

    @Test fun nothingHappeningChangesNothing() {
        val h = listOf(RainEpisode(t0, t0 + min, t0 + min, kind = PrecipKind.RAIN))
        assertEquals(h, step(h, t0 + 10 * min))
    }
}

class RainHistoryStoredKindTest {
    @Test fun thresholds() {
        assertEquals(PrecipKind.SNOW, PrecipKind.forCelsius(3.0))
        assertEquals(PrecipKind.MIXED, PrecipKind.forCelsius(4.0))
        assertEquals(PrecipKind.RAIN, PrecipKind.forCelsius(5.0))
        assertEquals(PrecipKind.RAIN, PrecipKind.forCelsius(null))
        assertEquals(PrecipKind.MIXED, PrecipKind.fromName("MIXED"))
        assertEquals(PrecipKind.RAIN, PrecipKind.fromName("??"))
    }
}
