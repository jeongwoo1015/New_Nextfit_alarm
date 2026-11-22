package com.example.respeclive.data

import android.content.Context
import com.example.respeclive.audio.AlarmSoundPlayer
import org.json.JSONObject

/**
 * 기상 알람 시작 단계 (AI CONTRACTS B23). 사용자가 넥스티에게 말로 정한 값(B22 update_setting → 서버 설정)이 추천보다 우선:
 * - wake_next_level: 다음 알람 한 번만 이 단계로 시작 (쓰고 나면 비움. 서버도 울리고 나면 비움)
 * - wake_max_start_level: 앞으로 이 단계보다 크게 시작하지 않음
 * 없으면 서버 추천 단계(아직 앱에 주는 API가 없어서 1단계).
 * 알람이 울리는 순간 인터넷을 기다리지 않도록 서버 설정을 받을 때마다 기기에 저장해 둠
 */
object WakeLevelPrefs {
    private const val PREFS = "wake_level"
    private const val KEY_NEXT = "next"
    private const val KEY_MAX = "max"
    private const val KEY_LAST_START = "last_start"

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 서버 설정(SettingOut)을 받으면 부름: 두 칸을 그대로 저장 (null이면 지움) */
    fun update(setting: JSONObject?) {
        setting ?: return
        if (!setting.has("wake_next_level") && !setting.has("wake_max_start_level")) return   // 예전 서버
        val e = prefs()?.edit() ?: return
        level(setting, "wake_next_level")?.let { e.putInt(KEY_NEXT, it) } ?: e.remove(KEY_NEXT)
        level(setting, "wake_max_start_level")?.let { e.putInt(KEY_MAX, it) } ?: e.remove(KEY_MAX)
        e.apply()
    }

    private fun level(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else o.optInt(key, 0).takeIf { it in 1..AlarmSoundPlayer.WAKE_MAX_LEVEL }

    /** 지금 울리는 기상 알람의 시작 단계를 정하고, '다음 한 번' 값은 썼으므로 지움 */
    fun takeStartLevel(): Int {
        val p = prefs()
        val next = p?.getInt(KEY_NEXT, 0)?.takeIf { it > 0 }
        val max = p?.getInt(KEY_MAX, 0)?.takeIf { it > 0 }
        val level = resolve(next, max, recommended = null)
        p?.edit()?.remove(KEY_NEXT)?.putInt(KEY_LAST_START, level)?.apply()
        return level
    }

    /** 오늘 기상 알람이 시작한 단계 (재기상 = 이 단계 + 1) */
    fun lastStartLevel(): Int = prefs()?.getInt(KEY_LAST_START, 0)?.takeIf { it > 0 } ?: AlarmSoundPlayer.WAKE_START_LEVEL

    /**
     * AI wake_volume.decide_start_level과 같은 우선순위: 다음 한 번(그대로 씀) > 상한(추천이 넘으면 상한으로) > 추천 > 기본 1단계
     */
    fun resolve(next: Int?, max: Int?, recommended: Int?): Int {
        val top = AlarmSoundPlayer.WAKE_MAX_LEVEL
        if (next != null) return next.coerceIn(1, top)
        val base = recommended ?: AlarmSoundPlayer.WAKE_START_LEVEL
        return (if (max != null && base > max) max else base).coerceIn(1, top)
    }
}
