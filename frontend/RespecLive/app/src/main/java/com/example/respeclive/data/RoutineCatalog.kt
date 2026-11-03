package com.example.respeclive.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 알람 목록·알람 설정·체크리스트가 함께 쓰는 서버 값 캐시.
 * 프리셋(RS_ROUTINE_TEMPLATE·_ITEM), 알람별 항목 시간(RS_ALARM_ROUTINE_ITEM을 덮어쓴 목록), 앱 설정(RS_APP_CONFIG).
 * 모두 Compose 상태라 받아 오면 화면이 바로 바뀜.
 */
object RoutineCatalog {
    /** 프리셋 항목 또는 알람의 항목. durationSec는 이 알람에서 쓰는 시간 (0 = 뺀 항목, 프리셋이면 기본 시간) */
    data class Item(
        val templateItemId: String,
        val name: String,
        val order: Int,
        val defaultSec: Int,
        val durationSec: Int,
        val responseType: String
    ) {
        val customized: Boolean get() = durationSec != defaultSec
    }

    data class AlarmRoutine(val templateId: String, val totalSec: Int, val maxSec: Int, val items: List<Item>) {
        /** 실제로 진행하는 항목 (0초 = 뺀 항목 제외), 순서대로 */
        val activeItems: List<Item> get() = items.filter { it.durationSec > 0 }
    }

    data class Limits(val routineMaxSec: Int, val stepSec: Int, val contentSec: Int)

    /** 서버에서 받기 전 기본값 (codes.py와 같은 값) */
    var limits by mutableStateOf(Limits(routineMaxSec = 900, stepSec = 30, contentSec = 300))
        private set

    /** TEMPLATE_ID → TEMPLATE_NAME */
    val presetNames = mutableStateMapOf<String, String>()

    /** TEMPLATE_ID → 프리셋 항목 (새 알람·프리셋을 바꿀 때 기본 시간) */
    private val presetItems = mutableStateMapOf<String, List<Item>>()

    /** 알람 serverId → 항목 시간. 알람 updated_at이 같으면 다시 받지 않음 */
    val routines = mutableStateMapOf<String, AlarmRoutine>()
    private val routineVersions = mutableMapOf<String, String?>()

    private var presets: List<JSONObject>? = null
    private var limitsLoaded = false

    /** 선택 가능한 프리셋 목록 (한 번 받으면 다시 받지 않음) */
    suspend fun loadPresets(): List<JSONObject>? {
        presets?.let { return it }
        val list = RespecApi.presets() ?: return null
        onMain { list.forEach { rememberPreset(it) } }
        presets = list
        return list
    }

    /** 프리셋 이름. 목록에 없으면(비활성 프리셋) 하나만 따로 받음 */
    suspend fun ensurePreset(templateId: String) {
        if (templateId in presetNames) return
        loadPresets()
        if (templateId in presetNames) return
        RespecApi.preset(templateId)?.let { p -> onMain { rememberPreset(p) } }
    }

    suspend fun loadLimits() {
        if (limitsLoaded) return
        val o = RespecApi.limits() ?: return
        onMain {
            limits = Limits(
                routineMaxSec = o.optInt("routine_max_sec", limits.routineMaxSec),
                stepSec = o.optInt("duration_step_sec", limits.stepSec),
                contentSec = o.optInt("content_duration_sec", limits.contentSec)
            )
        }
        limitsLoaded = true
    }

    /** 루틴 알람의 항목 시간. 알람이 바뀌었거나(updated_at) force면 다시 받음. 받지 못하면 있던 값 */
    suspend fun loadRoutine(alarm: Alarm, force: Boolean = false): AlarmRoutine? {
        val sid = alarm.serverId ?: return null
        if (alarm.isContent) return null
        if (!force && sid in routines && routineVersions[sid] == alarm.updatedAt) return routines[sid]
        val o = RespecApi.alarmRoutine(sid) ?: return routines[sid]
        onMain { putRoutine(alarm, o) }
        return routines[sid]
    }

    /** 서버 AlarmRoutineOut을 캐시에 넣음 (저장 응답도 이걸로) */
    fun putRoutine(alarm: Alarm, o: JSONObject) {
        val sid = alarm.serverId ?: return
        routines[sid] = AlarmRoutine(
            templateId = o.optString("template_id"),
            totalSec = o.optInt("total_sec"),
            maxSec = o.optInt("max_sec", limits.routineMaxSec),
            items = items(o, "duration_sec")
        )
        routineVersions[sid] = alarm.updatedAt
    }

    /** 프리셋 기본 항목 (알람을 아직 저장하지 않은 새 알람, 프리셋을 바꾸는 중일 때) */
    fun presetRoutine(templateId: String): AlarmRoutine? {
        val list = presetItems[templateId] ?: return null
        return AlarmRoutine(templateId, list.sumOf { it.durationSec }, limits.routineMaxSec, list)
    }

    private fun rememberPreset(p: JSONObject) {
        val id = p.optString("template_id").takeIf { it.isNotBlank() } ?: return
        presetNames[id] = p.optString("template_name")
        presetItems[id] = items(p, "default_duration_sec")
    }

    /** items 배열 → Item 목록 (순서대로). durationKey: 이 알람의 시간이 들어 있는 칸 */
    private fun items(o: JSONObject, durationKey: String): List<Item> {
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            val def = it.optInt("default_duration_sec")
            Item(
                templateItemId = it.optString("template_item_id"),
                name = it.optString("item_name"),
                order = it.optInt("item_order"),
                defaultSec = def,
                durationSec = it.optInt(durationKey, def),
                responseType = it.optString("response_type")
            )
        }.sortedBy { it.order }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
}
