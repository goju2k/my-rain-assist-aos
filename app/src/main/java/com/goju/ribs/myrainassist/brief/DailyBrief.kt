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

/** The user's "매일 날씨 알림" choice: on/off and the time of day (device time zone). */
data class DailyBriefSettings(val enabled: Boolean, val hour: Int, val minute: Int) {
    val time: LocalTime get() = LocalTime.of(hour, minute)

    companion object {
        private const val PREFS = "daily_brief"
        val DEFAULT = DailyBriefSettings(enabled = false, hour = 7, minute = 30)

        fun read(context: Context): DailyBriefSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DailyBriefSettings(
                p.getBoolean("enabled", DEFAULT.enabled),
                p.getInt("hour", DEFAULT.hour),
                p.getInt("minute", DEFAULT.minute),
            )
        }

        fun save(context: Context, settings: DailyBriefSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", settings.enabled)
                .putInt("hour", settings.hour)
                .putInt("minute", settings.minute)
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

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            Intent(context, RainMonitorService::class.java).setAction(RainMonitorService.ACTION_DAILY_BRIEF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val settings = DailyBriefSettings.read(context)
        if (!settings.enabled) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val at = nextTrigger(ZonedDateTime.now(ZoneId.systemDefault()), settings.time).toInstant().toEpochMilli()
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
