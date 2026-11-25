package com.example.respeclive.ui.diary

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.respeclive.R
import org.json.JSONObject

/**
 * 일기 감정 4종: 사진 없는 날 일기 칸에 넣는 넥스티 (표정 + 몸짓 + 소품, 배경색으로 구분).
 * 밤 기록 컨디션(condition_label) 4종과 하나씩 짝지음. 속상은 쓰지 않기로 함.
 * 그림은 res/drawable/nexty_mood_*.xml (디자인 SVG에서 배경 타일만 빼고 변환), 배경색은 그 타일의 색.
 */
enum class NextyMood(val condition: String, val label: String, @DrawableRes val drawable: Int, val background: Color) {
    JOY("GOOD", "기쁨", R.drawable.nexty_mood_joy, Color(0xFFFFDB6E)),              // 좋은 일이 있고 뿌듯한 날
    CALM("NORMAL", "평온", R.drawable.nexty_mood_calm, Color(0xFFC9E0B4)),          // 무난하고 편안하게 지나간 날
    TIRED("TIRED", "피곤", R.drawable.nexty_mood_tired, Color(0xFFD3CCE4)),         // 바쁘고 지쳐서 힘 빠진 날
    WORRIED("STRESSED", "걱정", R.drawable.nexty_mood_worried, Color(0xFFBFDCEB));  // 내일 일정·발표로 긴장한 날

    companion object {
        /**
         * 달력 하루(CalendarDayOut)의 감정 = 그날 밤 기록의 condition_label(GOOD/NORMAL/TIRED/STRESSED).
         * 기록은 있는데 컨디션을 모르면(서버가 아직 안 줌·추출 전·실패) 평온, 기록이 없으면 null
         */
        fun of(day: JSONObject?): NextyMood? {
            day ?: return null
            val label = day.text("condition_label")?.uppercase()
            entries.firstOrNull { it.condition == label }?.let { return it }
            return if (day.optBoolean("has_session") || day.optBoolean("has_night_record")) CALM else null
        }
    }
}
