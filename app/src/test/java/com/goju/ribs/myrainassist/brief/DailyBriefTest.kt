package com.goju.ribs.myrainassist.brief

import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.analysis.RainForecastResult
import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.Outfit
import com.goju.ribs.myrainassist.data.PrecipKind
import com.goju.ribs.myrainassist.ui.koreanClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyBriefTest {
    private fun result(state: ForecastState, eta: Int? = null, mmh: Double? = null, end: Int? = null) =
        RainForecastResult(0, LatLon(37.0, 127.0), state, eta, emptyList(), null, mmh, "202611210700", 0, 4, 8, end)

    @Test fun title() {
        assertEquals("기온 3℃ · 패딩 추천", DailyBriefText.title(3.0))
        assertEquals("기온 -6℃ · 두꺼운 패딩 추천", DailyBriefText.title(-6.0))
        assertEquals("지금 날씨", DailyBriefText.title(null))
    }

    @Test fun body() {
        assertEquals("지금은 눈 소식이 없어요", DailyBriefText.body(result(ForecastState.NONE), PrecipKind.SNOW))
        assertEquals("지금은 비 소식이 없어요", DailyBriefText.body(result(ForecastState.NONE), PrecipKind.RAIN))
        assertEquals("20분 뒤 약한 눈/비가 옵니다", DailyBriefText.body(result(ForecastState.INCOMING, eta = 20, mmh = 1.0), PrecipKind.MIXED))
        assertEquals("지금 약한 비가 오고 있어요 · 40분 뒤쯤 그칠 것 같아요", DailyBriefText.body(result(ForecastState.ACTIVE, mmh = 1.0, end = 38), PrecipKind.RAIN))
        assertEquals("비 소식은 확인하지 못했어요", DailyBriefText.body(null, PrecipKind.RAIN))
    }

    @Test fun outfitMatchesWebTable() {
        assertEquals("두꺼운 패딩 추천", Outfit.text(-5.0))
        assertEquals("얇은 패딩 추천", Outfit.text(10.0))
        assertEquals("긴팔옷 추천", Outfit.text(22.6))
        assertEquals("폭염 주의", Outfit.text(31.0))
    }

    @Test fun nextTrigger() {
        val zone = ZoneId.of("Asia/Seoul")
        val before = ZonedDateTime.of(2026, 11, 21, 7, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 7, 30, 0, 0, zone), DailyBriefScheduler.nextTrigger(before, LocalTime.of(7, 30)))
        val after = ZonedDateTime.of(2026, 11, 21, 7, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 7, 30, 0, 0, zone), DailyBriefScheduler.nextTrigger(after, LocalTime.of(7, 30)))
    }

    @Test fun clockText() {
        assertEquals("오전 7:30", koreanClock(7, 30))
        assertEquals("오후 12:05", koreanClock(12, 5))
        assertEquals("오전 12:00", koreanClock(0, 0))
    }
}
