package com.example.respeclive

import com.example.respeclive.ui.common.splitLongSentence
import com.example.respeclive.ui.common.splitSubtitlePages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자막 박스 나누기: 한 문장은 한 박스에, 넘치는 문장은 어색하지 않은 곳에서 나눔 */
class SubtitlePagesTest {
    @Test
    fun sentenceThatDoesNotFitMovesWholeToNextBox() {
        val pages = splitSubtitlePages("오늘 하루는 어땠어요? 편하게 이야기해 주실래요?", maxChars = 20)
        assertEquals(listOf("오늘 하루는 어땠어요?", "편하게 이야기해 주실래요?"), pages)
    }

    @Test
    fun shortSentencesShareOneBox() {
        val pages = splitSubtitlePages("좋아요. 시작할게요. 준비됐어요?", maxChars = 64)
        assertEquals(listOf("좋아요. 시작할게요. 준비됐어요?"), pages)
    }

    @Test
    fun longSentenceBreaksAtCommaFirst() {
        val s = "베개를 빼고 바르게 누워 턱을 살짝 당긴 채로, 뒷목을 길게 늘이고 다섯을 셀 동안 그대로 멈춰 있어요."
        val parts = splitLongSentence(s, maxChars = 40)
        assertEquals("베개를 빼고 바르게 누워 턱을 살짝 당긴 채로,", parts[0])
        assertEquals(s, parts.joinToString(" "))
    }

    @Test
    fun longSentenceBreaksAfterConnectiveEndingAndKeepsBalance() {
        val s = "오늘은 특별한 일정이 없어서 집에서 여유롭게 쉬면서 좋아하는 음악을 듣고 일찍 잠자리에 들 생각이에요."
        val parts = splitLongSentence(s, maxChars = 40)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.length <= 40 })
        val last = parts[0].split(" ").last()
        assertTrue("연결 어미 뒤에서 끊어야 함: $last", listOf("서", "면서", "고").any { last.endsWith(it) })
        assertEquals(s, parts.joinToString(" "))
    }

    @Test
    fun longSentenceStartsNewBoxAndEveryBoxFits() {
        val text = "좋아요. " + "고개를 천천히 오른쪽으로 돌려 여덟을 셀 동안 머물고 왼쪽도 같은 방법으로 해 보고 불편하면 움직이는 범위를 줄여요."
        val pages = splitSubtitlePages(text, maxChars = 30)
        assertEquals("좋아요.", pages[0])
        assertTrue(pages.all { it.length <= 30 })
        assertEquals(text, pages.joinToString(" "))
    }
}
