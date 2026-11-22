package com.example.respeclive.data

import android.content.Context
import kotlinx.coroutines.launch

/** 기상 알람 진동 세기를 정하는 사용자 유형 */
enum class WakeProfile { EASY, NORMAL, HARD }

/** 아침 한 번의 기록: 전화를 받은 볼륨 단계와 그 뒤 다시 깨우기가 울렸는지 */
data class WakeEntry(val receivedLevel: Int, val rewake: Boolean)

/**
 * 최근 아침 기록(기기에만 저장)으로 잘 깨는 사용자 / 보통 / 잘 못 깨는 사용자를 나눔.
 * 기록이 적으면(처음 쓰는 사람, 앱을 다시 설치) 서버 리포트의 주간 기상 성공률로 대신 판단.
 * 서버가 사용자별 기상 시작 단계를 정해 주면(AI CONTRACTS B23) 진동도 그 단계를 따라가므로 이 구분은 보조가 됨
 */
object WakeHistory {
    private const val PREFS = "wake_history"
    private const val KEY_ENTRIES = "entries"            // "받은단계:다시깨우기(0/1)"를 쉼표로, 오래된 것부터
    private const val KEY_SUCCESS_RATE = "week_success_rate"

    /** 판단에 쓰는 최근 아침 수 */
    const val RECENT = 5
    /** 이보다 기록이 적으면 서버 기상 성공률로 판단 */
    const val MIN_ENTRIES = 3
    const val EASY_MAX_AVG = 2.0
    const val HARD_MIN_AVG = 4.0
    const val HARD_MIN_REWAKES = 2
    const val HARD_SUCCESS_RATE = 0.5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(context: Context): List<WakeEntry> =
        prefs(context).getString(KEY_ENTRIES, "").orEmpty().split(',').mapNotNull { parse(it) }

    private fun parse(s: String): WakeEntry? {
        val parts = s.split(':')
        val level = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return WakeEntry(level, parts.getOrNull(1) == "1")
    }

    private fun save(context: Context, list: List<WakeEntry>) {
        val text = list.takeLast(RECENT).joinToString(",") { "${it.receivedLevel}:${if (it.rewake) 1 else 0}" }
        prefs(context).edit().putString(KEY_ENTRIES, text).apply()
    }

    /** 모닝콜을 받음 (받은 순간의 볼륨 단계) */
    fun recordAnswer(context: Context, receivedLevel: Int) {
        save(context, entries(context) + WakeEntry(receivedLevel, rewake = false))
    }

    /** 받은 뒤 대화 중에 다시 깨우기가 울림: 가장 최근 아침에 표시 */
    fun markRewake(context: Context) {
        val list = entries(context)
        val last = list.lastOrNull() ?: return
        if (last.rewake) return
        save(context, list.dropLast(1) + last.copy(rewake = true))
    }

    fun profile(context: Context): WakeProfile {
        val rate = prefs(context).getFloat(KEY_SUCCESS_RATE, -1f).takeIf { it >= 0f }?.toDouble()
        return classify(entries(context), rate)
    }

    /** 홈을 열 때 서버 리포트에서 이번 주 기상 성공률을 받아 둠 (알람이 울릴 때는 네트워크를 기다리지 않도록) */
    fun refreshSuccessRate(context: Context) {
        val app = context.applicationContext
        ApiClient.scope.launch {
            val report = RespecApi.report("week") ?: return@launch
            val rate = listOf("totals", "overall")
                .mapNotNull { report.optJSONObject(it) }
                .firstOrNull { it.optInt("n_sessions") > 0 && !it.isNull("wake_success_rate") }
                ?.optDouble("wake_success_rate")
            val p = prefs(app).edit()
            if (rate == null || rate.isNaN()) p.remove(KEY_SUCCESS_RATE) else p.putFloat(KEY_SUCCESS_RATE, rate.toFloat())
            p.apply()
        }
    }

    /**
     * 최근 RECENT번: 받은 단계 평균 2 이하이고 다시 깨우기 없음 → 잘 깨는 사용자,
     * 받은 단계 평균 4 이상이거나 다시 깨우기 2번 이상 → 잘 못 깨는 사용자, 그 사이는 보통.
     * 기록이 MIN_ENTRIES번보다 적으면 기상 성공률 50% 미만일 때만 잘 못 깨는 사용자, 아니면 보통
     */
    fun classify(entries: List<WakeEntry>, weekSuccessRate: Double?): WakeProfile {
        val recent = entries.takeLast(RECENT)
        if (recent.size < MIN_ENTRIES) {
            return if (weekSuccessRate != null && weekSuccessRate < HARD_SUCCESS_RATE) WakeProfile.HARD else WakeProfile.NORMAL
        }
        val avg = recent.map { it.receivedLevel }.average()
        val rewakes = recent.count { it.rewake }
        return when {
            avg >= HARD_MIN_AVG || rewakes >= HARD_MIN_REWAKES -> WakeProfile.HARD
            avg <= EASY_MAX_AVG && rewakes == 0 -> WakeProfile.EASY
            else -> WakeProfile.NORMAL
        }
    }
}
