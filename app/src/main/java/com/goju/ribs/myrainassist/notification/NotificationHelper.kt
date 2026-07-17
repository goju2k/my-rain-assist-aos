package com.goju.ribs.myrainassist.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goju.ribs.myrainassist.MainActivity
import com.goju.ribs.myrainassist.R
import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.data.RadarLegend

object NotificationHelper {

    // _v3: MIN hid the status bar icon entirely, and LOW gets grouped under "Silent" without a
    // reliable status bar icon either. A channel's importance is fixed once created, so bumping
    // the id forces a fresh channel at IMPORTANCE_DEFAULT (sound/vibration explicitly muted below)
    // so the running service always shows a status bar icon without making noise.
    const val ONGOING_CHANNEL_ID = "rain_monitor_ongoing_v3"
    private val LEGACY_ONGOING_CHANNEL_IDS = listOf("rain_monitor_ongoing", "rain_monitor_ongoing_v2")
    const val ALERT_CHANNEL_ID = "rain_monitor_alert"
    const val ONGOING_NOTIFICATION_ID = 1
    private const val ALERT_NOTIFICATION_ID = 100
    // Long enough for the user to notice the screen light up and glance at the lock screen
    // notification; the OS will let the screen time out normally after this.
    private const val SCREEN_WAKE_DURATION_MS = 10_000L

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        LEGACY_ONGOING_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL_ID, "비올까 감시 서비스", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "백그라운드에서 강수 레이더를 주기적으로 확인합니다."
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "강수 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "비가 다가오거나 시작될 때 알려줍니다."
            },
        )
    }

    const val DEFAULT_ONGOING_TEXT = "주변 비구름을 감지하고 있어요"

    fun buildOngoingNotification(context: Context, contentText: String = DEFAULT_ONGOING_TEXT): Notification {
        return NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("비올까?")
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setContentIntent(contentIntent(context))
            .build()
    }

    /**
     * [justStopped] is true for exactly the one poll cycle where rain that was actually overhead
     * just cleared. [etaMinutes] mirrors [showIncomingRainAlert]'s wording (same phrase builder) so
     * the always-on ongoing notification never reads vaguer than the alert that was just shown for
     * the same state — they used to drift apart (e.g. "비가 곧 올 것 같아요" here vs "20분 뒤 비가
     * 옵니다" in the alert).
     */
    fun ongoingTextFor(state: ForecastState, justStopped: Boolean, etaMinutes: Int?, intensityMmh: Double? = null): String = if (justStopped) {
        "비가 그쳤어요"
    } else {
        when (state) {
            ForecastState.ACTIVE -> "${RadarLegend.intensityPrefix(intensityMmh)}비가 오고 있어요"
            ForecastState.INCOMING -> etaMinutes?.let { incomingRainText(it, intensityMmh) } ?: DEFAULT_ONGOING_TEXT
            ForecastState.NONE -> DEFAULT_ONGOING_TEXT
        }
    }

    // Past this horizon the linear motion extrapolation is stretched thin (it's fit from only the
    // last ~15 minutes of frames), so the wording should read as a guess rather than a prediction.
    private const val UNCERTAIN_ETA_THRESHOLD_MINUTES = 60

    private fun incomingRainText(etaMinutes: Int, intensityMmh: Double?): String {
        val hours = etaMinutes / 60
        val minutes = etaMinutes % 60
        val timePhrase = when {
            hours == 0 -> "${minutes}분 뒤"
            minutes == 0 -> "${hours}시간 뒤"
            else -> "${hours}시간 ${minutes}분 뒤"
        }
        val prefix = RadarLegend.intensityPrefix(intensityMmh)
        return if (etaMinutes > UNCERTAIN_ETA_THRESHOLD_MINUTES) {
            "${timePhrase}쯤 ${prefix}비가 올 것 같아요"
        } else {
            "$timePhrase ${prefix}비가 옵니다"
        }
    }

    fun showIncomingRainAlert(context: Context, etaMinutes: Int, intensityMmh: Double? = null): String {
        val text = incomingRainText(etaMinutes, intensityMmh)
        show(context, "비 소식", text)
        return text
    }

    fun showActiveRainAlert(context: Context, intensityMmh: Double? = null): String {
        val prefix = RadarLegend.intensityPrefix(intensityMmh)
        val text = "지금 ${prefix}비가 오고 있어요"
        show(context, "비 소식", text)
        return text
    }

    fun showRainStoppedAlert(context: Context): String {
        val text = "비가 그쳤어요"
        show(context, "비 소식", text)
        return text
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        wakeScreen(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Turns the display on so a rain alert isn't missed while the phone is asleep, without
     * launching the app over the lock screen — the screen simply lights up on the lock screen
     * showing the notification, same as an incoming text message would. A plain high-priority
     * notification alone does not wake a sleeping screen, which is why this is needed.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "$ALERT_CHANNEL_ID:screenWake",
        )
        wakeLock.acquire(SCREEN_WAKE_DURATION_MS)
    }
}
