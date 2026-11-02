package com.example.respeclive.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.respeclive.MainActivity
import com.example.respeclive.data.AlarmType

object AlarmNotifier {
    private const val CHANNEL_ID = "respec_alarm_v1"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "모닝콜, 굿나잇콜 알람"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 500, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    /** 전체 화면 알림. 화면이 꺼져 있거나 잠금 상태면 앱 화면이 바로 열리고, 사용 중이면 상단 알림으로 표시됨 */
    @SuppressLint("MissingPermission")
    fun show(context: Context, id: Int, type: String, label: String) {
        ensureChannel(context)

        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmExtras.FROM_ALARM, true)
            putExtra(AlarmExtras.ID, id)
            putExtra(AlarmExtras.TYPE, type)
            putExtra(AlarmExtras.LABEL, label)
        }
        val pi = PendingIntent.getActivity(
            context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isWake = type == AlarmType.WAKE.name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isWake) "AI 모닝콜" else "AI 굿나잇콜")
            .setContentText("$label · 눌러서 받기")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT } // 앱 화면이 열려 알림이 취소될 때까지 반복

        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            nm.notify(id, notification)
        } else {
            // 알림 권한이 없으면 직접 열기 시도 (백그라운드에서는 막힐 수 있음)
            try {
                context.startActivity(launch)
            } catch (e: Exception) {
            }
        }
    }
}