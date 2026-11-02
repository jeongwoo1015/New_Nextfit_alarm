package com.example.respeclive.data

enum class AlarmType(val route: String) {
    WAKE("morningCall"),     // 모닝콜
    BEDTIME("nightCall")     // 굿나잇콜 수신 화면
}

/** code: 서버 alarm_mode 값 */
enum class AlarmMode(val label: String, val code: String) {
    CONTINUOUS("계속", "ON_TIME"),
    BEFORE_10("10분 전", "BEFORE_10"),
    BEFORE_30("30분 전", "BEFORE_30");

    companion object {
        fun fromCode(code: String?): AlarmMode = entries.firstOrNull { it.code == code } ?: CONTINUOUS
    }
}

/** 테마 = 콘텐츠 종류 (서버 content_type → 화면 이름). 고르면 콘텐츠 알람, 안 고르면 루틴 알람 */
val THEMES = listOf("EXERCISE" to "운동", "LANGUAGE" to "외국어", "MEDITATION" to "명상", "QUIZ" to "퀴즈")

fun themeLabel(code: String?): String? = THEMES.firstOrNull { it.first == code }?.second

data class Alarm(
    val id: Int,
    val label: String,
    val hour: Int,               // 0~23
    val minute: Int,
    val days: Set<Int>,          // 0=월 ... 6=일
    val enabled: Boolean = true,
    val type: AlarmType = AlarmType.WAKE,
    val mode: AlarmMode = AlarmMode.CONTINUOUS,
    // 서버 알람(RS_ALARM)과의 연결. 모닝콜 알람만 서버에 있고 취침 알람은 기기에만 있음
    val serverId: String? = null,
    val templateId: String? = null,      // 루틴 알람의 프리셋
    val contentType: String? = null,     // 테마. 있으면 콘텐츠 알람(MEDITATION/EXERCISE/LANGUAGE/QUIZ), 없으면 루틴 알람
    val contentOption: String? = null
) {
    val isContent: Boolean get() = contentType != null

    val themeLabel: String? get() = themeLabel(contentType)

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