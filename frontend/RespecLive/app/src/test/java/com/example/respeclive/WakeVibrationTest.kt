package com.example.respeclive

import com.example.respeclive.audio.AlarmVibrator
import com.example.respeclive.data.WakeEntry
import com.example.respeclive.data.WakeHistory
import com.example.respeclive.data.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeVibrationTest {
    private fun e(level: Int, rewake: Boolean = false) = WakeEntry(level, rewake)

    @Test
    fun fewRecordsUseSuccessRate() {
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(listOf(e(1), e(1)), null))
        assertEquals(WakeProfile.HARD, WakeHistory.classify(listOf(e(1)), 0.4))
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(emptyList(), 0.9))
    }

    @Test
    fun easyHardNormal() {
        assertEquals(WakeProfile.EASY, WakeHistory.classify(listOf(e(1), e(2), e(2), e(3), e(1)), null))
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(listOf(e(1), e(2), e(1, rewake = true)), null))
        assertEquals(WakeProfile.HARD, WakeHistory.classify(listOf(e(4), e(5), e(3)), null))
        assertEquals(WakeProfile.HARD, WakeHistory.classify(listOf(e(1, true), e(2), e(1, true)), null))
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(listOf(e(3), e(3), e(2)), 0.1))
    }

    @Test
    fun onlyRecentFiveCount() {
        val old = List(5) { e(5) }
        assertEquals(WakeProfile.EASY, WakeHistory.classify(old + List(5) { e(1) }, null))
    }

    @Test
    fun vibrationStep() {
        assertEquals(1, AlarmVibrator.step(1, WakeProfile.EASY))
        assertEquals(1, AlarmVibrator.step(1, WakeProfile.NORMAL))
        assertEquals(3, AlarmVibrator.step(1, WakeProfile.HARD))
        assertEquals(6, AlarmVibrator.step(5, WakeProfile.HARD))
        for (s in 1..6) {
            val (t, a) = AlarmVibrator.pattern(s)
            assertEquals(t.size, a.size)
        }
    }
}
