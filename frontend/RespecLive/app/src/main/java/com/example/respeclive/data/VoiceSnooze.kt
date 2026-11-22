package com.example.respeclive.data

import java.util.Calendar

/**
 * 말로 미루기(AI reschedule_alarm, CONTRACTS B21) 인자 확인. AI도 검사하지만 앱이 한 번 더 함:
 * minutes_later(지금부터 1~60분) 또는 at_time(오늘 "HH:MM", 지금보다 뒤이고 60분 안) 중 정확히 하나
 */
object VoiceSnooze {
    const val MAX_MINUTES = 60
    private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /** 확인을 통과한 미루기 요청. 서버에는 받은 그대로 보내고, 서버에 닿지 못하면 triggerMillis로 기기에서만 예약 */
    data class Request(val minutesLater: Int?, val atTime: String?, val triggerMillis: Long)

    fun check(minutesLater: Int?, atTime: String?, nowMillis: Long = System.currentTimeMillis()): Request? {
        if ((minutesLater == null) == (atTime.isNullOrBlank())) return null       // 둘 다 있거나 둘 다 없음
        if (minutesLater != null) {
            if (minutesLater !in 1..MAX_MINUTES) return null
            return Request(minutesLater, null, nowMillis + minutesLater * 60_000L)
        }
        val m = HHMM.find(atTime!!.trim()) ?: return null
        val at = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            set(Calendar.MINUTE, m.groupValues[2].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (at <= nowMillis || at - nowMillis > MAX_MINUTES * 60_000L) return null
        val hhmm = "%02d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        return Request(null, hhmm, at)
    }

    /** 알림·수신 화면에 쓸 시각 "오전 9:10" */
    fun label(triggerMillis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = triggerMillis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$ampm $h12:%02d".format(c.get(Calendar.MINUTE))
    }
}
