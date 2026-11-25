package com.example.respeclive

import com.example.respeclive.data.WakeLevelPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeLevelTest {
    @Test
    fun priority() {
        assertEquals(1, WakeLevelPrefs.resolve(null, null, null))          // 아무것도 없으면 1단계
        assertEquals(4, WakeLevelPrefs.resolve(4, 2, 3))                   // 다음 한 번은 상한보다 우선
        assertEquals(2, WakeLevelPrefs.resolve(null, 2, 4))                // 추천이 상한을 넘으면 상한
        assertEquals(3, WakeLevelPrefs.resolve(null, 4, 3))
        assertEquals(1, WakeLevelPrefs.resolve(null, 3, null))             // 상한만 있으면 기본 1단계
    }
}
