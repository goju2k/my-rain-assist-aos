package com.goju.ribs.myrainassist.ui

import com.goju.ribs.myrainassist.data.RainEpisode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RainHistoryTextTest {
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(RainHistoryText.KST).toInstant().toEpochMilli()

    private val now = kst(2026, 11, 21, 9, 0)

    @Test fun titlesAndDetails() {
        val snow = RainEpisode(kst(2026, 11, 21, 0, 10), kst(2026, 11, 21, 1, 40), 0, snow = true)
        assertEquals("눈 00:10 ~ 01:40", RainHistoryText.title(snow))
        assertEquals("1시간 30분", RainHistoryText.detail(snow, now))
        val ongoing = RainEpisode(kst(2026, 11, 21, 8, 20), null, 0, snow = false)
        assertEquals("비 08:20 ~ 지금", RainHistoryText.title(ongoing))
        assertEquals("40분째 오는 중", RainHistoryText.detail(ongoing, now))
        val missed = RainEpisode(kst(2026, 11, 16, 9, 30), kst(2026, 11, 16, 9, 30), 0, snow = false, missed = true)
        assertEquals("비 소식 · 09:30", RainHistoryText.title(missed))
        assertEquals("예보했지만 비켜 갔어요", RainHistoryText.detail(missed, now))
    }

    @Test fun durations() {
        assertEquals("5분 미만", RainHistoryText.durationText(3))
        assertEquals("15분", RainHistoryText.durationText(15))
        assertEquals("2시간", RainHistoryText.durationText(120))
    }

    @Test fun headlineCountsRealSpellsInLastSevenDays() {
        val list = listOf(
            RainEpisode(kst(2026, 11, 21, 0, 10), kst(2026, 11, 21, 1, 40), 0, snow = true),
            RainEpisode(kst(2026, 11, 18, 14, 20), kst(2026, 11, 18, 16, 5), 0, snow = false),
            RainEpisode(kst(2026, 11, 18, 19, 40), kst(2026, 11, 18, 19, 55), 0, snow = false),
            RainEpisode(kst(2026, 11, 16, 9, 30), kst(2026, 11, 16, 9, 30), 0, snow = false, missed = true),
            RainEpisode(kst(2026, 11, 10, 9, 0), kst(2026, 11, 10, 10, 0), 0, snow = false),
        )
        assertEquals("2일 동안 3번 왔어요", RainHistoryText.headline(list, now))
        assertEquals("비·눈이 오지 않았어요", RainHistoryText.headline(emptyList(), now))
        val week = RainHistoryText.week(list, now)
        assertEquals(7, week.size)
        assertEquals(90L, week.last().minutes)
    }

    @Test fun dayHeaders() {
        val today = RainHistoryText.dateOf(now)
        assertEquals("오늘 · 11월 21일 (토)", RainHistoryText.dayHeader(today, today))
        assertEquals("어제 · 11월 20일 (금)", RainHistoryText.dayHeader(today.minusDays(1), today))
    }
}
