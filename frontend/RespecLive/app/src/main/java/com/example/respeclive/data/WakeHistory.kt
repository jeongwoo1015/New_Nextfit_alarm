package com.example.respeclive.data

import android.content.Context
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 기상 알람 진동 세기를 정하는 사용자 유형 */
enum class WakeProfile { EASY, NORMAL, HARD }

/** 리포트 한 기간의 아침 기록 수와 기상 성공률(0~1, 기록이 없으면 null) */
data class WakeRate(val sessions: Int, val rate: Double?)

/**
 * 서버 리포트의 기상 성공률로 잘 깨는 사용자 / 보통 / 잘 못 깨는 사용자를 나눔. 기기에는 받아 둔 성공률만 저장하므로
 * 로그아웃·앱 재설치 뒤에도 서버에서 다시 받으면 같은 값이 됨.
 * 이번 주 아침이 MIN_SESSIONS번 이상이면 이번 주 성공률, 아니면(주 초반) 전체 기간 성공률, 둘 다 적으면 보통.
 * 서버가 사용자별 기상 시작 단계를 정해 주고(AI CONTRACTS B23) 진동도 그 단계를 따라가므로 이 구분은 보조
 */
object WakeHistory {
    private const val PREFS = "wake_history"
    private const val KEY_SUCCESS_RATE = "success_rate"
    private val LEGACY_KEYS = listOf("entries", "week_success_rate")   // 예전 기기 기상 기록·이번 주 성공률

    /** 이보다 아침 기록이 적은 기간의 성공률은 쓰지 않음 (월요일 하나만 있으면 0% 또는 100%라 흔들림) */
    const val MIN_SESSIONS = 3
    /** 성공률이 이 값 미만이면 잘 못 깨는 사용자 */
    const val HARD_BELOW = 0.5
    /** 성공률이 이 값 이상이면 잘 깨는 사용자 */
    const val EASY_FROM = 0.8

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 알람이 울릴 때 부름: 받아 둔 성공률로 판단 (네트워크를 기다리지 않음) */
    fun profile(context: Context): WakeProfile {
        val rate = prefs(context).getFloat(KEY_SUCCESS_RATE, -1f).takeIf { it >= 0f }?.toDouble()
        return classify(rate)
    }

    /** 홈을 열 때 서버 리포트에서 기상 성공률을 받아 둠 */
    fun refreshSuccessRate(context: Context) {
        val app = context.applicationContext
        ApiClient.scope.launch {
            val report = RespecApi.report("week") ?: return@launch
            val p = prefs(app).edit()
            LEGACY_KEYS.forEach { p.remove(it) }
            val rate = pickRate(report.optJSONObject("totals")?.toWakeRate(), report.optJSONObject("overall")?.toWakeRate())
            if (rate == null) p.remove(KEY_SUCCESS_RATE) else p.putFloat(KEY_SUCCESS_RATE, rate.toFloat())
            p.apply()
        }
    }

    /** 로그아웃: 다음 계정은 홈을 열 때 자기 성공률을 다시 받음 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 이번 주(totals) → 전체 기간(overall) 차례로, 아침 기록이 MIN_SESSIONS번 이상인 쪽의 성공률 */
    fun pickRate(week: WakeRate?, overall: WakeRate?): Double? =
        listOfNotNull(week, overall).firstOrNull { it.sessions >= MIN_SESSIONS && it.rate != null }?.rate

    private fun JSONObject.toWakeRate() = WakeRate(
        sessions = optInt("n_sessions"),
        rate = if (isNull("wake_success_rate")) null else optDouble("wake_success_rate").takeIf { !it.isNaN() }
    )

    /** 성공률 50% 미만 → 잘 못 깨는 사용자, 80% 이상 → 잘 깨는 사용자, 그 사이·값 없음 → 보통 */
    fun classify(successRate: Double?): WakeProfile = when {
        successRate == null -> WakeProfile.NORMAL
        successRate < HARD_BELOW -> WakeProfile.HARD
        successRate >= EASY_FROM -> WakeProfile.EASY
        else -> WakeProfile.NORMAL
    }
}
