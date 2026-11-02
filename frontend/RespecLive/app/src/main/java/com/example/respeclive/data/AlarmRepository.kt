package com.example.respeclive.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.example.respeclive.alarm.AlarmScheduler
import org.json.JSONArray
import org.json.JSONObject

/** 알람 목록 저장소. 목록은 Compose 상태라 변경되면 화면이 자동 갱신됨 */
object AlarmRepository {
    private const val PREFS = "respec_alarms"
    private const val KEY = "alarms_json"

    val alarms = mutableStateListOf<Alarm>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        alarms.addAll(if (raw == null) defaults() else parse(raw))
        if (raw == null) persist(app)
    }

    fun get(id: Int): Alarm? = alarms.firstOrNull { it.id == id }

    fun nextId(): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    fun upsert(context: Context, alarm: Alarm) {
        val i = alarms.indexOfFirst { it.id == alarm.id }
        if (i >= 0) alarms[i] = alarm else alarms.add(alarm)
        persist(context)
        AlarmScheduler.schedule(context, alarm)
    }

    fun delete(context: Context, id: Int) {
        alarms.removeAll { it.id == id }
        persist(context)
        AlarmScheduler.cancel(context, id)
    }

    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        get(id)?.let { upsert(context, it.copy(enabled = enabled)) }
    }

    /** 요일 켜기/끄기. 마지막 남은 요일은 끌 수 없어 false 반환 */
    fun toggleDay(context: Context, id: Int, day: Int): Boolean {
        val a = get(id) ?: return false
        val newDays = if (day in a.days) a.days - day else a.days + day
        if (newDays.isEmpty()) return false
        upsert(context, a.copy(days = newDays))
        return true
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        alarms.forEach { arr.put(it.toJson()) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun Alarm.toJson() = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", JSONArray(days.toList()))
        .put("enabled", enabled)
        .put("type", type.name)
        .put("mode", mode.name)
        .put("theme", theme ?: JSONObject.NULL)

    private fun parse(raw: String): List<Alarm> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d = o.getJSONArray("days")
            Alarm(
                id = o.getInt("id"),
                label = o.getString("label"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                enabled = o.optBoolean("enabled", true),
                type = runCatching { AlarmType.valueOf(o.getString("type")) }.getOrDefault(AlarmType.WAKE),
                mode = runCatching { AlarmMode.valueOf(o.getString("mode")) }.getOrDefault(AlarmMode.CONTINUOUS),
                theme = if (o.isNull("theme")) null else o.getString("theme")
            )
        }
    } catch (e: Exception) {
        defaults()
    }

    private fun defaults() = listOf(
        Alarm(1, "기상 알람", 7, 0, setOf(0, 1, 2, 3, 4), type = AlarmType.WAKE, theme = "운동"),
        Alarm(2, "취침 알람", 22, 0, (0..6).toSet(), type = AlarmType.BEDTIME)
    )
}