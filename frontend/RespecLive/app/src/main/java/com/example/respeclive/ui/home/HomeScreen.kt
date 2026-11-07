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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.RoutineCatalog
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.common.alarmSummary
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")

@Composable
fun HomeScreen(
    onStartNightChat: () -> Unit,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (Int) -> Unit,
    onOpenDebug: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 켜진 알람 중 다음에 울릴 순서, 꺼진 알람은 아래로. 같은 시각이면 먼저 만든 순 (CREATED_AT)
    val alarms = AlarmRepository.alarms.sortedWith(
        compareBy<Alarm>({ !it.enabled }, { AlarmScheduler.nextTriggerMillis(it) ?: Long.MAX_VALUE }, { it.createdAt ?: "" })
    )

    // 홈을 열 때마다 서버 알람 목록으로 맞춤 (실패하면 기기에 저장된 목록 그대로).
    // 카드의 프리셋 이름·루틴 합계는 그다음에 받음 (알람 updated_at이 같으면 다시 받지 않음)
    LaunchedEffect(Unit) {
        AlarmRepository.sync(context)
        RoutineCatalog.loadLimits()
        AlarmRepository.alarms.filter { it.type == AlarmType.WAKE && !it.isContent }.forEach { a ->
            a.templateId?.let { RoutineCatalog.ensurePreset(it) }
            RoutineCatalog.loadRoutine(a)
        }
    }

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
            Text("알람 목록", color = RespecColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                        scope.launch {
                            val error = AlarmRepository.setEnabled(context, alarm.id, checked)
                            val message = error ?: if (checked) AlarmScheduler.describeRemaining(alarm) else null
                            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onDay = { day ->
                        scope.launch {
                            AlarmRepository.toggleDay(context, alarm.id, day)
                                ?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
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
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))   // 목록 끝과 띄워 둠
        SwipeBanner(text = "밀어서 취침 전 대화 시작하기 →", onComplete = onStartNightChat)
        TextButton(onClick = onOpenDebug, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("디버그", color = RespecColors.TextSub, fontSize = 13.sp)
        }
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
            TypeBadge(
                when {
                    alarm.type == AlarmType.BEDTIME -> "취침"
                    alarm.isContent -> "콘텐츠"
                    else -> "루틴"
                },
                content = alarm.isContent
            )
            Spacer(Modifier.width(8.dp))
            Text(alarm.label, color = RespecColors.TextSub, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RespecColors.OnAccent,
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
            color = RespecColors.Text,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(dim)
        )
        // 루틴: 프리셋 · 합계, 콘텐츠: 종류 · 세부 · 시간
        if (alarm.type == AlarmType.WAKE) {
            val summary = alarmSummary(alarm)
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    color = if (alarm.isContent) RespecColors.Mint else RespecColors.AccentText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().alpha(dim)
                )
            }
        }
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
                    Text(name, color = if (on) RespecColors.Text else RespecColors.TextSub, fontSize = 13.sp)
                }
            }
        }
    }
}

/** 알람 유형 배지 (MORNING_CALL_TYPE): 루틴 / 콘텐츠 / 취침 */
@Composable
private fun TypeBadge(text: String, content: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (content) RespecColors.MintDark else RespecColors.PurpleDark)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = if (content) RespecColors.Mint else RespecColors.Text,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
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
            .border(1.dp, RespecColors.Banner, shape)
            .onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart
    ) {
        // 손잡이 자리를 비워 두고, 좁은 화면에서는 줄바꿈 대신 글자를 줄임
        FitText(
            text,
            modifier = Modifier.fillMaxWidth().padding(start = knobSize + inset * 2, end = 16.dp),
            textAlign = TextAlign.Center,
            color = RespecColors.OnBanner,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .padding(inset)
                .size(knobSize)
                .clip(CircleShape)
                .background(RespecColors.Sun)
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
            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color(0xFF1F2547))
        }
    }
}