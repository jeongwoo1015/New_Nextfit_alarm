package com.example.respeclive

import com.example.respeclive.data.WordChainDictionary
import com.example.respeclive.data.WordChainLine
import com.example.respeclive.data.WordChainMorning
import com.example.respeclive.data.WordChainReason
import com.example.respeclive.data.WordChainRules
import com.example.respeclive.data.WordChainWinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class WordChainTest {
    @Test
    fun dueum() {
        assertEquals('이', WordChainRules.dueum('리'))
        assertEquals('나', WordChainRules.dueum('라'))
        assertEquals('여', WordChainRules.dueum('녀'))
        assertEquals('역', WordChainRules.dueum('력'))
        assertEquals('노', WordChainRules.dueum('로'))
        assertNull(WordChainRules.dueum('나'))
        assertNull(WordChainRules.dueum('가'))
    }

    @Test
    fun validation() {
        assertNull(WordChainRules.problem("과자", "사과", null, emptyList()))
        assertNull(WordChainRules.problem("이불", "자리", null, emptyList()))        // 두음법칙
        assertNotNull(WordChainRules.problem("나무", "사과", null, emptyList()))
        assertNotNull(WordChainRules.problem("과자", "사과", null, listOf("과자")))
        assertNotNull(WordChainRules.problem("과", "사과", null, emptyList()))
        assertNull(WordChainRules.problem("과일", null, '과', emptyList()))
        assertEquals("사과", WordChainRules.clean(" 사과! "))
    }

    @Test
    fun dictionaryPick() {
        val dict = WordChainDictionary(listOf("사과", "과자", "자두", "두부", "이불", "불꽃"))
        assertEquals("과자", dict.pick("사과", null, listOf("사과"), Random(1)))
        assertEquals("이불", dict.pick("자리", null, emptyList(), Random(1)))
        assertNull(dict.pick("부엌", null, emptyList(), Random(1)))
    }

    @Test
    fun assetListLoads() {
        val f = listOf(File("src/main/assets/wordchain_words.txt"), File("app/src/main/assets/wordchain_words.txt")).first { it.exists() }
        val words = f.readLines().filterNot { it.startsWith("#") }.flatMap { it.split(' ') }.filter { it.isNotBlank() }
        assertTrue(words.size > 900)
        assertTrue(words.all { it.matches(Regex("[가-힣]{2,6}")) })
    }

    @Test
    fun morningRecord() {
        val lines = listOf(
            WordChainLine(false, "앞 단어의 마지막 글자로 시작하는 단어를 말하는 거예요. 시작 단어는 '기차'예요!"),
            WordChainLine(true, "차표"),
            WordChainLine(false, "좋아요! 그럼 저는 '표정'! '정'으로 시작하는 단어를 말해 주세요."),
        )
        assertEquals("표정", WordChainMorning.lastNextyWord(lines))
        assertEquals('정', WordChainMorning.requiredStart(lines))
        assertNull(WordChainMorning.decided(lines))
        // 다섯 번쯤 주고받고 칭찬으로 마무리: 칭찬은 건너뛰고 마지막으로 이어진 단어
        val wrapped = lines + WordChainLine(true, "정원이요") + WordChainLine(false, "원숭이! 오늘 끝말잇기 정말 잘했어요!")
        assertEquals(WordChainMorning.ResumePoint("원숭이", false), WordChainMorning.resumePoint(wrapped))
        val userLast = lines + WordChainLine(true, "정원") + WordChainLine(false, "와, 잘했어요! 오늘은 여기까지 할게요.")
        assertEquals(WordChainMorning.ResumePoint("정원", true), WordChainMorning.resumePoint(userLast))
        // 실제 모닝콜(11/22): 시작 단어가 따옴표 없이 나오고, 내 말은 'kier'로 잘못 받아써짐, 마무리 말이 두 번
        val real = listOf(
            WordChainLine(false, "그럼 끝말잇기 시작해 볼게요. 앞 단어의 마지막 글자로 시작하는 단어를 말하는 거예요. 시작 단어는 하늘이에요."),
            WordChainLine(false, "보자기"),
            WordChainLine(true, "kier"),
            WordChainLine(false, "기차"),
            WordChainLine(false, "기러기. 재미있게 잘하셨어요. 그럼 다음 순서로 넘어갈게요."),
            WordChainLine(false, "오늘 끝말잇기도 아주 즐겁게 잘 마치셨어요. 마지막으로 오늘의 포춘을 들어 볼게요."),
        )
        assertEquals(listOf("하늘", "보자기", "기차", "기러기"), WordChainMorning.chainWords(real).map { it.text })
        assertEquals(WordChainMorning.ResumePoint("기러기", false), WordChainMorning.resumePoint(real))
        assertTrue(WordChainMorning.isWrapUp(real[4].text))
        val gaveUp = lines + WordChainLine(true, "음 모르겠어요 패스")
        assertEquals(WordChainWinner.NEXTY, WordChainMorning.decided(gaveUp)?.winner)
        val nextyLost = lines + WordChainLine(false, "앗, 제가 졌어요!")
        assertEquals(WordChainWinner.USER, WordChainMorning.decided(nextyLost)?.winner)
        assertEquals(WordChainReason.NO_WORD, WordChainMorning.decided(nextyLost)?.reason)
    }
}
