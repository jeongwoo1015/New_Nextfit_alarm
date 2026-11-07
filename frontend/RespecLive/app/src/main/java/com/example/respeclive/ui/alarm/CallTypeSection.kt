package com.example.respeclive.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.MORNING_CONTENTS
import com.example.respeclive.ui.theme.RespecColors

/** 알람 종류: 모닝콜 / 굿나잇콜 */
@Composable
internal fun AlarmKindToggle(isBedtime: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(4.dp)
    ) {
        listOf(false to "모닝콜", true to "굿나잇콜").forEach { (bedtime, label) ->
            val selected = isBedtime == bedtime
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (selected) RespecColors.Lime else Color.Transparent)
                    .clickable { onChange(bedtime) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (selected) RespecColors.OnAccent else RespecColors.TextSub, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/** 모닝콜에서 함께 할 아침 콘텐츠 (CONTENT_TYPE): 운동 / 외국어 / 퀴즈 중 하나 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MorningContentPicker(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MORNING_CONTENTS.forEach { (code, label) ->
                val on = code == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) RespecColors.MintDark else RespecColors.Card)
                        .border(1.dp, if (on) RespecColors.Mint else RespecColors.CardBorder, RoundedCornerShape(50))
                        .clickable { onSelect(code) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(label, color = if (on) RespecColors.Mint else RespecColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        Text("모닝콜 브리핑이 끝나면 넥스티와 함께 해요.", color = RespecColors.TextSub, fontSize = 13.sp)
    }
}
