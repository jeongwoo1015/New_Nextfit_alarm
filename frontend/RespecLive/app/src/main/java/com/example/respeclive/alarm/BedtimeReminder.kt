package com.example.respeclive.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/**
 * 굿나잇콜 팝업 (11/17 회의 2번). 굿나잇콜 시각이 되면 전화 화면을 바로 열지 않고 앱 밖에서도 뜨는 알림으로
 * ① 독려 멘트 ② -10분 / +10분 버튼 ③ 최종 굿나잇콜 시각을 보여 줌. 팝업을 누르면 굿나잇콜 수신 화면이 열림.
 * +10분: 최종 시각을 늦추고 그 시각에 팝업을 다시 울림(그날 한 번, shift). -10분: 늦춘 것을 되돌림(처음 시각까지)
 */
object BedtimeReminder {
    private const val STEP_MINUTES = 10         // 버튼 한 번에 바꾸는 시간
    private const val MAX_DELAY_MINUTES = 120   // 처음 시각보다 최대 이만큼 늦출 수 있음
    private const val SAME_RING_MS = 2 * 60_000L   // 미룬 시각과 이만큼 안에 울리면 미뤄서 다시 울린 것으로 봄

    private const val CHANNEL_ID = "respec_bedtime_reminder_v1"
    private const val PREFS = "bedtime_shifts"
    private const val LEGACY_FIRE_REQUEST_BASE = 300_000   // 예전 "30분 전 미리 알림" 예약 (남아 있으면 지우기만)
    private const val LEGACY_ACTION_SHOW = "com.example.respeclive.BEDTIME_REMINDER_SHOW"
    private const val ACTION_REQUEST_BASE = 200_000
    private const val NOTIFICATION_BASE = 400_000

    const val ACTION_MINUS = "com.example.respeclive.BEDTIME_REMINDER_MINUS"
    const val ACTION_PLUS = "com.example.respeclive.BEDTIME_REMINDER_PLUS"
    const val EXTRA_MESSAGE = "bedtime_message"
    const val EXTRA_FROM_REMINDER = "from_bedtime_reminder"   // 팝업을 눌러 굿나잇콜을 받음

    private val MESSAGES = listOf(
        "넥스티가 기다리고 있어요.",
        "오늘 하루는 어떠셨어요?",
        "음악과 함께 잠들 시간이에요. 😴",
        "오늘 하루도 수고 많았어요.",
        "내일 아침을 위해 슬슬 잠들 준비를 해요. 🌙"
    )

    // ---------------- 미룬 시각 (알람 예약용) ----------------

    /**
     * +10분으로 미룬 시각. AlarmScheduler가 이 시각에 굿나잇콜(팝업)을 다시 울림.
     * 미룬 시각이 아직 안 지났고, 처음 시각(original)이 다음 차례이거나 이미 지난 경우에만 씀.
     * 알람 시각을 고쳐 처음 시각이 달라졌거나 미룬 시각이 지났으면 지움
     */
    fun shiftedTime(context: Context, alarmId: Int, normal: Long?, now: Long = System.currentTimeMillis()): Long? {
        val (original, shifted) = peekShift(context, alarmId) ?: return null
        if (shifted > now && (original == normal || original <= now)) return shifted
        clearShift(context, alarmId)
        return null
    }

    /** 미룬 기록 (처음 시각, 미룬 시각). AlarmReceiver가 다시 예약해 기록이 지워지기 전에 읽어 둠 */
    fun peekShift(context: Context, alarmId: Int): Pair<Long, Long>? = readPair(context, alarmId.toString())

    private fun saveShift(context: Context, alarmId: Int, original: Long, shifted: Long) =
        prefs(context).edit().putString(alarmId.toString(), "$original,$shifted").apply()

    private fun clearShift(context: Context, alarmId: Int) = prefs(context).edit().remove(alarmId.toString()).apply()

    // ---------------- 팝업 상태: 처음 울린 시각, 지금 정한 최종 시각 ----------------

    private fun readPopup(context: Context, alarmId: Int) = readPair(context, "pop_$alarmId")

    private fun savePopup(context: Context, alarmId: Int, original: Long, final: Long) =
        prefs(context).edit().putString("pop_$alarmId", "$original,$final").apply()

    private fun clearPopup(context: Context, alarmId: Int) = prefs(context).edit().remove("pop_$alarmId").apply()

    private fun readPair(context: Context, key: String): Pair<Long, Long>? {
        val raw = prefs(context).getString(key, null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toLongOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun minuteOf(ms: Long) = ms - ms % 60_000L

    // ---------------- 울릴 때 / 받을 때 / 끌 때 ----------------

    /**
     * 굿나잇콜 시각에 AlarmReceiver가 부름: 팝업을 새로 울림.
     * previousShift: 다시 예약하기 전의 미룬 기록. 그 미룬 시각에 울린 것이면 처음 시각을 이어 씀
     */
    fun ring(context: Context, alarm: Alarm, previousShift: Pair<Long, Long>?) {
        val now = minuteOf(System.currentTimeMillis())
        val original = previousShift?.takeIf { abs(it.second - now) <= SAME_RING_MS }?.first ?: now
        savePopup(context, alarm.id, original, now)
        dismiss(context, alarm.id)      // 이미 떠 있는 팝업이면 내렸다가 새로 띄워야 다시 울림
        show(context, alarm)
    }

    /** 디버그: 지금이 굿나잇콜 시각인 것처럼 팝업을 띄움 (+10분을 누르면 실제로 10분 뒤 다시 울림) */
    fun ringNow(context: Context, alarm: Alarm) = ring(context, alarm, null)

    /** 팝업을 눌러 굿나잇콜을 받음 (MainActivity가 수신 화면을 열 때). 팝업을 내리고 미뤄 둔 다시 울림도 지움 */
    fun answerNow(context: Context, alarm: Alarm) {
        forget(context, alarm.id)
        AlarmScheduler.schedule(context, alarm)
    }

    /** 팝업과 그날 미룬 기록을 모두 지움 (받았거나 알람을 껐을 때) */
    fun forget(context: Context, alarmId: Int) {
        dismiss(context, alarmId)
        clearPopup(context, alarmId)
        clearShift(context, alarmId)
    }

    fun dismiss(context: Context, alarmId: Int) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + alarmId)
    }

    /** 예전 버전이 예약한 "30분 전 미리 알림"이 남아 있으면 지움 */
    fun cancelLegacy(context: Context, alarmId: Int) {
        val pi = PendingIntent.getBroadcast(
            context, LEGACY_FIRE_REQUEST_BASE + alarmId,
            Intent(context, BedtimeReminderReceiver::class.java).setAction(LEGACY_ACTION_SHOW).putExtra(AlarmExtras.ID, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    // ---------------- 버튼 ----------------

    /** +10분 / -10분: 최종 시각을 바꾸고(늦췄으면 그 시각에 다시 울리도록 예약) 팝업 내용을 고침 */
    fun shift(context: Context, alarm: Alarm, minutes: Int, messageIndex: Int) {
        val (original, final) = readPopup(context, alarm.id) ?: return
        val next = final + minutes * 60_000L
        if (!canMoveTo(next, original)) return
        savePopup(context, alarm.id, original, next)
        if (next > System.currentTimeMillis()) saveShift(context, alarm.id, original, next) else clearShift(context, alarm.id)
        AlarmScheduler.schedule(context, alarm)
        show(context, alarm, messageIndex)
    }

    private fun canMoveTo(final: Long, original: Long): Boolean =
        final >= original && final <= original + MAX_DELAY_MINUTES * 60_000L

    // ---------------- 팝업 ----------------

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "굿나잇콜", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "굿나잇콜 시간을 알려 주고 시간을 미룰 수 있는 알림"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** 팝업을 띄우거나 내용을 고침(버튼). messageIndex가 없으면 멘트를 새로 고름 */
    @SuppressLint("MissingPermission")
    private fun show(context: Context, alarm: Alarm, messageIndex: Int = -1) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val (original, final) = readPopup(context, alarm.id) ?: return
        ensureChannel(context)

        val msgIndex = messageIndex.takeIf { it in MESSAGES.indices } ?: Random.nextInt(MESSAGES.size)
        val message = MESSAGES[msgIndex]
        val finalLabel = labelOf(final)
        val moved = (final - original) / 60_000L
        // 한눈에 보이도록: 제목은 굿나잇콜 시각(미뤘으면 미룬 시각)만, 미룬 시간은 맨 윗줄(앱 이름 옆),
        // 본문은 독려 멘트와 짧은 안내 두 줄
        val title = "$finalLabel 굿나잇콜"
        val guide = "눌러서 넥스티와 함께 하루를 마무리해 보세요."

        // 누르면 굿나잇콜 수신 화면이 열림 (알람이 울린 것과 같은 경로)
        val answer = PendingIntent.getActivity(
            context, NOTIFICATION_BASE + alarm.id,
            AlarmNotifier.launchIntent(context, alarm.id, AlarmType.BEDTIME.name, finalLabel)
                .putExtra(EXTRA_FROM_REMINDER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n$guide"))
            .apply { if (moved > 0) setSubText("${moved}분 미룸") }
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(answer)
            .setOnlyAlertOnce(true)          // 버튼으로 내용을 고칠 때는 다시 울리지 않음
            .setAutoCancel(true)

        if (canMoveTo(final - STEP_MINUTES * 60_000L, original)) {
            builder.addAction(0, "-${STEP_MINUTES}분", actionIntent(context, alarm.id, ACTION_MINUS, 0, msgIndex))
        }
        if (canMoveTo(final + STEP_MINUTES * 60_000L, original)) {
            builder.addAction(0, "+${STEP_MINUTES}분", actionIntent(context, alarm.id, ACTION_PLUS, 1, msgIndex))
        }
        nm.notify(NOTIFICATION_BASE + alarm.id, builder.build())
    }

    private fun labelOf(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return Alarm.timeLabelOf(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun actionIntent(context: Context, alarmId: Int, action: String, k: Int, msgIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, ACTION_REQUEST_BASE + alarmId * 2 + k,
            Intent(context, BedtimeReminderReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmExtras.ID, alarmId)
                .putExtra(EXTRA_MESSAGE, msgIndex),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** 굿나잇콜 팝업의 버튼 (-10분 / +10분) */
class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmRepository.init(context)
        val id = intent.getIntExtra(AlarmExtras.ID, -1)
        val alarm = AlarmRepository.get(id)?.takeIf { it.type == AlarmType.BEDTIME && it.enabled }
        if (alarm == null) {
            BedtimeReminder.forget(context, id)
            return
        }
        val msg = intent.getIntExtra(BedtimeReminder.EXTRA_MESSAGE, -1)
        when (intent.action) {
            BedtimeReminder.ACTION_MINUS -> BedtimeReminder.shift(context, alarm, -10, msg)
            BedtimeReminder.ACTION_PLUS -> BedtimeReminder.shift(context, alarm, 10, msg)
        }
    }
}
