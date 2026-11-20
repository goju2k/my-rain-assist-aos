package com.goju.ribs.myrainassist.ui

import com.goju.ribs.myrainassist.data.RainEpisode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Wording and grouping for the history screen — pure, so it's unit-tested. Times are KST. */
object RainHistoryText {

    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    class DayBar(val date: LocalDate, val minutes: Long)

    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(KST).toLocalDate()

    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(KST).let { "%02d:%02d".format(it.hour, it.minute) }

    private fun minutesOf(e: RainEpisode, nowMs: Long): Long = ((e.endMs ?: nowMs) - e.startMs).coerceAtLeast(0) / 60_000

    /** The last 7 days, oldest first, each with how long it rained/snowed (by start day). */
    fun week(episodes: List<RainEpisode>, nowMs: Long): List<DayBar> {
        val today = dateOf(nowMs)
        return (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            val minutes = episodes.filter { !it.missed && dateOf(it.startMs) == day }.sumOf { minutesOf(it, nowMs).coerceAtLeast(1) }
            DayBar(day, minutes)
        }
    }

    fun headline(episodes: List<RainEpisode>, nowMs: Long): String {
        val from = dateOf(nowMs).minusDays(6)
        val real = episodes.filter { !it.missed && !dateOf(it.startMs).isBefore(from) }
        if (real.isEmpty()) return "비·눈이 오지 않았어요"
        val days = real.map { dateOf(it.startMs) }.distinct().size
        return "${days}일 동안 ${real.size}번 왔어요"
    }

    fun weekdayLabel(date: LocalDate, today: LocalDate): String =
        if (date == today) "오늘" else date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN)

    fun dayHeader(date: LocalDate, today: LocalDate): String {
        val base = "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN)})"
        return when (date) {
            today -> "오늘 · $base"
            today.minusDays(1) -> "어제 · $base"
            else -> base
        }
    }

    fun title(e: RainEpisode): String {
        val word = if (e.snow) "눈" else "비"
        if (e.missed) return "$word 소식 · ${clock(e.startMs)}"
        val end = e.endMs?.let { clock(it) } ?: "지금"
        return "$word ${clock(e.startMs)} ~ $end"
    }

    fun detail(e: RainEpisode, nowMs: Long): String {
        if (e.missed) return "예보했지만 비켜 갔어요"
        val text = durationText(minutesOf(e, nowMs))
        return if (e.endMs == null) "${text}째 오는 중" else text
    }

    fun durationText(minutes: Long): String = when {
        minutes < 5 -> "5분 미만"
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0L -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    /** Where the episode sits within its start day, as fractions of 24h (start, width), for the bar. */
    fun dayFraction(e: RainEpisode, nowMs: Long): Pair<Float, Float> {
        val dayStart = dateOf(e.startMs).atStartOfDay(KST).toInstant().toEpochMilli()
        val day = 24f * 60 * 60 * 1000
        val start = ((e.startMs - dayStart) / day).coerceIn(0f, 1f)
        val end = (((e.endMs ?: nowMs) - dayStart) / day).coerceIn(start, 1f)
        return start to (end - start).coerceAtLeast(0.01f)
    }
}
