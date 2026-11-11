package com.example.respeclive.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.example.respeclive.alarm.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 알람 목록 저장소. 목록은 Compose 상태라 변경되면 화면이 자동 갱신됨.
 * 모닝콜 알람은 서버(GET/POST/PATCH/DELETE /alarms)가 기준이고 기기에는 사본을 둬서 오프라인에서도 울림.
 * 굿나잇콜 알람은 서버에 없어 기기에만 저장.
 */
object AlarmRepository {
    private const val PREFS = "respec_alarms"
    private const val KEY = "alarms_json"
    private const val DEFAULT_LABEL = "기상 알람"

    val alarms = mutableStateListOf<Alarm>()
    private var loaded = false

    fun init(context: Context) {
        WakeOverrides.init(context)   // 알람 시각 계산에 하루만 바꾼 기상 시각이 필요
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        // 기본 알람은 만들지 않음. 모닝콜 알람은 홈에서 서버와 동기화할 때 채워짐
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        alarms.addAll(parse(raw))
    }

    fun get(id: Int): Alarm? = alarms.firstOrNull { it.id == id }

    fun nextId(): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    // ---------------- 서버 동기화 ----------------

    /** 서버 알람 목록으로 모닝콜 알람을 교체 (서버에 없는 모닝콜 알람은 지움). 실패하면 기기 목록 그대로 두고 false */
    suspend fun sync(context: Context): Boolean {
        val server = RespecApi.alarms() ?: return false
        onMain {
            val app = context.applicationContext
            var next = nextId()
            val wakes = server.mapNotNull { o ->
                val old = alarms.firstOrNull { it.serverId != null && it.serverId == o.optString("alarm_id") }
                fromServer(o, old?.id ?: next++)
            }
            alarms.filter { it.type == AlarmType.WAKE && wakes.none { w -> w.id == it.id } }
                .forEach { AlarmScheduler.cancel(app, it.id) }
            val bedtimes = alarms.filter { it.type == AlarmType.BEDTIME }
            alarms.clear()
            alarms.addAll(wakes + bedtimes)
            persist(app)
            alarms.forEach { AlarmScheduler.schedule(app, it) }
        }
        return true
    }

    /**
     * 알람 설정 화면의 저장. 모닝콜 알람은 서버에 먼저 저장하고 성공하면 기기에 반영. 실패하면 오류 문구.
     * routineItems: 루틴 알람의 항목 시간 [{template_item_id, duration_sec}]. 알람을 저장한 뒤 보냄
     * (프리셋을 바꾸면 서버가 항목 설정을 지우므로 알람 먼저). null이면 항목 시간은 그대로
     */
    suspend fun save(context: Context, alarm: Alarm, routineItems: JSONArray? = null): String? {
        if (alarm.type != AlarmType.WAKE) {
            // 모닝콜을 굿나잇콜로 바꾸면 서버의 모닝콜 알람을 먼저 지움 (굿나잇콜은 기기에만 저장)
            val serverId = alarm.serverId ?: get(alarm.id)?.serverId
            if (serverId != null && !RespecApi.deleteAlarm(serverId)) {
                return ApiClient.lastError ?: "모닝콜 알람을 굿나잇콜로 바꾸지 못했어요"
            }
            onMain {
                upsert(context, alarm.copy(serverId = null, templateId = null, contentType = null, contentOption = null))
            }
            return null
        }
        // 굿나잇콜을 모닝콜로 바꾸면 serverId가 없어 아래에서 서버에 새로 만듦
        val routineTemplate = if (alarm.isContent) null else
            routineTemplate(alarm) ?: return ApiClient.lastError ?: "루틴 프리셋을 찾지 못했어요"
        val body = editableFields(alarm, routineTemplate)
        val saved = if (alarm.serverId != null) RespecApi.updateAlarm(alarm.serverId, body) else RespecApi.createAlarm(body)
        saved ?: return ApiClient.lastError ?: "알람을 저장하지 못했어요"
        val synced = fromServer(saved, alarm.id) ?: return "서버 응답을 읽지 못했어요"
        onMain { upsert(context, synced) }
        if (!synced.isContent && routineItems != null && synced.serverId != null) {
            val routine = RespecApi.saveAlarmRoutine(synced.serverId, routineItems)
                ?: return ApiClient.lastError ?: "알람은 저장했지만 항목 시간을 저장하지 못했어요"
            onMain { RoutineCatalog.putRoutine(synced, routine) }
        }
        return null
    }

    /** 목록의 켜기/끄기. 화면은 바로 바뀌고 서버 저장이 실패하면 되돌림 */
    suspend fun setEnabled(context: Context, id: Int, enabled: Boolean): String? {
        val a = get(id) ?: return null
        return change(context, a, a.copy(enabled = enabled), JSONObject().put("enabled", enabled))
    }

    suspend fun delete(context: Context, id: Int): String? {
        val a = get(id) ?: return null
        if (a.serverId != null && !RespecApi.deleteAlarm(a.serverId)) {
            return ApiClient.lastError ?: "알람을 삭제하지 못했어요"
        }
        onMain {
            alarms.removeAll { it.id == id }
            persist(context)
            AlarmScheduler.cancel(context, id)
        }
        return null
    }

    // ---------------- 기기 저장 ----------------

    private fun upsert(context: Context, alarm: Alarm) {
        val i = alarms.indexOfFirst { it.id == alarm.id }
        if (i >= 0) alarms[i] = alarm else alarms.add(alarm)
        persist(context)
        AlarmScheduler.schedule(context, alarm)
    }

    /** 기기에 먼저 반영 → 서버 PATCH. 서버와 연결 안 된 알람(취침 알람 등)은 기기에서만 바꿈 */
    private suspend fun change(context: Context, before: Alarm, after: Alarm, changes: JSONObject): String? {
        onMain { upsert(context, after) }
        val sid = after.serverId ?: return null
        val saved = RespecApi.updateAlarm(sid, changes)?.let { fromServer(it, after.id) }
        onMain { upsert(context, saved ?: before) }
        return if (saved == null) ApiClient.lastError ?: "알람을 저장하지 못했어요" else null
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

    // ---------------- 서버 형식 ----------------

    /** days(0=월 … 6=일) → "1111100" */
    private fun repeatDays(days: Set<Int>) = (0..6).joinToString("") { if (it in days) "1" else "0" }

    /**
     * 알람 설정 화면에서 바꾸는 칸. 테마(content_type)가 있으면 콘텐츠 알람, 없으면 루틴 알람.
     * 유형이 바뀔 수 있어 반대쪽 칸은 null로 함께 보냄 (서버 AlarmPatch 규칙)
     */
    private fun editableFields(a: Alarm, routineTemplate: String?) = JSONObject()
        .put("alarm_time", "%02d:%02d".format(a.hour, a.minute))
        .put("repeat_days", repeatDays(a.days))
        .put("label", a.label.ifBlank { JSONObject.NULL })
        .put("enabled", a.enabled)
        .put("morning_call_type", if (a.isContent) "CONTENT" else "ROUTINE")
        .put("template_id", routineTemplate ?: JSONObject.NULL)
        .put("content_type", a.contentType ?: JSONObject.NULL)
        .put("content_option", a.contentOption ?: JSONObject.NULL)

    /** 루틴 알람의 프리셋: 이 알람에 있던 것 → 다른 루틴 알람의 것 → 첫 번째 프리셋 */
    private suspend fun routineTemplate(a: Alarm): String? =
        a.templateId
            ?: alarms.firstOrNull { it.type == AlarmType.WAKE && it.templateId != null }?.templateId
            ?: RespecApi.presets()?.firstOrNull()?.optString("template_id")?.takeIf { it.isNotBlank() }

    /** AlarmOut → Alarm */
    private fun fromServer(o: JSONObject, id: Int): Alarm? {
        val serverId = o.text("alarm_id") ?: return null
        val time = o.text("alarm_time")?.split(":") ?: return null
        val repeat = o.text("repeat_days") ?: "1111111"
        return Alarm(
            id = id,
            label = o.text("label") ?: DEFAULT_LABEL,
            hour = time.getOrNull(0)?.toIntOrNull() ?: return null,
            minute = time.getOrNull(1)?.toIntOrNull() ?: return null,
            days = repeat.indices.filter { repeat[it] == '1' }.toSet(),
            enabled = o.optBoolean("enabled", true),
            type = AlarmType.WAKE,
            serverId = serverId,
            templateId = o.text("template_id"),
            contentType = o.text("content_type"),
            contentOption = o.text("content_option"),
            createdAt = o.text("created_at"),
            updatedAt = o.text("updated_at")
        )
    }

    /** 없는 칸·null(JSONObject.NULL)·빈 값이면 null */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    // ---------------- 기기 저장 형식 ----------------

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
        .put("server_id", serverId ?: JSONObject.NULL)
        .put("template_id", templateId ?: JSONObject.NULL)
        .put("content_type", contentType ?: JSONObject.NULL)
        .put("content_option", contentOption ?: JSONObject.NULL)
        .put("created_at", createdAt ?: JSONObject.NULL)
        .put("updated_at", updatedAt ?: JSONObject.NULL)

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
                serverId = o.text("server_id"),
                templateId = o.text("template_id"),
                contentType = o.text("content_type"),
                contentOption = o.text("content_option"),
                createdAt = o.text("created_at"),
                updatedAt = o.text("updated_at")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
