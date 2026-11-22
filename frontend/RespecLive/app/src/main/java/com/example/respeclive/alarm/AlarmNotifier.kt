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
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.respeclive.MainActivity
import com.example.respeclive.audio.AlarmVibrator
import com.example.respeclive.data.AlarmType

object AlarmNotifier {
    // 한 번 만든 채널의 진동 설정은 앱에서 바꿀 수 없어서, 설정의 알람 진동에 따라 쓸 채널을 둘로 나눔.
    // 진동 채널을 남기는 이유: 다른 앱을 쓰는 중이라 상단 알림만 뜨면 수신 화면의 진동(AlarmVibrator)이 돌지 않음
    private const val CHANNEL_VIBRATE = "respec_alarm_v2"
    private const val CHANNEL_SILENT = "respec_alarm_v2_novibrate"
    private const val LEGACY_CHANNEL = "respec_alarm_v1"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.getNotificationChannel(LEGACY_CHANNEL)?.let { nm.deleteNotificationChannel(LEGACY_CHANNEL) }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun create(id: String, name: String, vibrate: Boolean) {
            if (nm.getNotificationChannel(id) != null) return
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "모닝콜, 굿나잇콜 알람"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
                enableVibration(vibrate)
                if (vibrate) vibrationPattern = longArrayOf(0, 500, 500, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        create(CHANNEL_VIBRATE, "알람", vibrate = true)
        create(CHANNEL_SILENT, "알람 (진동 없음)", vibrate = false)
    }

    /** 설정에서 알람 진동을 껐으면 진동 없는 채널 */
    private fun channelFor(context: Context) =
        if (AlarmVibrator.isEnabled(context)) CHANNEL_VIBRATE else CHANNEL_SILENT

    /** 전체 화면 알림. 화면이 꺼져 있거나 잠금 상태면 앱 화면이 바로 열리고, 사용 중이면 상단 알림으로 표시됨 */
    @SuppressLint("MissingPermission")
    fun show(context: Context, id: Int, type: String, label: String) {
        ensureChannel(context)

        val launch = launchIntent(context, id, type, label)
        val pi = PendingIntent.getActivity(
            context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isWake = type == AlarmType.WAKE.name
        val notification = NotificationCompat.Builder(context, channelFor(context))
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
        val notified = nm.areNotificationsEnabled()
        if (notified) nm.notify(id, notification)

        // '다른 앱 위에 표시'가 켜져 있으면 폰을 쓰는 중에도 팝업 대신 수신 화면을 바로 연다.
        // 알림은 열기가 막혔을 때를 위한 예비로 남겨 두고, 화면이 열리면 MainActivity가 취소한다.
        // 알림 권한이 없을 때도 열기를 시도한다(오버레이 권한이 없으면 백그라운드에서는 막힐 수 있음).
        if (Settings.canDrawOverlays(context) || !notified) {
            try {
                context.startActivity(launch)
            } catch (e: Exception) {
            }
        }
    }

    /** 디버그: 알람 시각을 기다리지 않고 지금 바로 수신 화면을 엶 (알람이 울려 알림을 누른 것과 같은 경로) */
    fun ringNow(context: Context, id: Int, type: String, label: String) {
        context.startActivity(launchIntent(context, id, type, label))
    }

    internal fun launchIntent(context: Context, id: Int, type: String, label: String) =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmExtras.FROM_ALARM, true)
            putExtra(AlarmExtras.ID, id)
            putExtra(AlarmExtras.TYPE, type)
            putExtra(AlarmExtras.LABEL, label)
        }
}