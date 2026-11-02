package com.example.respeclive.data

enum class AlarmType(val route: String) {
    WAKE("morningCall"),     // 모닝콜
    BEDTIME("nightCall")     // 굿나잇콜 수신 화면
}

enum class AlarmMode(val label: String) {
    CONTINUOUS("계속"),
    BEFORE_10("10분 전"),
    BEFORE_30("30분 전")
}

data class Alarm(
    val id: Int,
    val label: String,
    val hour: Int,               // 0~23
    val minute: Int,
    val days: Set<Int>,          // 0=월 ... 6=일
    val enabled: Boolean = true,
    val type: AlarmType = AlarmType.WAKE,
    val mode: AlarmMode = AlarmMode.CONTINUOUS,
    val theme: String? = null
) {
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