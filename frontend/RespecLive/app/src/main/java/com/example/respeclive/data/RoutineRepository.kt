package com.example.respeclive.data

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class Routine(
    val title: String,              // 체크리스트에 표시되는 이름 (STEP_NAME)
    val done: Boolean = false,
    val stepOrder: Int? = null,     // 서버 단계 번호. null이면 서버 세션이 없을 때 보여 주는 기본 항목
    val skipped: Boolean = false
)

/**
 * 루틴 탭과 모닝콜이 함께 쓰는 오늘의 체크리스트.
 * 서버 세션(RS_SESSION_STEP)이 있으면 그 단계로 채우고, 없으면 기본 항목을 보여 줌.
 */
object RoutineRepository {
    /** POST /morning/sessions 로 받은 오늘 세션 id */
    @Volatile
    var sessionId: String? = null

    private val defaults = listOf(
        Routine("물 한 컵 마시기"),
        Routine("가벼운 스트레칭"),
        Routine("오늘 할 일 한 가지 정하기"),
    )

    val items = mutableStateListOf<Routine>().apply { addAll(defaults) }

    /** SessionViewOut {session, steps, progress} 반영 */
    fun applyView(view: JSONObject) {
        view.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        view.optJSONArray("steps")?.let { applySteps(it) }
    }

    /** StepOut 목록 반영. 단계가 아직 없으면(코칭 토큰 전) 기본 항목 유지 */
    fun applySteps(steps: JSONArray) {
        if (steps.length() == 0) return
        val list = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            .sortedBy { it.optInt("step_order") }
            .map {
                val status = it.optString("step_status")
                Routine(
                    title = it.optString("step_name"),
                    done = status == "DONE",
                    stepOrder = it.optInt("step_order"),
                    skipped = status == "SKIPPED"
                )
            }
        items.clear()
        items.addAll(list)
    }

    /** 서버 세션이 없을 때만 쓰는 기기 내 체크 */
    fun setDone(index: Int, done: Boolean) {
        if (index in items.indices) items[index] = items[index].copy(done = done)
    }

    fun resetAll() {
        sessionId = null
        items.clear()
        items.addAll(defaults)
    }
}
