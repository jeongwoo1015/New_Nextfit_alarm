package com.example.respeclive.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.respeclive.data.DeviceLocation
import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class ThemeMode(val label: String) { LIGHT("라이트"), DARK("다크"), AUTO("자동") }

/** 오늘의 일출·일몰 (기기 시간대, 자정부터 지난 분). 극지방처럼 해가 안 뜨거나 안 지는 날은 null */
data class SunTimes(val riseMinute: Int, val setMinute: Int) {
    fun isDark(minuteOfDay: Int) = minuteOfDay < riseMinute || minuteOfDay >= setMinute
}

/**
 * 앱 테마. 선택은 기기에 저장함 (SharedPreferences).
 * 자동: 해가 떠 있으면 라이트, 진 뒤에는 다크. 위치는 기기가 마지막으로 알던 위치, 권한이나 위치가 없으면 서울 기준.
 */
object AppTheme {
    private const val PREFS = "respec_theme"
    private const val KEY_MODE = "mode"
    private const val SEOUL_LAT = 37.5665
    private const val SEOUL_LON = 126.978
    private const val MAX_CHECK_MS = 60 * 60 * 1000L    // 위치나 날짜가 바뀌어도 한 시간 안에 다시 계산

    var mode by mutableStateOf(ThemeMode.AUTO)
        private set

    /** 지금 다크 색을 쓰는가 */
    var isDark by mutableStateOf(true)
        private set

    /** 자동 모드에서 쓰는 오늘의 일출·일몰 */
    var sun by mutableStateOf<SunTimes?>(null)
        private set

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        mode = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.AUTO
        refresh(context)
    }

    fun select(context: Context, selected: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, selected.name).apply()
        mode = selected
        refresh(context)
    }

    /** 지금 시각 기준으로 테마를 맞추고, 다음에 확인할 때까지 남은 시간(ms)을 돌려줌 */
    fun refresh(context: Context): Long {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val place = DeviceLocation.lastCoordinates(context)
        val offsetHours = now.timeZone.getOffset(now.timeInMillis) / 3_600_000.0
        val today = sunTimes(now.get(Calendar.DAY_OF_YEAR), place?.first ?: SEOUL_LAT, place?.second ?: SEOUL_LON, offsetHours)
        sun = today
        isDark = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.AUTO -> today?.isDark(minute) ?: (now.get(Calendar.MONTH) in Calendar.OCTOBER..Calendar.DECEMBER)
        }
        RespecColors.palette = if (isDark) DarkPalette else LightPalette

        val nextChange = listOfNotNull(today?.riseMinute, today?.setMinute).filter { it > minute }.minOrNull()
        val untilChange = nextChange?.let { (it - minute) * 60_000L - now.get(Calendar.SECOND) * 1000L + 1000L } ?: MAX_CHECK_MS
        return untilChange.coerceIn(1000L, MAX_CHECK_MS)
    }

    /**
     * 일출·일몰 계산 (Almanac for Computers 공식, 오차 수 분). offsetHours: 기기 시간대와 UTC의 차이.
     * 해가 안 뜨거나 안 지는 날은 null.
     */
    fun sunTimes(dayOfYear: Int, lat: Double, lon: Double, offsetHours: Double): SunTimes? {
        val rise = sunEventMinute(dayOfYear, lat, lon, offsetHours, rising = true) ?: return null
        val set = sunEventMinute(dayOfYear, lat, lon, offsetHours, rising = false) ?: return null
        return SunTimes(rise, set)
    }

    private fun sunEventMinute(dayOfYear: Int, lat: Double, lon: Double, offsetHours: Double, rising: Boolean): Int? {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * t - 3.289
        val trueLongitude = normalize(
            meanAnomaly + 1.916 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2 * meanAnomaly) + 282.634, 360.0
        )
        var rightAscension = normalize(deg(atan(0.91764 * tanDeg(trueLongitude))), 360.0)
        // 황경과 같은 사분면으로 맞춤
        rightAscension += floor(trueLongitude / 90.0) * 90.0 - floor(rightAscension / 90.0) * 90.0
        rightAscension /= 15.0

        val sinDeclination = 0.39782 * sinDeg(trueLongitude)
        val cosDeclination = cos(asin(sinDeclination))
        val cosHour = (cosDeg(ZENITH) - sinDeclination * sinDeg(lat)) / (cosDeclination * cosDeg(lat))
        if (cosHour > 1.0 || cosHour < -1.0) return null

        val hourAngle = (if (rising) 360.0 - deg(acos(cosHour)) else deg(acos(cosHour))) / 15.0
        val localMean = hourAngle + rightAscension - 0.06571 * t - 6.622
        val localHour = normalize(localMean - lngHour + offsetHours, 24.0)
        return (localHour * 60.0).toInt()
    }

    private const val ZENITH = 90.833      // 해의 윗부분이 지평선에 걸리는 순간 (대기 굴절 포함)

    private fun normalize(value: Double, range: Double) = ((value % range) + range) % range
    private fun deg(radians: Double) = Math.toDegrees(radians)
    private fun sinDeg(degrees: Double) = sin(Math.toRadians(degrees))
    private fun cosDeg(degrees: Double) = cos(Math.toRadians(degrees))
    private fun tanDeg(degrees: Double) = tan(Math.toRadians(degrees))
}
