package com.example.respeclive.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.respeclive.MainActivity
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.WakeOverrides
import java.util.Calendar

object AlarmExtras {
    const val ID = "alarm_id"
    const val TYPE = "alarm_type"
    const val LABEL = "alarm_label"
    const val FROM_ALARM = "from_alarm"
}

/** 알람으로 앱이 열렸을 때 화면 이동 요청 (token은 매번 달라서 같은 알람이 다시 와도 새 요청으로 인식) */
data class AlarmLaunch(
    val route: String,
    val label: String,
    val alarmId: Int = -1,
    val token: Long = System.nanoTime()
)

object AlarmScheduler {
    private const val TEST_ID = 9999
    private const val SNOOZE_REQUEST_BASE = 100_000   // 스누즈는 정규 알람과 다른 요청 코드 (서로 덮어쓰지 않게)
    const val SNOOZE_MINUTES = 5

    /**
     * 다음 울릴 시각 (켜진 요일 중 지금 이후 가장 가까운 시각).
     * 밤 대화로 하루만 바꾼 기상 시각(WakeOverrides)이 있으면 그날은 그 시각에 울림 (그날이 원래 쉬는 요일이어도)
     */
    fun nextTriggerMillis(alarm: Alarm, now: Long = System.currentTimeMillis()): Long? {
        val override = WakeOverrides.forAlarm(alarm)
        if (alarm.days.isEmpty() && override == null) return null
        val cal = Calendar.getInstance()
        for (offset in 0..7) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, offset)
            val isOverrideDay = override != null && WakeOverrides.dateKey(cal.time) == override.date
            cal.set(Calendar.HOUR_OF_DAY, if (isOverrideDay) override!!.hour else alarm.hour)
            cal.set(Calendar.MINUTE, if (isOverrideDay) override!!.minute else alarm.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 월=0 ... 일=6
            if ((isOverrideDay || dayIndex in alarm.days) && cal.timeInMillis > now) return cal.timeInMillis
        }
        return null
    }

    fun schedule(context: Context, alarm: Alarm) {
        cancel(context, alarm.id)
        if (!alarm.enabled) return
        val trigger = nextTriggerMillis(alarm) ?: return
        // 수신 화면에 보일 시각: 하루만 바꾼 날이면 바뀐 시각
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        val label = Alarm.timeLabelOf(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        setAlarm(context, alarm.id, alarm.id, alarm.type, label, trigger)
    }

    /** "5분 뒤 다시 울리기". 알람 id는 그대로라 다시 울리면 같은 서버 세션을 받음 */
    fun scheduleSnooze(context: Context, alarmId: Int, label: String, minutes: Int = SNOOZE_MINUTES) {
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        setAlarm(context, SNOOZE_REQUEST_BASE + alarmId.coerceAtLeast(0), alarmId, AlarmType.WAKE, label, trigger)
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, id, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun rescheduleAll(context: Context) {
        AlarmRepository.alarms.forEach { schedule(context, it) }
    }

    /** 테스트용: N초 뒤 모닝콜 (저장되지 않음) */
    fun scheduleTest(context: Context, seconds: Int = 10) {
        val trigger = System.currentTimeMillis() + seconds * 1000L
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        val label = Alarm.timeLabelOf(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        setAlarm(context, TEST_ID, TEST_ID, AlarmType.WAKE, label, trigger)
    }

    fun describeRemaining(alarm: Alarm): String {
        val t = nextTriggerMillis(alarm) ?: return ""
        val totalMin = ((t - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)
        val d = totalMin / 1440
        val h = totalMin % 1440 / 60
        val m = totalMin % 60
        val parts = buildList {
            if (d > 0) add("${d}일")
            if (h > 0) add("${h}시간")
            if (m > 0 || (d == 0 && h == 0)) add("${m}분")
        }
        return parts.joinToString(" ") + " 후에 울려요"
    }

    private fun setAlarm(context: Context, requestCode: Int, id: Int, type: AlarmType, label: String, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val fire = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmExtras.ID, id)
                putExtra(AlarmExtras.TYPE, type.name)
                putExtra(AlarmExtras.LABEL, label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 상태바 알람 아이콘을 눌렀을 때 열 화면
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fire)
    }
}