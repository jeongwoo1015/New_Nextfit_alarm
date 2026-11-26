package com.example.respeclive

import com.example.respeclive.live.LiveConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class UserHoldTest {
    @Test
    fun shortAnswerKeepsMinimum() {
        assertEquals(LiveConfig.USER_GAP_MS, LiveConfig.userHoldMs("응"))
        assertEquals(LiveConfig.USER_GAP_MS, LiveConfig.userHoldMs(""))
    }

    @Test
    fun longerSpeechHoldsUntilTypedPlusReading() {
        // 30자: 30 × 25ms + 1초 = 1.75초
        assertEquals(1_750L, LiveConfig.userHoldMs("가".repeat(30)))
        // 한 박스(64자)를 넘어도 마지막 박스는 많아야 64자: 64 × 25ms + 1초 = 2.6초
        assertEquals(2_600L, LiveConfig.userHoldMs("가".repeat(200)))
    }

    @Test
    fun neverLongerThanMax() {
        assert(LiveConfig.userHoldMs("가".repeat(1_000)) <= LiveConfig.USER_GAP_MAX_MS)
    }
}
