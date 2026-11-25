package com.example.respeclive

import com.example.respeclive.ui.common.SubtitleNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

/** 자막 숫자 표기: 기대값은 ai/tasks/subtitle_numbers.py의 결과 (AI CONTRACTS B18) */
class SubtitleNumbersTest {
    @Test
    fun sameAsAiReference() {
        assertEquals("5초 동안 머물러요", SubtitleNumbers.apply("오 초 동안 머물러요"))
        assertEquals("5번 반복해요", SubtitleNumbers.apply("다섯 번 반복해요"))
        assertEquals("지금은 12시예요", SubtitleNumbers.apply("지금은 열두 시예요"))
        assertEquals("6시간 정도 잤네요", SubtitleNumbers.apply("여섯 시간 정도 잤네요"))
        assertEquals("오늘은 11도예요", SubtitleNumbers.apply("오늘은 십일 도예요"))
        assertEquals("한 번 더 해 볼까요", SubtitleNumbers.apply("한 번 더 해 볼까요"))
        assertEquals("이 분들", SubtitleNumbers.apply("이 분들"))
        assertEquals("초록색", SubtitleNumbers.apply("초록색"))
        assertEquals("시작해 볼게요", SubtitleNumbers.apply("시작해 볼게요"))
        assertEquals("번호를 말해요", SubtitleNumbers.apply("번호를 말해요"))
        assertEquals("20초씩", SubtitleNumbers.apply("이십 초씩"))
        assertEquals("25번", SubtitleNumbers.apply("스물다섯 번"))
        assertEquals("1시에 만나요", SubtitleNumbers.apply("한 시에 만나요"))
        assertEquals("이 분 정도", SubtitleNumbers.apply("이 분 정도"))
        assertEquals("30퍼센트", SubtitleNumbers.apply("삼십 퍼센트"))
        assertEquals("7시 반", SubtitleNumbers.apply("일곱 시 반"))
        assertEquals("3가지", SubtitleNumbers.apply("세 가지"))
        assertEquals("2세트", SubtitleNumbers.apply("두 세트"))
        assertEquals("이미 5초 지났어요", SubtitleNumbers.apply("이미 5초 지났어요"))
        assertEquals("10번만 더", SubtitleNumbers.apply("열 번만 더"))
    }
}
