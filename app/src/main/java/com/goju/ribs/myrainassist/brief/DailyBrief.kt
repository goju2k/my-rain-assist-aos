package com.goju.ribs.myrainassist.brief

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.analysis.RainForecastResult
import com.goju.ribs.myrainassist.data.Outfit
import com.goju.ribs.myrainassist.data.PrecipKind
import com.goju.ribs.myrainassist.notification.NotificationHelper
import com.goju.ribs.myrainassist.service.RainMonitorService
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * The user's "매일 날씨 알림" choice: on/off and up to [MAX_TIMES] times of day (device time zone),
 * kept sorted earliest first without duplicates.
 */
data class DailyBriefSettings(val enabled: Boolean, val times: List<LocalTime>) {

    fun withTime(time: LocalTime): DailyBriefSettings =
        if (times.size >= MAX_TIMES || time in times) this else copy(times = (times + time).sorted())

    fun withoutTime(time: LocalTime): DailyBriefSettings = copy(times = times - time)

    fun replaceTime(old: LocalTime, new: LocalTime): DailyBriefSettings =
        if (new != old && new in times) withoutTime(old) else copy(times = (times - old + new).sorted())

    companion object {
        const val MAX_TIMES = 5
        private const val PREFS = "daily_brief"
        val DEFAULT_TIME: LocalTime = LocalTime.of(7, 30)

        /** "07:30,18:00" */
        fun encode(times: List<LocalTime>): String = times.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }

        fun decode(text: String?): List<LocalTime> = text.orEmpty().split(",").mapNotNull { part ->
            val (h, m) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { LocalTime.of(h.trim().toInt(), m.trim().toInt()) }.getOrNull()
        }.distinct().sorted().take(MAX_TIMES)

        fun read(context: Context): DailyBriefSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val times = when {
                p.contains("times") -> decode(p.getString("times", null))
                // Before multiple times: a single hour/minute.
                p.contains("hour") -> listOf(LocalTime.of(p.getInt("hour", 7), p.getInt("minute", 30)))
                else -> listOf(DEFAULT_TIME)
            }
            return DailyBriefSettings(p.getBoolean("enabled", false), times)
        }

        fun save(context: Context, settings: DailyBriefSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", settings.enabled)
                .putString("times", encode(settings.times))
                .remove("hour")
                .remove("minute")
                .apply()
            DailyBriefScheduler.schedule(context)
        }
    }
}

/**
 * Books the next daily brief as a wall-clock alarm that starts [RainMonitorService] with
 * [RainMonitorService.ACTION_DAILY_BRIEF]. Re-booked after each brief, on settings change, at boot
 * / app update and when the clock or time zone changes. Exact when "알람 및 리마인더" is allowed.
 */
object DailyBriefScheduler {

    private const val REQUEST_CODE = 2

    fun nextTrigger(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** The soonest of [times] after [now]; only that one is booked, the brief books the next. */
    fun nextTrigger(now: ZonedDateTime, times: List<LocalTime>): ZonedDateTime? =
        times.map { nextTrigger(now, it) }.minOrNull()

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            Intent(context, RainMonitorService::class.java).setAction(RainMonitorService.ACTION_DAILY_BRIEF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val settings = DailyBriefSettings.read(context)
        val next = if (settings.enabled) nextTrigger(ZonedDateTime.now(ZoneId.systemDefault()), settings.times) else null
        if (next == null) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val at = next.toInstant().toEpochMilli()
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        }
    }
}

/** Wording of the brief notification — pure, unit-tested. */
object DailyBriefText {

    /** Title: "기온 3℃ · 패딩 추천", or a plain title when the temperature couldn't be read. */
    fun title(celsius: Double?): String =
        celsius?.let { "기온 ${it.roundToInt()}℃ · ${Outfit.text(it)}" } ?: "지금 날씨"

    /** Body: what's happening with rain/snow here right now. */
    fun body(result: RainForecastResult?, kind: PrecipKind): String = when {
        result == null -> "비 소식은 확인하지 못했어요"
        result.state == ForecastState.ACTIVE ->
            "지금 " + NotificationHelper.ongoingTextFor(result.state, false, result.etaMinutes, result.intensityMmh, result.rainEndMinutes, kind)
        result.state == ForecastState.INCOMING ->
            NotificationHelper.ongoingTextFor(result.state, false, result.etaMinutes, result.intensityMmh, result.rainEndMinutes, kind)
        else -> "지금은 ${kind.noun} 소식이 없어요"
    }
}
