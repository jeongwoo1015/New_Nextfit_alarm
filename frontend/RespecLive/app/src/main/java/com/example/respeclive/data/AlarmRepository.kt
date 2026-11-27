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
 * 모닝콜·굿나잇콜 알람 모두 서버(GET/POST/PATCH/DELETE /alarms, alarm_kind WAKE / BEDTIME)가 기준이고
 * 기기에는 사본을 둬서 오프라인에서도 울림. 11/18 전에 기기에만 저장한 굿나잇콜은 동기화할 때 서버에 올림.
 */
object AlarmRepository {
    private const val PREFS = "respec_alarms"
    private const val KEY = "alarms_json"
    private const val DEFAULT_LABEL = "기상 알람"
    private const val DEFAULT_BEDTIME_LABEL = "굿나잇콜"
    private const val KIND_WAKE = "WAKE"
    private const val KIND_BEDTIME = "BEDTIME"

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

    /** 로그아웃: 이 계정의 알람 예약을 모두 끄고 기기 사본을 지움 (다음 계정은 로그인 뒤 서버에서 다시 받음) */
    fun clear(context: Context) {
        val app = context.applicationContext
        alarms.forEach { AlarmScheduler.cancel(app, it.id) }
        alarms.clear()
        persist(app)
    }

    fun get(id: Int): Alarm? = alarms.firstOrNull { it.id == id }

    fun nextId(): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    // ---------------- 서버 동기화 ----------------

    /**
     * 서버 알람 목록으로 기기 알람을 교체 (서버에 없는 알람은 지움). 실패하면 기기 목록 그대로 두고 false.
     * 기기에만 있는 굿나잇콜(11/18 전에 만든 것)은 서버에 올림: 같은 시각의 굿나잇콜이 서버에 이미 있으면 그 알람과 잇고,
     * 올리지 못하면 기기에 그대로 두고 다음 동기화 때 다시 올림. 기기 번호(id)는 그대로라 울릴 예약도 그대로
     */
    suspend fun sync(context: Context): Boolean {
        val server = RespecApi.alarms()?.toMutableList() ?: return false
        val idFor = mutableMapOf<String, Int>()   // 서버 alarm_id → 기기 id
        alarms.forEach { a -> a.serverId?.let { idFor[it] = a.id } }
        val localBedtimes = alarms.filter { it.type == AlarmType.BEDTIME && it.serverId == null }
        for (local in localBedtimes) {
            val time = "%02d:%02d".format(local.hour, local.minute)
            val match = server.firstOrNull {
                it.optString("alarm_kind") == KIND_BEDTIME && it.optString("alarm_time") == time && it.optString("alarm_id") !in idFor
            } ?: RespecApi.createAlarm(bedtimeFields(local))?.also { server.add(it) } ?: continue
            idFor[match.optString("alarm_id")] = local.id
        }
        onMain {
            val app = context.applicationContext
            var next = nextId()
            val synced = server.mapNotNull { o -> fromServer(o, idFor[o.optString("alarm_id")] ?: next++) }
            val notUploaded = alarms.filter { a ->
                a.type == AlarmType.BEDTIME && a.serverId == null && synced.none { it.id == a.id }
            }
            alarms.filter { a -> synced.none { it.id == a.id } && a !in notUploaded }
                .forEach { AlarmScheduler.cancel(app, it.id) }
            alarms.clear()
            alarms.addAll(synced + notUploaded)
            persist(app)
            alarms.forEach { AlarmScheduler.schedule(app, it) }
        }
        return true
    }

    /**
     * 알람 설정 화면의 저장. 서버에 먼저 저장하고 성공하면 기기에 반영. 실패하면 오류 문구.
     * 모닝콜 ↔ 굿나잇콜을 바꾸면 같은 서버 알람의 alarm_kind만 바꿈 (굿나잇콜로 바꾸면 서버가 모닝콜 칸을 비움).
     * routineItems: 루틴 알람의 항목 시간 [{template_item_id, duration_sec}]. 알람을 저장한 뒤 보냄
     * (프리셋을 바꾸면 서버가 항목 설정을 지우므로 알람 먼저). null이면 항목 시간은 그대로
     */
    suspend fun save(context: Context, alarm: Alarm, routineItems: JSONArray? = null): String? {
        val serverId = alarm.serverId ?: get(alarm.id)?.serverId
        if (alarm.type == AlarmType.BEDTIME) {
            val body = bedtimeFields(alarm)
            val saved = (if (serverId != null) RespecApi.updateAlarm(serverId, body) else RespecApi.createAlarm(body))
                ?: return ApiClient.lastError ?: "굿나잇콜 알람을 저장하지 못했어요."
            val synced = fromServer(saved, alarm.id) ?: return "서버 응답을 읽지 못했어요."
            onMain { upsert(context, synced) }
            return null
        }
        val routineTemplate = if (alarm.isContent) null else
            routineTemplate(alarm) ?: return ApiClient.lastError ?: "루틴 프리셋을 찾지 못했어요."
        val body = editableFields(alarm, routineTemplate)
        val saved = if (serverId != null) RespecApi.updateAlarm(serverId, body) else RespecApi.createAlarm(body)
        saved ?: return ApiClient.lastError ?: "알람을 저장하지 못했어요."
        val synced = fromServer(saved, alarm.id) ?: return "서버 응답을 읽지 못했어요."
        onMain { upsert(context, synced) }
        if (!synced.isContent && routineItems != null && synced.serverId != null) {
            val routine = RespecApi.saveAlarmRoutine(synced.serverId, routineItems)
                ?: return ApiClient.lastError ?: "알람은 저장했지만 항목 시간을 저장하지 못했어요."
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
            return ApiClient.lastError ?: "알람을 삭제하지 못했어요."
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

    /** 기기에 먼저 반영 → 서버 PATCH. 서버와 연결 안 된 알람(아직 올리지 못한 굿나잇콜)은 기기에서만 바꿈 */
    private suspend fun change(context: Context, before: Alarm, after: Alarm, changes: JSONObject): String? {
        onMain { upsert(context, after) }
        val sid = after.serverId ?: return null
        val saved = RespecApi.updateAlarm(sid, changes)?.let { fromServer(it, after.id) }
        onMain { upsert(context, saved ?: before) }
        return if (saved == null) ApiClient.lastError ?: "알람을 저장하지 못했어요." else null
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
        .put("alarm_kind", KIND_WAKE)
        .put("alarm_time", "%02d:%02d".format(a.hour, a.minute))
        .put("repeat_days", repeatDays(a.days))
        .put("label", a.label.ifBlank { JSONObject.NULL })
        .put("enabled", a.enabled)
        .put("morning_call_type", if (a.isContent) "CONTENT" else "ROUTINE")
        .put("template_id", routineTemplate ?: JSONObject.NULL)
        .put("content_type", a.contentType ?: JSONObject.NULL)
        .put("content_option", a.contentOption ?: JSONObject.NULL)

    /**
     * 굿나잇콜 알람의 칸 (만들기·바꾸기 같음). 모닝콜 칸(morning_call_type·template_id·content_*)은 보내지 않음:
     * 서버가 비우고 alarm_mode는 정각(ON_TIME)으로 둠
     */
    private fun bedtimeFields(a: Alarm) = JSONObject()
        .put("alarm_kind", KIND_BEDTIME)
        .put("alarm_time", "%02d:%02d".format(a.hour, a.minute))
        .put("repeat_days", repeatDays(a.days))
        .put("label", a.label.ifBlank { JSONObject.NULL })
        .put("enabled", a.enabled)

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
        val bedtime = o.text("alarm_kind") == KIND_BEDTIME
        return Alarm(
            id = id,
            label = o.text("label") ?: if (bedtime) DEFAULT_BEDTIME_LABEL else DEFAULT_LABEL,
            hour = time.getOrNull(0)?.toIntOrNull() ?: return null,
            minute = time.getOrNull(1)?.toIntOrNull() ?: return null,
            days = repeat.indices.filter { repeat[it] == '1' }.toSet(),
            enabled = o.optBoolean("enabled", true),
            type = if (bedtime) AlarmType.BEDTIME else AlarmType.WAKE,
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
