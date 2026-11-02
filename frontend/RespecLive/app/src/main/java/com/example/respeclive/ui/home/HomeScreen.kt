package com.example.respeclive.ui.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.ui.theme.RespecColors
import kotlin.math.roundToInt

private val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")

@Composable
fun HomeScreen(
    onStartNightChat: () -> Unit,
    onPreviewMorningCall: () -> Unit,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (Int) -> Unit
) {
    val context = LocalContext.current
    val alarms = AlarmRepository.alarms

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text("알람 목록", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${alarms.size}개", color = RespecColors.TextSub, fontSize = 14.sp)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            alarms.forEach { alarm ->
                AlarmCard(
                    alarm = alarm,
                    onClick = { onEditAlarm(alarm.id) },
                    onToggle = { checked ->
                        AlarmRepository.setEnabled(context, alarm.id, checked)
                        if (checked) {
                            Toast.makeText(context, AlarmScheduler.describeRemaining(alarm), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDay = { day ->
                        if (!AlarmRepository.toggleDay(context, alarm.id, day)) {
                            Toast.makeText(context, "요일을 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            OutlinedButton(
                onClick = onCreateAlarm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(2.dp, RespecColors.Purple)
            ) {
                Text("+ 나만의 알람 만들기", color = RespecColors.Purple, fontWeight = FontWeight.Bold)
            }

            Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onPreviewMorningCall) {
                    Text("[디버그] 모닝콜 화면 미리보기", color = RespecColors.TextSub, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    AlarmScheduler.scheduleTest(context, 10)
                    Toast.makeText(
                        context, "10초 뒤 알람이 울려요. 화면을 꺼 두고 기다려 보세요", Toast.LENGTH_LONG
                    ).show()
                }) {
                    Text("[디버그] 10초 뒤 알람 울리기", color = RespecColors.TextSub, fontSize = 13.sp)
                }
            }
        }

        SwipeBanner(text = "밀어서 취침 전 대화 시작하기 →", onComplete = onStartNightChat)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDay: (Int) -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val dim = if (alarm.enabled) 1f else 0.5f

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(alarm.label, color = RespecColors.TextSub, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = RespecColors.Lime,
                    checkedBorderColor = RespecColors.Lime,
                    uncheckedThumbColor = RespecColors.TextSub,
                    uncheckedTrackColor = RespecColors.Disabled,
                    uncheckedBorderColor = RespecColors.Disabled
                )
            )
        }
        Text(
            alarm.timeText(),
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(dim)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(dim),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            dayNames.forEachIndexed { index, name ->
                val on = index in alarm.days
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) RespecColors.PurpleDark else RespecColors.Disabled)
                        .clickable { onDay(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(name, color = if (on) Color.White else RespecColors.TextSub, fontSize = 13.sp)
                }
            }
        }
        if (alarm.theme != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .alpha(dim)
                    .clip(RoundedCornerShape(50))
                    .background(RespecColors.MintDark)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(alarm.theme, color = RespecColors.Mint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

/** 오른쪽으로 끝까지 밀면 onComplete 호출 */
@Composable
private fun SwipeBanner(text: String, onComplete: () -> Unit) {
    val density = LocalDensity.current
    val knobSize = 52.dp
    val inset = 6.dp
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val maxOffset = (widthPx - with(density) { (knobSize + inset * 2).toPx() }).coerceAtLeast(0f)
    val shape = RoundedCornerShape(32.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(RespecColors.Banner)
            .border(1.dp, RespecColors.PurpleDark, shape)
            .onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = RespecColors.Purple,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .padding(inset)
                .size(knobSize)
                .clip(CircleShape)
                .background(RespecColors.Purple)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetPx = (offsetPx + delta).coerceIn(0f, maxOffset)
                    },
                    onDragStopped = {
                        if (offsetPx > maxOffset * 0.8f) onComplete()
                        offsetPx = 0f
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = RespecColors.Banner)
        }
    }
}