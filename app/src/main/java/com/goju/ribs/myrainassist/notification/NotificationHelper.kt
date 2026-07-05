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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goju.ribs.myrainassist.MainActivity
import com.goju.ribs.myrainassist.R

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

    fun buildOngoingNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("비올까?")
            .setContentText("주변 비구름을 감지하고 있어요")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setContentIntent(contentIntent(context))
            .build()
    }

    fun showIncomingRainAlert(context: Context, etaMinutes: Int): String {
        val hours = etaMinutes / 60
        val minutes = etaMinutes % 60
        val text = when {
            hours == 0 -> "${minutes}분 뒤 비가 옵니다"
            minutes == 0 -> "${hours}시간 뒤 비가 옵니다"
            else -> "${hours}시간 ${minutes}분 뒤 비가 옵니다"
        }
        show(context, "비 소식", text)
        return text
    }

    fun showActiveRainAlert(context: Context): String {
        val text = "지금 비가 오고 있어요"
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
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
    }
}
