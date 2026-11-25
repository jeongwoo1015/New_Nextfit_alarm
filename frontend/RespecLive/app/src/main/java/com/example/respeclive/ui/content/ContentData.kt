package com.example.respeclive.ui.content

import org.json.JSONArray
import org.json.JSONObject

/** 초성 게임의 세부 값 (AI·서버에 생기면 이 값으로 맞춤) */
const val CHOSUNG_OPTION = "CHOSUNG"

/** 화면에 보여 줄 단계 하나: 일기 API의 단계(이름·결과) + 콘텐츠 API의 내용(설명·문장·단어, 없으면 비어 있음) */
data class ContentStep(
    val order: Int,
    val name: String,
    val status: String,
    val script: String?,
    val words: List<String>,
    val score: Int?,
)

/** 오늘 받은 모닝콜 한 번 */
data class ContentSession(
    val sessionId: String,
    val callType: String,          // ROUTINE / CONTENT
    val contentType: String?,
    val contentOption: String?,
    val stretchArea: String?,
    val steps: List<ContentStep>,
    val wakeTime: String? = null,  // 알람 시각 "07:00"
    val alarmServerId: String? = null,
)

// ---------------- 데이터 ----------------

/** 일기 날짜 상세(DiaryDayOut)의 세션·단계 + 콘텐츠 API 단계 내용(같은 세션·순서끼리)을 합침 */
fun parseSessions(day: JSONObject, extra: JSONArray?): List<ContentSession> {
    val details = HashMap<Pair<String, Int>, JSONObject>()
    for (i in 0 until (extra?.length() ?: 0)) {
        val o = extra!!.optJSONObject(i) ?: continue
        details[o.optString("session_id") to o.optInt("step_order")] = o
    }
    val arr = day.optJSONArray("sessions") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val item = arr.optJSONObject(i) ?: return@mapNotNull null
        val s = item.optJSONObject("session") ?: return@mapNotNull null
        val sid = s.optString("session_id")
        val stepsJson = item.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).mapNotNull { k ->
            val st = stepsJson.optJSONObject(k) ?: return@mapNotNull null
            val order = st.optInt("step_order")
            val d = details[sid to order]
            ContentStep(
                order = order,
                name = st.optString("step_name"),
                status = st.optString("step_status", "PENDING"),
                script = d?.str("script"),
                words = d?.optJSONArray("words")?.let { w -> (0 until w.length()).mapNotNull { w.optString(it).takeIf { x -> x.isNotBlank() } } }
                    ?: emptyList(),
                score = d?.takeIf { !it.isNull("score") && it.has("score") }?.optInt("score"),
            )
        }.sortedBy { it.order }
        ContentSession(
            sessionId = sid,
            callType = s.optString("morning_call_type"),
            contentType = s.str("content_type"),
            contentOption = s.str("content_option"),
            stretchArea = s.str("stretch_area"),
            steps = steps,
            wakeTime = s.str("base_wake_time"),
            alarmServerId = s.str("alarm_id"),
        )
    }
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** 영어 표현 한 개 (AI 단계 스크립트 "영어 / 뜻 / 발음") */
data class Expression(val english: String, val meaning: String?, val pronunciation: String?)

/** 영어 한마디 대답 (스크립트 "질문: … / 힌트: … / 예시: …" 여러 줄) */
data class Question(val question: String, val example: String?)

fun parseExpression(script: String?): Expression? {
    val text = script?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("질문:")) return null
    val parts = text.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return Expression(parts[0], parts.getOrNull(1), parts.getOrNull(2))
}

fun parseQuestion(script: String?): Question? {
    val q = scriptField(script, "질문") ?: return null
    return Question(q, scriptField(script, "예시"))
}

/** 여러 줄 스크립트에서 "이름: 값" 줄의 값 */
fun scriptField(script: String?, key: String): String? =
    script?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

/** 스트레칭 부위 이름 (서버 STRETCH_AREAS + 예전 부위) */
fun stretchAreaName(code: String?): String? = when (code) {
    "FULL" -> "전신"
    "NECK" -> "목"
    "SHOULDER" -> "어깨"
    "CHEST_BACK" -> "가슴·등"
    "WRIST_HAND" -> "손목·손"
    "ELBOW_ARM" -> "팔꿈치·팔"
    "LOW_BACK" -> "허리"
    "PELVIS" -> "골반"
    "GLUTE" -> "엉덩이"
    "THIGH" -> "허벅지"
    "CALF" -> "종아리"
    "ANKLE_FOOT" -> "발목·발"
    "NECK_SHOULDER" -> "목·어깨"
    "ARM_WRIST" -> "팔·손목"
    "BACK_HIP" -> "등·허리"
    "LEG" -> "다리"
    else -> null
}
