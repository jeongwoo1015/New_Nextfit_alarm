package com.example.respeclive

import com.example.respeclive.audio.ttsText
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextTest {
    @Test
    fun tildeIsNotSpoken() {
        assertEquals("좋은 아침이에요 기지개 한번 켜 볼까요?", ttsText("좋은 아침이에요~ 기지개 한번 켜 볼까요?"))
        assertEquals("일어나, 일어나 벌써 아침이야!", ttsText("일어나, 일어나~ 벌써 아침이야!"))
        assertEquals("좋은 아침", ttsText("좋은 아침~~"))
        assertEquals("좋은 아침", ttsText("좋은 아침～"))
    }

    @Test
    fun decorationsAndEmojiAreDropped() {
        assertEquals("좋은 아침이에요!", ttsText("좋은 아침이에요 ♪!"))
        assertEquals("오늘도 화이팅", ttsText("오늘도 화이팅 😊"))
        assertEquals("잘 잤어요?", ttsText("잘 잤어요 ㅎㅎ?"))
        assertEquals("좋아요", ttsText("좋아요^^"))
        assertEquals("오늘 할 일 완료", ttsText("#오늘 할 일 *완료*"))
    }

    @Test
    fun pauseMarksBecomeComma() {
        assertEquals("알람 소리 3, 5, 받지 않으면 커져요.", ttsText("알람 소리 3/5 · 받지 않으면 커져요."))
        assertEquals("음, 그렇군요.", ttsText("음… 그렇군요."))
        assertEquals("잠깐만요, 금방 할게요.", ttsText("잠깐만요... 금방 할게요."))
        assertEquals("연결하는 중", ttsText("연결하는 중…"))
        assertEquals("아침, 저녁", ttsText("아침 — 저녁"))
    }

    @Test
    fun plainTextUnchanged() {
        assertEquals("일어날 시간이에요. 준비되면 시작해요.", ttsText("일어날 시간이에요. 준비되면 시작해요."))
        assertEquals("오늘 습도는 60%예요. (조금 높아요)", ttsText("오늘 습도는 60%예요. (조금 높아요)"))
        assertEquals("“좋은 아침”이라고 말해 봐요!", ttsText("“좋은 아침”이라고 말해 봐요!"))
    }
}
