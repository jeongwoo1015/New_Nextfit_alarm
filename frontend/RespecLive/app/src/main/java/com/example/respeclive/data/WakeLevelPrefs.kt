package com.example.respeclive.data

import android.content.Context
import com.example.respeclive.audio.AlarmSoundPlayer
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기상 알람 시작 단계 (AI CONTRACTS B23, 서버 GET /wake-volume/next).
 * 서버가 다음 아침의 시작 단계(start_level)와 재기상 단계(rewake_level = 시작 + 1)를 정해 줌. 사용자가 넥스티에게 말로 정한 값
 * (다음 한 번·상한)도 서버가 이미 반영함. 알람이 울리는 순간 인터넷을 기다리지 않도록 앱을 열 때·밤 대화 뒤에 받아 기기에 저장.
 * 그날 값을 받지 못했으면 기기에 저장한 말로 정한 값으로 직접 계산 (다음 한 번 > 상한 > 1단계)
 */
object WakeLevelPrefs {
    private const val PREFS = "wake_level"
    private const val KEY_NEXT = "next"
    private const val KEY_MAX = "max"
    private const val KEY_LAST_START = "last_start"
    private const val KEY_LAST_REWAKE = "last_rewake"
    private const val KEY_SERVER_DATE = "server_date"
    private const val KEY_SERVER_START = "server_start"
    private const val KEY_SERVER_REWAKE = "server_rewake"

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 서버 설정(SettingOut)을 받으면 부름: 말로 정한 두 값(다음 한 번·상한)을 저장 (서버 값을 못 받았을 때 직접 계산용) */
    fun update(setting: JSONObject?) {
        setting ?: return
        if (!setting.has("wake_next_level") && !setting.has("wake_max_start_level")) return   // 예전 서버
        val e = prefs()?.edit() ?: return
        level(setting, "wake_next_level")?.let { e.putInt(KEY_NEXT, it) } ?: e.remove(KEY_NEXT)
        level(setting, "wake_max_start_level")?.let { e.putInt(KEY_MAX, it) } ?: e.remove(KEY_MAX)
        e.apply()
    }

    /** 서버에서 다음 아침 시작 단계를 받아 저장 (앱을 열 때·밤 대화 저장 뒤). 다음 알람이 없거나 실패하면 그대로 */
    fun refreshFromServer() {
        ApiClient.scope.launch { RespecApi.wakeVolumeNext()?.let { saveNext(it) } }
    }

    /** WakeNextOut {session_date, start_level, rewake_level, ...} 저장 */
    fun saveNext(next: JSONObject) {
        val date = next.optString("session_date").takeIf { it.isNotBlank() } ?: return
        val start = level(next, "start_level") ?: return
        val rewake = next.optInt("rewake_level", start + 1).coerceIn(2, AlarmSoundPlayer.SCALE_LEVELS)
        prefs()?.edit()?.putString(KEY_SERVER_DATE, date)?.putInt(KEY_SERVER_START, start)?.putInt(KEY_SERVER_REWAKE, rewake)?.apply()
    }

    private fun level(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else o.optInt(key, 0).takeIf { it in 1..AlarmSoundPlayer.WAKE_MAX_LEVEL }

    /** 지금 울리는 기상 알람의 시작 단계: 오늘 날짜로 받은 서버 값, 없으면 직접 계산. '다음 한 번' 값은 썼으므로 지움 */
    fun takeStartLevel(): Int {
        val p = prefs()
        val serverStart = p?.takeIf { it.getString(KEY_SERVER_DATE, null) == today() }?.getInt(KEY_SERVER_START, 0)?.takeIf { it > 0 }
        val level: Int
        val rewake: Int
        if (serverStart != null) {
            level = serverStart
            rewake = p.getInt(KEY_SERVER_REWAKE, serverStart + 1)
        } else {
            level = resolve(p?.getInt(KEY_NEXT, 0)?.takeIf { it > 0 }, p?.getInt(KEY_MAX, 0)?.takeIf { it > 0 }, recommended = null)
            rewake = AlarmSoundPlayer.rewakeLevel(level)
        }
        p?.edit()?.remove(KEY_NEXT)?.putInt(KEY_LAST_START, level)?.putInt(KEY_LAST_REWAKE, rewake)?.apply()
        return level
    }

    /** 오늘 기상 알람이 시작한 단계 */
    fun lastStartLevel(): Int = prefs()?.getInt(KEY_LAST_START, 0)?.takeIf { it > 0 } ?: AlarmSoundPlayer.WAKE_START_LEVEL

    /** 오늘 재기상 음악 단계 (서버 rewake_level, 없으면 시작 + 1) */
    fun rewakeLevel(): Int = prefs()?.getInt(KEY_LAST_REWAKE, 0)?.takeIf { it > 0 } ?: AlarmSoundPlayer.rewakeLevel(lastStartLevel())

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
