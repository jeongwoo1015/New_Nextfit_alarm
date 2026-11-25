package com.example.respeclive

import com.example.respeclive.audio.AlarmVibrator
import com.example.respeclive.data.WakeHistory
import com.example.respeclive.data.WakeProfile
import com.example.respeclive.data.WakeRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeVibrationTest {
    private fun totals(n: Int, rate: Double?) = WakeRate(n, rate)

    @Test
    fun classifyBySuccessRate() {
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(null))
        assertEquals(WakeProfile.HARD, WakeHistory.classify(0.49))
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(0.5))
        assertEquals(WakeProfile.NORMAL, WakeHistory.classify(0.79))
        assertEquals(WakeProfile.EASY, WakeHistory.classify(0.8))
    }

    @Test
    fun weekRateFirstThenOverall() {
        // 이번 주 기록이 충분하면 이번 주
        assertEquals(0.3, WakeHistory.pickRate(totals(3, 0.3), totals(20, 0.9))!!, 1e-9)
        // 주 초반(기록 적음)이면 전체 기간
        assertEquals(0.9, WakeHistory.pickRate(totals(1, 0.0), totals(20, 0.9))!!, 1e-9)
        // 둘 다 적거나 값이 없으면 null → 보통
        assertNull(WakeHistory.pickRate(totals(1, 0.0), totals(2, 0.0)))
        assertNull(WakeHistory.pickRate(totals(5, null), null))
        assertNull(WakeHistory.pickRate(null, null))
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
