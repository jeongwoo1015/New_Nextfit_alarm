package com.example.respeclive.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.respeclive.alarm.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Routine(
    val title: String,                  // 체크리스트에 표시되는 이름 (STEP_NAME 또는 ITEM_NAME)
    val done: Boolean = false,
    val stepOrder: Int? = null,         // 서버 단계 번호. null이면 세션 단계가 생기기 전에 보여 주는 알람의 루틴
    val skipped: Boolean = false,
    val durationSec: Int? = null,       // PLANNED_DURATION_SEC 또는 알람의 항목 시간 (콘텐츠 단계는 없을 수 있음)
    val templateItemId: String? = null, // 루틴 항목이면 프리셋 항목, 콘텐츠면 null
    val checkedAt: String? = null       // DONE·SKIPPED가 된 시각 (ISO)
)

/** 오늘 세션에 복사된 값 (SessionOut). 알람 설정이 그 뒤에 바뀌어도 오늘 체크리스트는 이 값 기준 */
data class SessionInfo(
    val alarmId: String?,
    val callType: String,               // ROUTINE / CONTENT
    val templateId: String?,
    val contentType: String?,
    val contentOption: String?,
    val coachingSkipped: Boolean,       // 밤 대화에서 '내일 코칭 안 하기' → 브리핑만, 단계 없음
    val targetWakeTime: String?
) {
    val isContent: Boolean get() = callType == "CONTENT"
}

/**
 * 오늘의 체크리스트. 세션 단계(RS_SESSION_STEP)가 있으면 그 단계로 채우고(대화로 바뀐 체크·시간 반영),
 * 없으면 알람의 루틴(프리셋 항목 + 알람별 항목 시간)을 미리보기로 보여 줌. 단계는 모닝콜에서 코칭을 시작할 때 생김.
 * items: 모닝콜 화면이 보는 지금 세션 하나. boards: 루틴 탭의 알람별 체크리스트.
 */
object RoutineRepository {
    /** POST /morning/sessions 로 받은 오늘 세션 id */
    @Volatile
    var sessionId: String? = null

    var session by mutableStateOf<SessionInfo?>(null)
        private set

    /** 미리보기 기준 알람 */
    var planAlarm by mutableStateOf<Alarm?>(null)
        private set

    val items = mutableStateListOf<Routine>()

    /** 세션 단계가 없을 때 보여 줄 알람의 루틴 */
    private var plan: List<Routine> = emptyList()

    /** 세션 단계가 들어와 있는가 (없으면 알람의 루틴을 보여 주는 중이라 체크할 수 없음) */
    val hasSteps: Boolean get() = items.any { it.stepOrder != null }

    /**
     * SessionViewOut {session, steps, progress} 반영.
     * 다른 세션이면 이전 세션의 체크를 먼저 비우고 알람의 루틴으로 되돌림. 새 세션은 코칭 토큰 전까지 단계가 없어서,
     * 비우지 않으면 이전 완료 표시가 남고 체크 변경이 단계 없는 새 세션으로 가서 404가 남.
     */
    fun applyView(view: JSONObject) {
        view.optJSONObject("session")?.let { s ->
            val id = s.text("session_id") ?: return@let
            if (id != sessionId) showPlan()
            sessionId = id
            session = sessionInfo(s)
        }
        view.optJSONArray("steps")?.let { applySteps(it) }
        updateBoard(view)
    }

    /** StepOut 목록 반영. 단계가 아직 없으면(코칭 토큰 전) 지금 항목 유지 */
    fun applySteps(steps: JSONArray) {
        if (steps.length() == 0) return
        items.clear()
        items.addAll(parseSteps(steps))
    }

    /**
     * 모닝콜 화면의 기준 알람 루틴을 받아 둠. 세션 단계가 없을 때만 화면에 보여 줌. 받지 못하면 이전 값 유지.
     * 기준 알람: 오늘 세션이 있으면 그 세션의 알람, 없으면 다음에 울릴 알람
     */
    suspend fun loadPlan() {
        val wakes = AlarmRepository.alarms.filter { it.type == AlarmType.WAKE && it.serverId != null }
        val alarm = session?.alarmId?.let { id -> wakes.firstOrNull { it.serverId == id } }
            ?: wakes.filter { it.enabled }.minByOrNull { AlarmScheduler.nextTriggerMillis(it) ?: Long.MAX_VALUE }
        val list = if (alarm == null) emptyList() else planFor(alarm) ?: return
        withContext(Dispatchers.Main.immediate) {
            planAlarm = alarm
            plan = list
            if (!hasSteps) showPlan()
        }
    }

    // ---------------- 루틴 탭: 알람별 체크리스트 ----------------

    /** 알람 하나의 체크리스트. 오늘 이 알람의 세션에 단계가 있으면 그 단계, 없으면 알람의 루틴 미리보기 */
    data class AlarmChecklist(
        val alarm: Alarm,
        val sessionId: String? = null,
        val session: SessionInfo? = null,
        val items: List<Routine> = emptyList()
    ) {
        val hasSteps: Boolean get() = items.any { it.stepOrder != null }
    }

    /** 루틴 탭에 보여 줄 알람별 체크리스트 (오늘 울리는 알람만, 알람 시각 순) */
    val boards = mutableStateListOf<AlarmChecklist>()

    /**
     * 오늘 울리는 모닝콜 알람의 체크리스트를 받음. 오늘 울리는 알람 = 켜져 있고 오늘 요일(REPEAT_DAYS)이 켜진 알람,
     * 또는 오늘 이미 세션이 있는 알람(그 뒤에 껐거나 요일을 바꿨어도 오늘 기록은 보여 줌).
     * 오늘 세션은 GET /diary/{오늘}의 세션 목록(알람이 울린 순)에서 알람마다 마지막에 울린 것을 씀. 받지 못하면 미리보기만
     */
    suspend fun loadBoards() {
        val now = Calendar.getInstance()
        val today = "%04d-%02d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val todayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7      // 월=0 ... 일=6 (Alarm.days와 같은 기준)
        val sessions = RespecApi.diaryDay(today)?.optJSONArray("sessions")
        val byAlarm = mutableMapOf<String, JSONObject>()     // 같은 알람이면 뒤(나중에 울린 것)가 남음
        if (sessions != null) for (i in 0 until sessions.length()) {
            val v = sessions.optJSONObject(i) ?: continue
            v.optJSONObject("session")?.text("alarm_id")?.let { byAlarm[it] = v }
        }
        val alarms = AlarmRepository.alarms
            .filter { it.type == AlarmType.WAKE && it.serverId != null }
            .filter { (it.enabled && todayIndex in it.days) || it.serverId in byAlarm }
            .sortedWith(compareBy<Alarm>({ it.hour * 60 + it.minute }, { it.createdAt ?: "" }))
        val list = alarms.map { alarm ->
            val view = byAlarm[alarm.serverId]
            val steps = view?.optJSONArray("steps")?.let { parseSteps(it) }.orEmpty()
            AlarmChecklist(
                alarm = alarm,
                sessionId = view?.optJSONObject("session")?.text("session_id"),
                session = view?.optJSONObject("session")?.let { sessionInfo(it) },
                items = steps.ifEmpty { planFor(alarm).orEmpty() }
            )
        }
        withContext(Dispatchers.Main.immediate) {
            boards.clear()
            boards.addAll(list)
        }
    }

    /** 체크 변경 응답(SessionViewOut)을 그 세션의 체크리스트에 반영 */
    private fun updateBoard(view: JSONObject) {
        val id = view.optJSONObject("session")?.text("session_id") ?: return
        val steps = view.optJSONArray("steps")?.let { parseSteps(it) }?.takeIf { it.isNotEmpty() } ?: return
        val i = boards.indexOfFirst { it.sessionId == id }
        if (i >= 0) boards[i] = boards[i].copy(items = steps)
    }

    /** 루틴 탭에서 체크·해제. 모닝콜 화면이 보고 있는 세션이면 그쪽 체크리스트도 함께 바뀜 */
    suspend fun mark(board: AlarmChecklist, item: Routine): String? {
        val sid = board.sessionId ?: return null
        val order = item.stepOrder ?: return null
        val view = RespecApi.markStep(sid, order, if (item.done) "PENDING" else "DONE")
            ?: return ApiClient.lastError ?: "체크를 저장하지 못했어요"
        withContext(Dispatchers.Main.immediate) {
            if (sid == sessionId) applyView(view) else updateBoard(view)
        }
        return null
    }

    // ---------------- 공통 ----------------

    /**
     * 알람의 루틴 미리보기. 루틴 알람은 알람의 항목 시간(0초 = 뺀 항목 제외), 콘텐츠 알람은 "{종류} · {세부}" 하나.
     * 항목 시간을 받지 못하면 null
     */
    private suspend fun planFor(alarm: Alarm): List<Routine>? {
        RoutineCatalog.loadLimits()
        if (alarm.isContent) return listOf(
            Routine(
                listOfNotNull(alarm.themeLabel ?: "콘텐츠", alarm.contentOptionLabel).joinToString(" · "),
                durationSec = RoutineCatalog.limits.contentSec
            )
        )
        alarm.templateId?.let { RoutineCatalog.ensurePreset(it) }
        val routine = RoutineCatalog.loadRoutine(alarm) ?: return null
        return routine.activeItems.map {
            Routine(it.name, durationSec = it.durationSec, templateItemId = it.templateItemId)
        }
    }

    private fun sessionInfo(s: JSONObject) = SessionInfo(
        alarmId = s.text("alarm_id"),
        callType = s.text("morning_call_type") ?: "ROUTINE",
        templateId = s.text("template_id"),
        contentType = s.text("content_type"),
        contentOption = s.text("content_option"),
        coachingSkipped = s.optBoolean("coaching_skipped"),
        targetWakeTime = s.text("target_wake_time")
    )

    private fun parseSteps(steps: JSONArray): List<Routine> =
        (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            .sortedBy { it.optInt("step_order") }
            .map {
                val status = it.optString("step_status")
                Routine(
                    title = it.optString("step_name"),
                    done = status == "DONE",
                    stepOrder = it.optInt("step_order"),
                    skipped = status == "SKIPPED",
                    durationSec = if (it.isNull("planned_duration_sec")) null else it.optInt("planned_duration_sec"),
                    templateItemId = it.text("template_item_id"),
                    checkedAt = it.text("checked_at")
                )
            }

    private fun showPlan() {
        items.clear()
        items.addAll(plan)
    }

    /** null(JSONObject.NULL)·빈 값이면 null */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
