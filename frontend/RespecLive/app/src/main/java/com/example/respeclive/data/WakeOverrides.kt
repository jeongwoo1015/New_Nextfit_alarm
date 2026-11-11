package com.example.respeclive.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.example.respeclive.alarm.AlarmScheduler
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 하루만 바꾼 기상 시각. 굿나잇콜에서 "내일은 7시에 깨워 줘"라고 하면 서버가 밤 기록에
 * override_wake_time(그 알람 override_alarm_id, 그날 apply_date)을 저장하는데, 알람 예약은 기기가 하므로 여기 저장해 두고
 * AlarmScheduler가 그날만 이 시각으로 울림. 서버 알람 id로 저장 (기기 알람 id는 동기화 때 바뀔 수 있음).
 */
object WakeOverrides {
    private const val PREFS = "wake_overrides"

    data class Override(val date: String, val hour: Int, val minute: Int)   // date "YYYY-MM-DD"

    // 화면(홈 알람 카드)이 바뀌면 바로 다시 그리도록 Compose 상태로 둠
    private val byServerAlarm = mutableStateMapOf<String, Override>()
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        val today = dateKey(Date())
        prefs.all.forEach { (alarmId, raw) ->
            val o = runCatching { JSONObject(raw as String) }.getOrNull() ?: return@forEach
            val ov = Override(o.optString("date"), o.optInt("hour"), o.optInt("minute"))
            if (ov.date >= today) byServerAlarm[alarmId] = ov
        }
        // 지난 날짜는 지움
        prefs.edit().apply { prefs.all.keys.filter { it !in byServerAlarm }.forEach { remove(it) } }.apply()
    }

    /** 이 알람의 하루 변경 (없거나 서버 알람이 아니면 null) */
    @Synchronized
    fun forAlarm(alarm: Alarm): Override? = alarm.serverId?.let { byServerAlarm[it] }

    /**
     * 알람 카드에 보여 줄 하루 변경: 켜진 알람의 다음 울릴 날이 바로 그 변경 날이면 그 변경.
     * 그날 알람이 울려 다음 차례가 다른 날로 넘어가면 null이 되어 카드는 원래 시각으로 돌아옴
     */
    fun upcoming(alarm: Alarm): Override? {
        val ov = forAlarm(alarm)?.takeIf { alarm.enabled } ?: return null
        val next = AlarmScheduler.nextTriggerMillis(alarm) ?: return null
        return ov.takeIf { dateKey(Date(next)) == it.date }
    }

    /**
     * 추출이 끝난 밤 기록(NightRecordOut)을 반영. 그날(apply_date)에 걸려 있던 변경을 지우고,
     * override_wake_time이 있으면 그 알람만 새로 걸고 알람을 다시 예약함. 지난 날짜·추출 전이면 무시
     */
    fun applyNightRecord(context: Context, record: JSONObject) {
        if (record.optString("extract_status") != "DONE") return
        val applyDate = record.text("apply_date") ?: return
        if (applyDate < dateKey(Date())) return
        val time = record.text("override_wake_time")?.split(":")
        val alarmId = record.text("override_alarm_id")
        val hour = time?.getOrNull(0)?.toIntOrNull()
        val minute = time?.getOrNull(1)?.toIntOrNull()
        synchronized(this) {
            byServerAlarm.filterValues { it.date == applyDate }.keys.forEach { byServerAlarm.remove(it) }
            if (alarmId != null && hour != null && minute != null) byServerAlarm[alarmId] = Override(applyDate, hour, minute)
            save(context)
        }
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }

    /**
     * 홈을 열 때: 굿나잇콜 직후 확인하는 동안 추출이 안 끝났을 수 있어 어젯밤·오늘 밤 기록을 다시 확인해 반영
     * (GET /night/records?record_date=, 없으면 404)
     */
    suspend fun refresh(context: Context) {
        val cal = Calendar.getInstance()
        val days = listOf(cal.time, cal.apply { add(Calendar.DAY_OF_YEAR, -1) }.time).map { dateKey(it) }
        days.forEach { d -> RespecApi.nightRecordByDate(d)?.let { applyNightRecord(context, it) } }
    }

    private fun save(context: Context) {
        prefs(context).edit().apply {
            clear()
            byServerAlarm.forEach { (id, ov) ->
                putString(id, JSONObject().put("date", ov.date).put("hour", ov.hour).put("minute", ov.minute).toString())
            }
        }.apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dateKey(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
