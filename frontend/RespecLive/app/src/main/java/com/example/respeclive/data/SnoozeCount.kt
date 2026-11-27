package com.example.respeclive.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 알람마다 오늘 미룬 횟수 (버튼·말 합산, 알람 하나에 3번까지, 11/27). 수신 화면 버튼에 남은 횟수를 보여 주려고 기기에 저장.
 * A 알람을 3번 미뤄도 B 알람은 따로 3번 미룰 수 있음.
 * 서버 응답의 그 세션 미루기 횟수(snooze_count, 같은 알람이 다시 울리면 같은 세션)로 맞추고,
 * 서버에 닿지 못했을 때만 기기에서 1 올림. 날짜가 바뀌면 0
 */
object SnoozeCount {
    const val MAX_PER_ALARM = 3
    private const val PREFS = "snooze_count"
    private const val KEY_DATE = "date"

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun key(alarmId: Int) = "count_$alarmId"

    /** 날짜가 바뀌었으면 모든 알람의 횟수를 비우고 오늘 날짜로 */
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
        if (p.getString(KEY_DATE, null) != today()) p.edit().clear().putString(KEY_DATE, today()).apply()
    }

    fun get(context: Context, alarmId: Int): Int = prefs(context).getInt(key(alarmId), 0)

    /** 서버가 알려 준 이 알람(세션)의 횟수로 맞춤 → 맞춘 값 */
    fun set(context: Context, alarmId: Int, count: Int): Int {
        val n = count.coerceIn(0, MAX_PER_ALARM)
        prefs(context).edit().putInt(key(alarmId), n).apply()
        return n
    }

    /** 세션의 미루기 횟수처럼 '적어도 이만큼'인 값 → 둘 중 큰 값 */
    fun atLeast(context: Context, alarmId: Int, count: Int): Int = set(context, alarmId, maxOf(get(context, alarmId), count))

    /** 서버에 닿지 못하고 기기에서만 미뤘을 때 */
    fun increment(context: Context, alarmId: Int): Int = set(context, alarmId, get(context, alarmId) + 1)

    fun left(count: Int): Int = (MAX_PER_ALARM - count).coerceAtLeast(0)
}
