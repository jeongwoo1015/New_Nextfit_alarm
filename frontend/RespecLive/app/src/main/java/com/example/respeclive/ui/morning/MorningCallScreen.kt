package com.example.respeclive.ui.morning

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private const val VOLUME_UP_SECONDS = 10

/**
 * 알람 수신 화면 → 밀어서 받으면 아침 대화 화면.
 * 알람이 울리면 바로 서버 세션을 만든다 (스누즈 뒤 다시 울려도 같은 세션). 스누즈에도 세션 id가 필요하다.
 * onCallEnded: 받은 통화가 끝났을 때 (세션 id, 통화 길이 초). 받지 않고 끄거나 세션이 없으면 onFinish
 */
@Composable
fun MorningCallScreen(
    onFinish: () -> Unit,
    onCallEnded: (sessionId: String, talkSec: Int) -> Unit,
    onSnooze: () -> Unit = onFinish,
    alarmId: Int = -1,
    alarmLabel: String = "오전 6:00"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var answered by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        val local = AlarmRepository.get(alarmId)
            ?: AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE && it.enabled }
        session = RespecApi.startMorningFor(local)?.also { RoutineRepository.applyView(it) }
        RoutineRepository.loadPlan()   // 단계가 생기기 전 체크리스트는 이 세션 알람의 루틴
    }

    if (answered) {
        MorningChatScreen(
            alarmId = alarmId,
            initialSession = session,
            onFinish = { sid, talkSec -> if (sid != null) onCallEnded(sid, talkSec) else onFinish() }
        )
    } else {
        MorningIncomingScreen(
            onAnswer = { answered = true },
            onDismiss = onFinish,
            onSnooze = {
                AlarmScheduler.scheduleSnooze(context, alarmId, alarmLabel)
                session?.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
                    ?.let { sid -> scope.launch { RespecApi.snooze(sid) } }
                Toast.makeText(context, "${AlarmScheduler.SNOOZE_MINUTES}분 뒤에 다시 울려요", Toast.LENGTH_SHORT).show()
                onSnooze()
            },
            alarmLabel = alarmLabel
        )
    }
}

@Composable
private fun MorningIncomingScreen(
    onAnswer: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    alarmLabel: String = "오전 6:00"
) {
    val context = LocalContext.current
    val view = LocalView.current
    val alarm = remember { AlarmSoundPlayer(context) }
    var level by remember { mutableIntStateOf(1) }
    var countdown by remember { mutableIntStateOf(VOLUME_UP_SECONDS) }

    // 알람음 재생 + 화면 켜짐 유지. 화면을 벗어나면 정지하고 볼륨 복원
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        alarm.start(level = 1)
        onDispose {
            view.keepScreenOn = false
            alarm.stop()
        }
    }

    // 받지 않으면 10초마다 알람 볼륨 1단계 증가
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            countdown -= 1
            if (countdown <= 0) {
                if (level < AlarmSoundPlayer.MAX_LEVEL) {
                    level += 1
                    alarm.setLevel(level)
                }
                countdown = VOLUME_UP_SECONDS
            }
        }
    }

    BackHandler(onBack = onDismiss) // 테스트용: 뒤로 가기 = 알람 끄기

    val infinite = rememberInfiniteTransition(label = "ring")
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ringScale"
    )
    val ringAlpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ringAlpha"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Call, null, tint = RespecColors.AccentText, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text("AI 모닝콜", color = RespecColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("지금 당신의 하루가 시작돼요", color = RespecColors.TextSub, fontSize = 14.sp)
        }

        // 세로가 짧은 폰에서는 아래 버튼이 잘리지 않도록 동그라미를 남은 공간에 맞춰 줄임
        // (아래 이름·시각 칩 자리 약 150dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val orb = minOf(260.dp, maxHeight - 150.dp, maxWidth).coerceAtLeast(0.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(orb)) {
                    Box(
                        Modifier
                            .size(orb * 210 / 260)
                            .scale(ringScale)
                            .alpha(ringAlpha)
                            .border(2.dp, RespecColors.Lime, CircleShape)
                    )
                    Box(
                        Modifier
                            .size(orb * 210 / 260)
                            .clip(CircleShape)
                            .background(RespecColors.Card)
                            .border(1.dp, RespecColors.CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.WbSunny, null, tint = RespecColors.AccentText, modifier = Modifier.size(orb * 96 / 260))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("리스펙", color = RespecColors.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(RespecColors.Card)
                        .border(1.dp, RespecColors.CardBorder, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Alarm, null, tint = RespecColors.AccentText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(alarmLabel, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(
                if (level >= AlarmSoundPlayer.MAX_LEVEL) "알람 소리 최대"
                else "알람 소리 $level/${AlarmSoundPlayer.MAX_LEVEL} · 받지 않으면 ${countdown}초 뒤 커져요",
                color = RespecColors.TextSub,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            SwipeToAnswer(onComplete = onAnswer)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSnooze) {
                Text(
                    "${AlarmScheduler.SNOOZE_MINUTES}분 뒤 다시 울리기",
                    color = RespecColors.TextSub,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 오른쪽 끝까지 밀면 onComplete */
@Composable
private fun SwipeToAnswer(onComplete: () -> Unit) {
    val density = LocalDensity.current
    val knob = 68.dp
    val inset = 4.dp
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val maxOffset = (widthPx - with(density) { (knob + inset * 2).toPx() }).coerceAtLeast(0f)
    val shape = RoundedCornerShape(50)

    val infinite = rememberInfiniteTransition(label = "chevron")
    val chevronAlpha by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "chevronAlpha"
    )
    val hintAlpha = if (maxOffset > 0f) 1f - (offsetPx / maxOffset) else 1f

    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, end = 16.dp)
                .alpha(hintAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("밀어서 받기", color = RespecColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row {
                repeat(3) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        null,
                        tint = RespecColors.AccentText,
                        modifier = Modifier
                            .size(20.dp)
                            .alpha(chevronAlpha)
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .padding(inset)
                .size(knob)
                .clip(CircleShape)
                .background(RespecColors.Lime)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetPx = (offsetPx + delta).coerceIn(0f, maxOffset)
                    },
                    onDragStopped = {
                        if (offsetPx > maxOffset * 0.85f) onComplete()
                        offsetPx = 0f
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Call, null, tint = RespecColors.OnAccent, modifier = Modifier.size(30.dp))
        }
    }
}