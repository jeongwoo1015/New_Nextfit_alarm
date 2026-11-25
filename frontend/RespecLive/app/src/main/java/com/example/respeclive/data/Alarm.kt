package com.example.respeclive.data

enum class AlarmType(val route: String) {
    WAKE("morningCall"),     // 모닝콜
    BEDTIME("nightCall")     // 굿나잇콜 수신 화면
}

/** 테마 = 콘텐츠 종류 (서버 content_type → 화면 이름). 고르면 콘텐츠 알람, 안 고르면 루틴 알람. 명상은 예전에 만든 알람 표시용.
 *  QUIZ는 11/17 회의로 화면 이름을 "게임"으로 (서버 코드는 그대로 QUIZ) */
val THEMES = listOf("EXERCISE" to "스트레칭", "LANGUAGE" to "영어", "MEDITATION" to "명상", "QUIZ" to "게임")

fun themeLabel(code: String?): String? = THEMES.firstOrNull { it.first == code }?.second

/**
 * 알람을 만들 때 고를 수 있는 아침 콘텐츠: 스트레칭·영어 (11/12 회의, AI CONTRACTS B17)·게임 (11/17 회의).
 * 게임은 회의에서 초성게임만 두기로 했지만 AI·서버에 아직 없어서, 그동안 끝말잇기로 엶.
 * 명상·마사지·일본어·중국어는 새 알람에서 뺐음. 이미 만든 알람은 그대로 동작함
 */
val MORNING_CONTENTS = listOf("EXERCISE" to "스트레칭", "LANGUAGE" to "영어", "QUIZ" to "게임")

/**
 * 고를 수 있는 콘텐츠의 세부는 하나로 고정 (세부 선택 없음). 스트레칭 부위는 통화 중에 AI가 물어 정함
 */
fun fixedContentOption(type: String?): String? = when (type) {
    "EXERCISE" -> "STRETCH"
    "LANGUAGE" -> "ENGLISH"
    "QUIZ" -> "WORDCHAIN"      // 초성게임이 생기면 바꿈
    else -> null
}

/**
 * 콘텐츠 세부 (서버 CONTENT_OPTIONS, backend/domain/codes.py 11/10 기준). 예전에 만든 알람의 표시용.
 * 스트레칭·영어는 테마 이름과 같아서 세부 이름을 따로 붙이지 않음 ("스트레칭 · 스트레칭" 방지)
 */
val CONTENT_OPTIONS = mapOf(
    "MEDITATION" to listOf("BREATH" to "호흡", "BODY_SCAN" to "바디스캔"),
    "EXERCISE" to listOf("MASSAGE" to "마사지"),
    "LANGUAGE" to listOf("JAPANESE" to "일본어", "CHINESE" to "중국어"),
    "QUIZ" to listOf(
        "PERSON" to "인물", "GENERAL" to "상식", "WORDCHAIN" to "끝말잇기",
        "SCIENCE" to "과학", "HISTORY" to "역사", "NONSENSE" to "넌센스"
    ),
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