package com.example.respeclive.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 오늘 미룬 횟수 (버튼·말 합산, 서버 하루 3번 한도와 같음). 수신 화면 버튼에 남은 횟수를 보여 주려고 기기에 저장.
 * 서버 응답(snoozes_today)이 오면 그 값으로 맞추고, 서버에 닿지 못했을 때만 기기에서 1 올림. 날짜가 바뀌면 0
 */
object SnoozeCount {
    const val MAX_PER_DAY = 3
    private const val PREFS = "snooze_count"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DATE, null) == today()) p.getInt(KEY_COUNT, 0) else 0
    }

    /** 서버가 알려 준 오늘 횟수로 맞춤 → 맞춘 값 */
    fun set(context: Context, count: Int): Int {
        val n = count.coerceIn(0, MAX_PER_DAY)
        prefs(context).edit().putString(KEY_DATE, today()).putInt(KEY_COUNT, n).apply()
        return n
    }

    /** 세션의 미루기 횟수처럼 '적어도 이만큼'인 값 → 둘 중 큰 값 */
    fun atLeast(context: Context, count: Int): Int = set(context, maxOf(get(context), count))

    /** 서버에 닿지 못하고 기기에서만 미뤘을 때 */
    fun increment(context: Context): Int = set(context, get(context) + 1)

    fun left(count: Int): Int = (MAX_PER_DAY - count).coerceAtLeast(0)
}
