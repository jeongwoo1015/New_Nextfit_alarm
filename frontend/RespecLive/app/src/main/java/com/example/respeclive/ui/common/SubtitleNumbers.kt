package com.example.respeclive.ui.common

/**
 * 자막 숫자 표기 (AI CONTRACTS B18). Gemini 받아쓰기가 "5초"를 "오 초", "12시"를 "열두 시"처럼 글자로 적어 자막이 어색함.
 * 화면에 그리기 직전 한 줄 전체에 한 번 적용함 (조각마다 바꾸면 "열"과 "두 시"가 나뉘어 틀림). 서버 저장용 원문은 그대로 둠.
 * 기준 구현·테스트: ai/tasks/subtitle_numbers.py. 오 초 → 5초, 다섯 번 → 5번, 열두 시 → 12시, 여섯 시간 → 6시간, 십일 도 → 11도.
 * "한 번 더", "이 분들", "초록", "시작", "번호"처럼 수가 아닌 말은 그대로 둠
 */
object SubtitleNumbers {
    private val SINO_DIGIT = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
    private val NATIVE_ONES = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9)
    private val NATIVE_TENS = listOf("스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "열" to 10, "쉰" to 50)
    private const val NOT_WORD = "록콜대위명석야리착전움작장켜험절선원역호개"
    private const val SINO = "(?:[이삼사오육칠팔구]?백)?(?:[이삼사오육칠팔구]?십)?[일이삼사오육칠팔구]?"
    private const val NATIVE = "(?:열|스물|스무|서른|마흔|쉰)?(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉)?"
    private val SINO_RE = Regex("(?<![가-힣0-9])($SINO) ?(초|분|도|퍼센트)(?![$NOT_WORD])")
    private val NATIVE_RE = Regex("(?<![가-힣0-9])($NATIVE) ?(시간|시|번|세트|가지)(?![$NOT_WORD])")

    private fun sino(word: String): Int? {
        if (word.isEmpty()) return null
        var total = 0
        var num = 0
        for (ch in word) when {
            ch in SINO_DIGIT -> num = SINO_DIGIT.getValue(ch)
            ch == '십' -> { total += (if (num == 0) 1 else num) * 10; num = 0 }
            ch == '백' -> { total += (if (num == 0) 1 else num) * 100; num = 0 }
            else -> return null
        }
        return total + num
    }

    private fun native(word: String): Int? {
        if (word.isEmpty()) return null
        for ((tens, v) in NATIVE_TENS) if (word.startsWith(tens)) {
            val rest = word.removePrefix(tens)
            return if (rest.isEmpty()) v else NATIVE_ONES[rest]?.let { v + it }
        }
        return NATIVE_ONES[word]
    }

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        val a = SINO_RE.replace(text) { m ->
            val (word, unit) = m.destructured
            val v = sino(word)
            // "이 분", "사 도"처럼 한 글자는 수가 아닐 때가 많음 (이분들, 사도)
            if (v == null || (word.length == 1 && word in "이사구일" && (unit == "분" || unit == "도"))) m.value else "$v$unit"
        }
        return NATIVE_RE.replace(a) { m ->
            val (word, unit) = m.destructured
            val v = native(word)
            if (v == null || (word == "한" && unit != "시")) m.value else "$v$unit"     // "한 번 더"는 그대로
        }
    }
}
