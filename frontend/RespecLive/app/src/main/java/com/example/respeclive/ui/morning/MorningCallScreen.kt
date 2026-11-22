package com.example.respeclive.ui.morning

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.audio.AlarmVibrator
import com.example.respeclive.data.SnoozeCount
import com.example.respeclive.data.WakeHistory
import com.example.respeclive.data.WakeLevelPrefs
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.common.SwipeToAnswer
import com.example.respeclive.ui.nexty.MorningDecor
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val VOLUME_UP_SECONDS = 10
/** 미루기 버튼: 서버 답을 이 시간까지만 기다리고, 넘으면 기기에서 5분 뒤로 */
private const val SNOOZE_WAIT_MS = 3_000L

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
    // 오늘 미룬 횟수 (버튼에 남은 횟수 표시). 세션의 미루기 횟수가 더 크면 그 값으로
    var snoozesToday by remember { mutableIntStateOf(SnoozeCount.get(context)) }
    var snoozing by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session?.optJSONObject("session")?.optInt("snooze_count", 0)?.let { snoozesToday = SnoozeCount.atLeast(context, it) }
    }

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
            onFinish = { sid, talkSec -> if (sid != null) onCallEnded(sid, talkSec) else onFinish() },
            onSnoozed = onSnooze
        )
    } else {
        MorningIncomingScreen(
            onAnswer = { answered = true },
            onDismiss = onFinish,
            snoozeLeft = SnoozeCount.left(snoozesToday),
            onSnooze = {
                if (snoozing || snoozesToday >= SnoozeCount.MAX_PER_DAY) return@MorningIncomingScreen
                snoozing = true
                scope.launch {
                    // 서버가 하루 3번(버튼·말 합산)을 검사하고 다시 울릴 시각을 정함. 서버에 닿지 못하면 기기에서 5분 뒤로
                    val sid = session?.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
                    val r = sid?.let { withTimeoutOrNull(SNOOZE_WAIT_MS) { RespecApi.snoozeTo(it, null, null) } }
                    if (r?.code == "SNOOZE_LIMIT") {
                        // 한도를 넘음: 미루지 않고 알람은 계속 울림
                        snoozesToday = SnoozeCount.set(context, SnoozeCount.MAX_PER_DAY)
                        Toast.makeText(context, "오늘은 더 미룰 수 없어요 (하루 ${SnoozeCount.MAX_PER_DAY}번까지)", Toast.LENGTH_SHORT).show()
                        snoozing = false
                        return@launch
                    }
                    val at = r?.snoozedUntilMs ?: (System.currentTimeMillis() + AlarmScheduler.SNOOZE_MINUTES * 60_000L)
                    AlarmScheduler.scheduleSnoozeAt(context, alarmId, alarmLabel, at)
                    snoozesToday = r?.snoozesToday?.let { SnoozeCount.set(context, it) } ?: SnoozeCount.increment(context)
                    val left = SnoozeCount.left(snoozesToday)
                    Toast.makeText(
                        context,
                        "${AlarmScheduler.SNOOZE_MINUTES}분 뒤에 다시 울려요 · " + if (left > 0) "오늘 ${left}번 더 미룰 수 있어요" else "오늘은 마지막 미루기예요",
                        Toast.LENGTH_SHORT
                    ).show()
                    onSnooze()
                }
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
    alarmLabel: String = "오전 6:00",
    snoozeLeft: Int = SnoozeCount.MAX_PER_DAY,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val alarm = remember { AlarmSoundPlayer(context) }
    val vibration = remember { AlarmVibrator(context) }
    // 시작 단계: 넥스티에게 말로 정한 값(다음 한 번·상한)이 있으면 그 값, 없으면 서버 추천(아직 API가 없어 1단계)
    val startLevel = remember { WakeLevelPrefs.takeStartLevel() }
    var level by remember { mutableIntStateOf(startLevel) }
    var countdown by remember { mutableIntStateOf(VOLUME_UP_SECONDS) }

    // 알람음 재생 + 화면 켜짐 유지. 화면을 벗어나면 정지하고 볼륨 복원
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        alarm.start(level = startLevel)
        // 진동: 볼륨 단계를 따라 세지고, 최근 아침 기록으로 나눈 유형에 따라 시작 세기가 다름
        vibration.start(startLevel, WakeHistory.profile(context))
        onDispose {
            view.keepScreenOn = false
            alarm.stop()
            vibration.stop()
        }
    }

    // 받지 않으면 10초마다 알람 볼륨 1단계 증가
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            countdown -= 1
            if (countdown <= 0) {
                if (level < AlarmSoundPlayer.WAKE_MAX_LEVEL) {
                    level += 1
                    alarm.setLevel(level)
                    vibration.setLevel(level)
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
            .background(RespecColors.MorningBackground)
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
                        // 손 흔드는 넥스티. 원 안에 여유 있게 (캐릭터 높이가 원의 약 60%)
                        NextyCharacter(NextyMotion.WAVE, Modifier.size(orb * 180 / 260))
                        MorningDecor(RespecColors.AccentText, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("넥스티", color = RespecColors.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
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
                if (level >= AlarmSoundPlayer.WAKE_MAX_LEVEL) "알람 소리 최대"
                else "알람 소리 $level/${AlarmSoundPlayer.WAKE_MAX_LEVEL} · 받지 않으면 ${countdown}초 뒤 커져요",
                color = RespecColors.TextSub,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            SwipeToAnswer(onComplete = {
                WakeHistory.recordAnswer(context, level)   // 받은 단계 = 진동 유형을 나누는 기준
                onAnswer()
            })
            Spacer(Modifier.height(8.dp))
            // 하루 3번(버튼·말 합산)을 다 쓰면 누를 수 없음
            TextButton(onClick = onSnooze, enabled = snoozeLeft > 0) {
                Text(
                    if (snoozeLeft > 0) "${AlarmScheduler.SNOOZE_MINUTES}분 뒤 다시 울리기 · ${snoozeLeft}번 남음"
                    else "오늘은 더 미룰 수 없어요",
                    color = if (snoozeLeft > 0) RespecColors.TextSub else RespecColors.TextSub.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
