package com.example.respeclive

import com.example.respeclive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    private val seoulLat = 37.5665
    private val seoulLon = 126.978
    private val kst = 9.0

    private fun minutes(hour: Int, minute: Int) = hour * 60 + minute

    @Test
    fun `서울 겨울 일출 일몰`() {   // 11월 6일(310일째): 약 07:03 / 17:33
        val sun = AppTheme.sunTimes(310, seoulLat, seoulLon, kst)!!
        assertEquals(minutes(7, 3).toDouble(), sun.riseMinute.toDouble(), 6.0)
        assertEquals(minutes(17, 33).toDouble(), sun.setMinute.toDouble(), 6.0)
    }

    @Test
    fun `서울 여름 일출 일몰`() {   // 6월 21일(172일째): 약 05:11 / 19:57
        val sun = AppTheme.sunTimes(172, seoulLat, seoulLon, kst)!!
        assertEquals(minutes(5, 11).toDouble(), sun.riseMinute.toDouble(), 6.0)
        assertEquals(minutes(19, 57).toDouble(), sun.setMinute.toDouble(), 6.0)
    }

    @Test
    fun `해가 떠 있으면 라이트`() {
        val sun = AppTheme.sunTimes(310, seoulLat, seoulLon, kst)!!
        assertTrue(sun.isDark(minutes(5, 0)))
        assertFalse(sun.isDark(minutes(12, 0)))
        assertTrue(sun.isDark(minutes(22, 0)))
    }

    @Test
    fun `해가 지지 않는 날은 null`() {
        assertNull(AppTheme.sunTimes(172, 78.2, 15.6, 2.0))   // 스발바르 여름
    }
}
