package com.example.respeclive.data

enum class AlarmType(val route: String) {
    WAKE("morningCall"),     // 모닝콜
    BEDTIME("nightCall")     // 굿나잇콜 수신 화면
}

/** 테마 = 콘텐츠 종류 (서버 content_type → 화면 이름). 고르면 콘텐츠 알람, 안 고르면 루틴 알람 */
val THEMES = listOf("EXERCISE" to "운동", "LANGUAGE" to "외국어", "MEDITATION" to "명상", "QUIZ" to "퀴즈")

fun themeLabel(code: String?): String? = THEMES.firstOrNull { it.first == code }?.second

/** 콘텐츠 세부 (서버 CONTENT_OPTIONS, backend/domain/codes.py. 팀 확정 전 예시 값) */
val CONTENT_OPTIONS = mapOf(
    "MEDITATION" to listOf("BREATH" to "호흡", "BODY_SCAN" to "바디스캔"),
    "EXERCISE" to listOf("STRETCH" to "스트레칭", "MASSAGE" to "마사지"),
    "LANGUAGE" to listOf("ENGLISH" to "영어", "JAPANESE" to "일본어", "CHINESE" to "중국어"),
    "QUIZ" to listOf("GENERAL" to "상식", "SCIENCE" to "과학", "HISTORY" to "역사", "NONSENSE" to "넌센스"),
)

fun contentOptionLabel(type: String?, option: String?): String? =
    CONTENT_OPTIONS[type]?.firstOrNull { it.first == option }?.second

/** "2분", "30초", "1분 30초" */
fun durationText(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m == 0 -> "${s}초"
        s == 0 -> "${m}분"
        else -> "${m}분 ${s}초"
    }
}

data class Alarm(
    val id: Int,
    val label: String,
    val hour: Int,               // 0~23
    val minute: Int,
    val days: Set<Int>,          // 0=월 ... 6=일
    val enabled: Boolean = true,
    val type: AlarmType = AlarmType.WAKE,
    // 서버 알람(RS_ALARM)과의 연결. 모닝콜 알람만 서버에 있고 취침 알람은 기기에만 있음
    val serverId: String? = null,
    val templateId: String? = null,      // 루틴 알람의 프리셋
    val contentType: String? = null,     // 테마. 있으면 콘텐츠 알람(MEDITATION/EXERCISE/LANGUAGE/QUIZ), 없으면 루틴 알람
    val contentOption: String? = null,
    val createdAt: String? = null,       // 서버 created_at (목록에서 같은 시각일 때 순서)
    val updatedAt: String? = null        // 서버 updated_at (바뀌었을 때만 프리셋 이름·항목 시간을 다시 받음)
) {
    val isContent: Boolean get() = contentType != null

    val themeLabel: String? get() = themeLabel(contentType)

    val contentOptionLabel: String? get() = contentOptionLabel(contentType, contentOption)

    /** 알람 카드용: 06:00 */
    fun timeText(): String = "%02d:%02d".format(hour, minute)

    /** 수신 화면용: 오전 6:00 */
    fun timeLabel(): String = timeLabelOf(hour, minute)

    companion object {
        fun timeLabelOf(hour: Int, minute: Int): String {
            val h12 = (hour % 12).let { if (it == 0) 12 else it }
            return "${if (hour < 12) "오전" else "오후"} $h12:%02d".format(minute)
        }
    }
}