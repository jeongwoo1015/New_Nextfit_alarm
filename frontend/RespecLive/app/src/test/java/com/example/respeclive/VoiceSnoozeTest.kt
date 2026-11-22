package com.example.respeclive

import com.example.respeclive.data.VoiceSnooze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class VoiceSnoozeTest {
    private val now = Calendar.getInstance().apply { set(2026, 10, 23, 8, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun minutes() {
        assertEquals(now + 10 * 60_000L, VoiceSnooze.check(10, null, now)!!.triggerMillis)
        assertNull(VoiceSnooze.check(0, null, now))
        assertNull(VoiceSnooze.check(61, null, now))
    }

    @Test
    fun atTime() {
        val r = VoiceSnooze.check(null, "9:00", now)!!
        assertEquals("09:00", r.atTime)
        assertEquals(now + 30 * 60_000L, r.triggerMillis)
        assertNull(VoiceSnooze.check(null, "08:00", now))      // 이미 지남
        assertNull(VoiceSnooze.check(null, "10:00", now))      // 60분 넘음
        assertNull(VoiceSnooze.check(null, "9시", now))
    }

    @Test
    fun exactlyOne() {
        assertNull(VoiceSnooze.check(10, "09:00", now))
        assertNull(VoiceSnooze.check(null, null, now))
    }

    @Test
    fun label() {
        assertEquals("오전 9:05", VoiceSnooze.label(now + 35 * 60_000L))
    }
}
