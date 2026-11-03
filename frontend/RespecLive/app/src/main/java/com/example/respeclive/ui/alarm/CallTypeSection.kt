package com.example.respeclive.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.CONTENT_OPTIONS
import com.example.respeclive.data.RoutineCatalog
import com.example.respeclive.data.THEMES
import com.example.respeclive.data.durationText
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.theme.RespecColors

/** 알람 유형 (MORNING_CALL_TYPE): 루틴 / 콘텐츠 */
@Composable
internal fun CallTypeToggle(isContent: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(4.dp)
    ) {
        listOf(false to "루틴", true to "콘텐츠").forEach { (content, label) ->
            val selected = isContent == content
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (selected) RespecColors.Lime else Color.Transparent)
                    .clickable { onChange(content) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (selected) Color.Black else RespecColors.TextSub, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/** 프리셋 (TEMPLATE_ID → TEMPLATE_NAME). 누르면 프리셋 고르기 */
@Composable
internal fun PresetRow(name: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("프리셋", color = RespecColors.TextSub, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(name ?: "프리셋을 골라 주세요", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RespecColors.Purple)
    }
}

/**
 * 항목별 시간 (RS_ALARM_ROUTINE_ITEM). 30초 단위, 0초 = 이 알람에서 빼기.
 * 기본 시간(DEFAULT_DURATION_SEC)과 다르면 기본값과 되돌리기 버튼을 보여 줌. 합계는 상한(ROUTINE_MAX_SEC) 이하.
 */
@Composable
internal fun RoutineItemsEditor(
    items: List<RoutineCatalog.Item>,
    durations: Map<String, Int>,
    onChange: (templateItemId: String, sec: Int) -> Unit
) {
    val limits = RoutineCatalog.limits
    val total = items.sumOf { durations[it.templateItemId] ?: it.durationSec }
    val over = total > limits.routineMaxSec
    val empty = items.isNotEmpty() && items.all { (durations[it.templateItemId] ?: it.durationSec) == 0 }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            val sec = durations[item.templateItemId] ?: item.durationSec
            ItemRow(item, sec, limits.stepSec, limits.routineMaxSec) { onChange(item.templateItemId, it) }
        }
        // 합계 막대
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { (total.toFloat() / limits.routineMaxSec).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (over) RespecColors.Danger else RespecColors.Lime,
                trackColor = RespecColors.Disabled,
                drawStopIndicator = {}
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${durationText(total)} / ${durationText(limits.routineMaxSec)}",
                color = if (over) RespecColors.Danger else RespecColors.TextSub,
                fontSize = 14.sp
            )
        }
        when {
            over -> Text("합계가 ${durationText(limits.routineMaxSec)}을 넘을 수 없어요", color = RespecColors.Danger, fontSize = 13.sp)
            empty -> Text("항목을 하나 이상 남겨 주세요", color = RespecColors.Danger, fontSize = 13.sp)
            else -> Text("0초로 하면 이 알람에서 해당 항목이 제외돼요.", color = RespecColors.TextSub, fontSize = 13.sp)
        }
    }
}

/**
 * 항목 한 줄: [이름][기본 / 되돌리기] ······ [−][시간][+].
 * 줄 폭에 비례해 버튼·시간 칸 크기를 정하고, 이름은 남는 폭 안에서 한 줄로(모자라면 글자를 줄임).
 * '기본' 배지와 버튼은 줄어들지 않아 좁은 화면·큰 글꼴에서도 두 줄로 꺾이지 않음
 */
@Composable
private fun ItemRow(item: RoutineCatalog.Item, sec: Int, step: Int, max: Int, onChange: (Int) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val removed = sec == 0
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
    ) {
        val w = maxWidth
        val button = (w * 0.09f).coerceIn(28.dp, 36.dp)      // − / + 버튼
        val timeWidth = (w * 0.17f).coerceIn(48.dp, 68.dp)   // "1분 30초" 칸
        val gap = (w * 0.02f).coerceIn(4.dp, 8.dp)
        Row(
            Modifier.padding(start = (w * 0.04f).coerceIn(12.dp, 18.dp), end = gap, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FitText(
                    item.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    minSize = 11.sp,
                    modifier = Modifier.weight(1f, fill = false).alpha(if (removed) 0.45f else 1f)
                )
                Spacer(Modifier.width(gap))
                // 기본 시간이면 '기본', 바꿨으면 그 자리에 기본 시간으로 되돌리는 아이콘
                if (sec == item.defaultSec) {
                    Text(
                        "기본",
                        color = RespecColors.TextSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(RespecColors.Disabled)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                } else {
                    Box(
                        Modifier
                            .size(button * 0.8f)
                            .clip(CircleShape)
                            .clickable { onChange(item.defaultSec) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Replay,
                            "기본 시간(${durationText(item.defaultSec)})으로 되돌리기",
                            tint = RespecColors.Purple,
                            modifier = Modifier.size(button * 0.55f)
                        )
                    }
                }
                Spacer(Modifier.width(gap))
            }
            StepButton(Icons.Filled.Remove, "줄이기", button, enabled = sec > 0) { onChange((sec - step).coerceAtLeast(0)) }
            FitText(
                if (removed) "0" else durationText(sec),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(timeWidth)
            )
            StepButton(Icons.Filled.Add, "늘리기", button, enabled = sec + step <= max) { onChange(sec + step) }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) RespecColors.PurpleDark else RespecColors.Disabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else RespecColors.TextSub, modifier = Modifier.size(size * 0.53f))
    }
}

/** 콘텐츠 종류 (CONTENT_TYPE)와 세부 (CONTENT_OPTION). 시간은 고정 (CONTENT_DURATION_SEC) */
@Composable
internal fun ContentEditor(
    contentType: String?,
    contentOption: String?,
    onType: (String) -> Unit,
    onOption: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipRow(THEMES, contentType) { onType(it) }
        val options = CONTENT_OPTIONS[contentType].orEmpty()
        if (options.isNotEmpty()) {
            Text("세부 주제", color = RespecColors.TextSub, fontSize = 13.sp)
            ChipRow(listOf("" to "AI 추천") + options, contentOption ?: "") { onOption(it.ifEmpty { null }) }
        }
        Text(
            "콘텐츠는 매일 ${durationText(RoutineCatalog.limits.contentSec)}으로 진행돼요. 단계는 아침에 AI가 구성해요.",
            color = RespecColors.TextSub,
            fontSize = 13.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(choices: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (code, label) ->
            val on = code == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) RespecColors.MintDark else RespecColors.Card)
                    .border(1.dp, if (on) RespecColors.Mint else RespecColors.CardBorder, RoundedCornerShape(50))
                    .clickable { onSelect(code) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(label, color = if (on) RespecColors.Mint else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}
