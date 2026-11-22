package com.example.respeclive

import com.example.respeclive.ui.content.parseExpression
import com.example.respeclive.ui.content.parseQuestion
import com.example.respeclive.ui.content.scriptField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentParseTest {
    @Test
    fun englishExpression() {
        val e = parseExpression("Good morning! / 좋은 아침이에요. / 굿 모닝")!!
        assertEquals("Good morning!", e.english)
        assertEquals("좋은 아침이에요.", e.meaning)
        assertEquals("굿 모닝", e.pronunciation)
        assertNull(parseExpression(null))
        assertNull(parseExpression("질문: How are you?\n힌트: 기분\n예시: I'm good."))
    }

    @Test
    fun englishQuestion() {
        val q = parseQuestion("질문: How do you feel?\n힌트: 기분\n예시: I feel great.")!!
        assertEquals("How do you feel?", q.question)
        assertEquals("I feel great.", q.example)
        assertNull(parseQuestion("Hello / 안녕"))
    }

    @Test
    fun fields() {
        assertEquals("기차", scriptField("게임: 끝말잇기\n시작 단어: 기차", "시작 단어"))
        assertEquals("ㄱㅊ", scriptField("초성: ㄱㅊ", "초성"))
        assertNull(scriptField("게임: 끝말잇기", "시작 단어"))
    }
}
