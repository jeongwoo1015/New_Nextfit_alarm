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
import com.example.respeclive.data.ApiClient
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
/** 아침 결과 보고: 세션이 만들어지기를 이 시간까지 기다림 */
private const val REPORT_WAIT_MS = 15_000L
/** 미루기 버튼: 서버 답을 이 시간까지만 기다리고, 넘으면 기기에서 5분 뒤로 */
private const val SNOOZE_WAIT_MS = 3_000L
/** 미루기를 다 쓴 뒤 다시 울리면 이만큼 안내를 보여 주고 저절로 받음 */
private const val AUTO_ANSWER_DELAY_MS = 3_000L

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
    // 이 알람을 오늘 미룬 횟수 (버튼에 남은 횟수 표시, 알람마다 따로). 세션의 미루기 횟수가 더 크면 그 값으로
    var snoozesToday by remember { mutableIntStateOf(SnoozeCount.get(context, alarmId)) }
    var snoozing by remember { mutableStateOf(false) }

    // 아침 결과 보고 (B23): 시작 단계와 받은 단계(못 받았으면 null). 세션이 아직 안 만들어졌으면 잠깐 기다렸다 보냄.
    // 화면을 벗어나도 보내도록 앱 전체 범위에서
    fun reportWake(received: Int?) {
        val start = WakeLevelPrefs.lastStartLevel()
        fun sid() = session?.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
        ApiClient.scope.launch {
            var id = sid()
            var waited = 0L
            while (id == null && waited < REPORT_WAIT_MS) {
                delay(500)
                waited += 500
                id = sid()
            }
            id?.let { RespecApi.reportWakeResult(it, start, received) }
        }
    }
    LaunchedEffect(session) {
        session?.optJSONObject("session")?.optInt("snooze_count", 0)?.let { snoozesToday = SnoozeCount.atLeast(context, alarmId, it) }
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
            onAnswer = { received ->
                reportWake(received)
                answered = true
            },
            // 이 알람을 3번 다 미룬 뒤 다시 울리면 받기를 기다리지 않고 모닝콜을 바로 시작함 (11/26).
            // 사용자가 반응한 게 아니므로 받은 단계는 보고하지 않음 (null로 보내면 모든 단계 실패로 학습됨)
            autoAnswer = !snoozing && snoozesToday >= SnoozeCount.MAX_PER_ALARM,
            onAutoAnswer = { answered = true },
            onDismiss = { pressedLevel ->
                // 받지 않고 끔: 끄기도 반응이라 누른 순간의 단계를 보고 (11/27 서버). null은 반응 없이 저절로 꺼졌을 때만
                reportWake(pressedLevel)
                onFinish()
            },
            snoozeLeft = SnoozeCount.left(snoozesToday),
            onSnooze = { pressedLevel ->
                if (snoozing || snoozesToday >= SnoozeCount.MAX_PER_ALARM) return@MorningIncomingScreen
                snoozing = true
                scope.launch {
                    // 서버가 알람마다 3번(버튼·말 합산)을 검사하고 다시 울릴 시각을 정함. 서버에 닿지 못하면 기기에서 5분 뒤로
                    val sid = session?.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
                    val r = sid?.let { withTimeoutOrNull(SNOOZE_WAIT_MS) { RespecApi.snoozeTo(it, null, null) } }
                    if (r?.code == "SNOOZE_LIMIT") {
                        // 한도를 넘음: 미루지 않고 알람은 계속 울림
                        snoozesToday = SnoozeCount.set(context, alarmId, SnoozeCount.MAX_PER_ALARM)
                        Toast.makeText(context, "이 알람은 더 미룰 수 없어요(알람마다 ${SnoozeCount.MAX_PER_ALARM}번까지).", Toast.LENGTH_SHORT).show()
                        snoozing = false
                        return@launch
                    }
                    val at = r?.snoozedUntilMs ?: (System.currentTimeMillis() + AlarmScheduler.SNOOZE_MINUTES * 60_000L)
                    AlarmScheduler.scheduleSnoozeAt(context, alarmId, alarmLabel, at)
                    snoozesToday = r?.snoozeCount?.let { SnoozeCount.set(context, alarmId, it) } ?: SnoozeCount.increment(context, alarmId)
                    val left = SnoozeCount.left(snoozesToday)
                    Toast.makeText(
                        context,
                        "${AlarmScheduler.SNOOZE_MINUTES}분 뒤에 다시 울려요. " + if (left > 0) "오늘 ${left}번 더 미룰 수 있어요." else "오늘은 마지막 미루기예요.",
                        Toast.LENGTH_SHORT
                    ).show()
                    reportWake(pressedLevel)    // 미루기 버튼을 누른 순간의 단계
                    onSnooze()
                }
            },
            alarmLabel = alarmLabel
        )
    }
}

@Composable
private fun MorningIncomingScreen(
    onAnswer: (receivedLevel: Int) -> Unit,
    onDismiss: (pressedLevel: Int) -> Unit,
    onSnooze: (pressedLevel: Int) -> Unit,
    alarmLabel: String = "오전 6:00",
    snoozeLeft: Int = SnoozeCount.MAX_PER_ALARM,
    autoAnswer: Boolean = false,
    onAutoAnswer: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val alarm = remember { AlarmSoundPlayer(context) }
    val vibration = remember { AlarmVibrator(context) }
    // 시작 단계: 서버가 정해 준 오늘의 시작 단계(GET /wake-volume/next, 미리 받아 둠). 오늘 값을 못 받았으면
    // 넥스티에게 말로 정한 값(다음 한 번 > 상한), 그것도 없으면 1단계
    val startLevel = remember { WakeLevelPrefs.takeStartLevel() }
    var level by remember { mutableIntStateOf(startLevel) }
    var countdown by remember { mutableIntStateOf(VOLUME_UP_SECONDS) }

    // 알람음 재생 + 화면 켜짐 유지. 화면을 벗어나면 정지하고 볼륨 복원
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        alarm.start(level = startLevel)
        // 진동: 볼륨 단계를 따라 세지고, 서버 기상 성공률로 나눈 유형(WakeHistory)에 따라 시작 세기가 다름
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

    // 미루기를 다 썼으면 안내를 잠깐 보여 주고 저절로 받음
    LaunchedEffect(autoAnswer) {
        if (!autoAnswer) return@LaunchedEffect
        delay(AUTO_ANSWER_DELAY_MS)
        onAutoAnswer()
    }

    BackHandler { onDismiss(level) }   // 뒤로 가기도 알람 끄기와 같음

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
            Text("지금 당신의 하루가 시작돼요.", color = RespecColors.TextSub, fontSize = 14.sp)
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
                if (autoAnswer) "이 알람은 ${SnoozeCount.MAX_PER_ALARM}번 다 미뤘어요. 넥스티가 바로 연결할게요."
                else if (level >= AlarmSoundPlayer.WAKE_MAX_LEVEL) "알람 소리 최대"
                else "알람 소리 $level/${AlarmSoundPlayer.WAKE_MAX_LEVEL} · 받지 않으면 ${countdown}초 뒤 커져요.",
                color = RespecColors.TextSub,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            SwipeToAnswer(onComplete = {
                onAnswer(level)
            })
            Spacer(Modifier.height(8.dp))
            // 이 알람의 3번(버튼·말 합산)을 다 쓰면 누를 수 없음
            TextButton(onClick = { onSnooze(level) }, enabled = snoozeLeft > 0) {
                Text(
                    if (snoozeLeft > 0) "${AlarmScheduler.SNOOZE_MINUTES}분 뒤 다시 울리기 · ${snoozeLeft}번 남음"
                    else "이 알람은 더 미룰 수 없어요",
                    color = if (snoozeLeft > 0) RespecColors.TextSub else RespecColors.TextSub.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 받지 않고 알람 끄기: 바로 다시 잠들지 않도록 받기보다 작고 덜 눈에 띄게. 그날은 모닝콜 없이 끝남.
            // 미루기를 다 써서 저절로 연결하는 중에는 숨김 (끄기로 빠져나가지 않게)
            if (!autoAnswer) {
                TextButton(onClick = { onDismiss(level) }) {
                    Text("알람 끄기", color = RespecColors.TextSub.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}
