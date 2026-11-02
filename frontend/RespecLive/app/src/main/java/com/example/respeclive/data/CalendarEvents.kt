package com.example.respeclive.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 폰 캘린더에서 오늘·내일 일정을 읽어 서버 형식(CalendarEventIn)으로 만듦.
 * {title, start "HH:MM" 또는 "", end "HH:MM" 또는 "", all_day}. 서버는 저장하지 않고 지시문에만 씀.
 * 권한(READ_CALENDAR)이 없거나 읽지 못하면 빈 목록 (서버는 일정 없이도 토큰을 줌).
 */
object CalendarEvents {
    private const val TAG = "Calendar"
    private const val MAX_EVENTS = 30          // 서버 제한 (목록당 30개)
    private const val TITLE_MAX = 200
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun today(context: Context): JSONArray = day(context, 0)

    suspend fun tomorrow(context: Context): JSONArray = day(context, 1)

    /** offset: 0 = 오늘, 1 = 내일 (기기 시간대 기준 하루) */
    private suspend fun day(context: Context, offset: Int): JSONArray = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext JSONArray()
        try {
            read(context.applicationContext, offset)
        } catch (e: Exception) {
            Log.w(TAG, "일정을 읽지 못했어요: ${e.message}")
            JSONArray()
        }
    }

    private fun read(context: Context, offset: Int): JSONArray {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + DAY_MS
        // 종일 일정은 UTC 자정 기준으로 저장되므로 그날의 UTC 날짜로 비교
        val utcDay = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis

        // 반복 일정까지 펼쳐 주는 Instances. 앞뒤 하루를 넉넉히 읽고 아래에서 거름
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, dayStart - DAY_MS)
            ContentUris.appendId(it, dayEnd + DAY_MS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.VISIBLE
        )

        class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean)
        val events = mutableListOf<Event>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue                                          // 숨긴 캘린더
                if (c.getInt(4) == CalendarContract.Instances.STATUS_CANCELED) continue // 취소된 일정
                val begin = c.getLong(1)
                val end = c.getLong(2)
                val allDay = c.getInt(3) == 1
                val inDay = if (allDay) begin <= utcDay && utcDay < maxOf(end, begin + 1)
                            else begin < dayEnd && end > dayStart
                if (!inDay) continue
                val title = c.getString(0)?.trim().orEmpty().ifEmpty { "(제목 없음)" }.take(TITLE_MAX)
                events += Event(title, begin, end, allDay)
            }
        }

        val arr = JSONArray()
        // 종일 일정 먼저, 그다음 시작 시각 순
        events.sortedWith(compareBy<Event>({ !it.allDay }, { it.begin })).take(MAX_EVENTS).forEach { e ->
            val o = JSONObject().put("title", e.title).put("all_day", e.allDay)
            if (e.allDay) {
                o.put("start", "").put("end", "")
            } else {
                // 전날 시작했거나 다음 날 끝나는 일정은 그날 안으로 잘라서 보냄
                o.put("start", hhmm(maxOf(e.begin, dayStart)))
                o.put("end", if (e.end >= dayEnd) "23:59" else hhmm(e.end))
            }
            arr.put(o)
        }
        return arr
    }

    private fun hhmm(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
