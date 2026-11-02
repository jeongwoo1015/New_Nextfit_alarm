package com.example.respeclive.ui.morning

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.audio.MessagePlayer
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.MusicStore
import com.example.respeclive.data.NightMessageStore
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.live.LiveAuth
import com.example.respeclive.live.LiveClient
import com.example.respeclive.ui.common.SubtitleBox
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MPhase { AiSpeaking, Playing, Listening }
private enum class Stage { Preparing, Message, Briefing, Coaching, Finished }
private data class Banner(val title: String, val sub: String)

// 말 대신 누를 수 있는 대답 (Live에 사용자 글자 입력으로 보냄)
private const val BTN_DONE = "다 했어요"
private const val BTN_LATER = "다음에 할게요"
private const val BTN_WAIT = "잠깐만요"
private const val BTN_READY = "준비됐어요"

/** AI 질문 뒤 이 시간 동안 대답이 없으면 재기상 음악 (브리핑의 기상 확인 전까지만) */
private const val REWAKE_SILENCE_MS = 8_000L
private const val REWAKE_LEVEL = 3

/**
 * 아침 대화 (Gemini Live). 지시문·함수는 서버가 토큰에 고정한다 (ai/live, AI CONTRACTS 2절).
 * 세션 시작 → 어젯밤 한 마디 → 브리핑 세션 → start_routine → 코칭 세션(새 토큰) → end_session 또는 통화 종료
 * initialSession: 알람 수신 화면에서 이미 만든 세션 (없으면 여기서 만듦)
 */
@Composable
fun MorningChatScreen(alarmId: Int, onFinish: () -> Unit, initialSession: JSONObject? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val client = remember { LiveClient(context) }
    val messagePlayer = remember { MessagePlayer() }
    val rewakePlayer = remember { AlarmSoundPlayer(context) }
    val messageDone = remember { arrayOfNulls<CompletableDeferred<Unit>>(1) }
    val toolEvents = remember { Channel<String>(Channel.UNLIMITED) }   // start_routine / end_session
    val micReady = remember { CompletableDeferred<Unit>() }
    val items = RoutineRepository.items

    val status by client.status.collectAsState()
    val aiSpeaking by client.aiSpeaking.collectAsState()
    val lines by client.transcript.collectAsState()
    val liveMessage by client.message.collectAsState()

    var stage by remember { mutableStateOf(Stage.Preparing) }
    var notice by remember { mutableStateOf("리스펙이 준비하는 중…") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var responded by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Banner?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(true) }
    var wakeChecked by remember { mutableStateOf(false) }   // wake_check_done 받음 → 재기상 음악 더 이상 안 틂
    var rewaking by remember { mutableStateOf(false) }

    // ---- 마이크 권한: 받기 전에 Live를 열면 마이크가 열리지 않으므로 결과를 기다렸다가 연결 ----
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "마이크 권한이 없으면 버튼으로 대답해 주세요", Toast.LENGTH_SHORT).show()
        }
        micReady.complete(Unit)
    }
    LaunchedEffect(Unit) {
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (has) micReady.complete(Unit) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ---- 정리 + 화면 켜짐 유지. 화면을 벗어나면 서버에 세션 종료 기록 ----
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            client.release()
            messagePlayer.release()
            rewakePlayer.stop()
            RoutineRepository.sessionId?.let { sid ->
                ApiClient.scope.launch {
                    RespecApi.endMorning(sid)?.let { v -> withContext(Dispatchers.Main) { RoutineRepository.applyView(v) } }
                }
            }
        }
    }

    LaunchedEffect(muted) { client.setMuted(muted) }
    LaunchedEffect(speakerOn) { client.setSpeaker(speakerOn) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed += 1
        }
    }

    // 배너는 3.5초 뒤 사라짐
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3500)
            banner = null
        }
    }

    // 알람 후 첫 음성 응답 기록 (반응 시간 계산용, 서버가 처음 한 번만 저장)
    LaunchedEffect(lines, sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!responded && lines.any { it.role == LiveClient.Role.User }) {
            responded = true
            RespecApi.firstResponse(sid)
        }
    }

    // ---- 재기상: AI가 말을 마친 뒤 8초 무응답이면 음악 + POST /rewake. 사용자가 말하면(버튼 포함) 멈춤 ----
    val lastRole = lines.lastOrNull()?.role
    LaunchedEffect(stage, status, aiSpeaking, lastRole, wakeChecked, rewaking, sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (stage != Stage.Briefing || status != LiveClient.Status.Live || aiSpeaking || wakeChecked || rewaking) return@LaunchedEffect
        if (lastRole != LiveClient.Role.Assistant) return@LaunchedEffect
        delay(REWAKE_SILENCE_MS)
        rewaking = true
        scope.launch {
            // 밤에 받아 둔 파일 → 서버 URL → (둘 다 없으면) 기기 기본 알람음
            val source = MusicStore.rewakeFile(context)?.absolutePath ?: RespecApi.musicUrl("rewake")
            if (rewaking) rewakePlayer.start(level = REWAKE_LEVEL, soundUrl = source)
            RespecApi.rewake(sid)
        }
    }
    LaunchedEffect(lastRole, stage, rewaking) {
        if (rewaking && (lastRole == LiveClient.Role.User || stage != Stage.Briefing)) {
            rewakePlayer.stop()
            rewaking = false
        }
    }

    // ---- 아침 대화 진행 ----
    LaunchedEffect(Unit) {

        // 판정 결과 반영: 새로 완료된 단계가 있으면 배너
        fun applyStepView(v: JSONObject) {
            val before = items.filter { it.done }.mapNotNull { it.stepOrder }.toSet()
            RoutineRepository.applyView(v)
            val newly = items.firstOrNull { it.done && it.stepOrder !in before }
            if (newly != null) banner = Banner("${items.count { it.done }}번째 루틴 완료!", newly.title)
        }

        client.onToolCall = { call ->
            when (call.name) {
                "report_step_result" -> {
                    val sid = RoutineRepository.sessionId
                    val order = call.args.optInt("step_order", -1)
                    val verdict = call.args.optString("verdict")
                    if (sid != null && order > 0 && verdict.isNotBlank()) {
                        RespecApi.reportVerdict(sid, order, verdict)
                            ?.let { v -> withContext(Dispatchers.Main) { applyStepView(v) } }
                    }
                    JSONObject().put("result", "ok")
                }
                // 기상 확인 멘트를 모두 마침 → 서버가 기상 성공 여부 판정
                "wake_check_done" -> {
                    withContext(Dispatchers.Main) { wakeChecked = true }
                    RoutineRepository.sessionId?.let { sid ->
                        RespecApi.wakeCheck(sid)?.let { v -> withContext(Dispatchers.Main) { RoutineRepository.applyView(v) } }
                    }
                    JSONObject().put("result", "ok")
                }
                // 코칭 중 "오늘 스트레칭 좀 적게" → 오늘만 단계 시간 변경 (0 = 오늘만 빼기)
                "adjust_step_time" -> {
                    val sid = RoutineRepository.sessionId
                    val order = call.args.optInt("step_order", -1)
                    val sec = call.args.optInt("duration_sec", -1)
                    if (sid != null && order > 0 && sec >= 0) {
                        RespecApi.changeStepDuration(sid, order, sec)
                            ?.let { v -> withContext(Dispatchers.Main) { RoutineRepository.applyView(v) } }
                    }
                    JSONObject().put("result", "ok")
                }
                // 응답을 보내지 않음: 앱이 세션을 닫거나 다음 세션으로 넘어감
                "start_routine", "end_session" -> {
                    toolEvents.trySend(call.name)
                    null
                }
                else -> JSONObject().put("result", "ok")
            }
        }

        // 어젯밤 남긴 한 마디: 서버 음성(서명 URL)을 먼저 시도하고, 안 되면 기기 파일로 재생
        suspend fun playLastNightMessage() {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val applyDate = NightMessageStore.applyDate(context)
            if (applyDate != null && applyDate != today) return       // 오늘 아침용 메시지가 아님

            val url = NightMessageStore.recordId(context)
                ?.let { id -> withTimeoutOrNull(5000) { RespecApi.nightRecord(id) } }
                ?.takeUnless { it.isNull("voice_url") }
                ?.optString("voice_url")
                ?.takeIf { it.isNotBlank() }
            val file = NightMessageStore.latestMessage(context)
            if (url == null && file == null) return

            stage = Stage.Message
            notice = "어젯밤의 내가 남긴 한 마디"
            val done = CompletableDeferred<Unit>()
            messageDone[0] = done
            val onEnd: () -> Unit = { done.complete(Unit) }
            var ok = url != null && messagePlayer.loadUrlAsync(url, onEnd)
            if (!ok && file != null) ok = messagePlayer.load(file, onEnd)
            if (ok) {
                messagePlayer.play()
                done.await()
            }
            messagePlayer.release()
            messageDone[0] = null
        }

        // 1) 오늘 세션 (수신 화면에서 만들었으면 그대로, 아니면 지금 만듦)
        val started = initialSession ?: run {
            val local = AlarmRepository.get(alarmId)
                ?: AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE && it.enabled }
            RespecApi.startMorningFor(local?.hour, local?.minute)
        }
        if (started == null) {
            stage = Stage.Finished
            notice = ApiClient.lastError ?: "서버에서 알람을 찾지 못했어요. 알람을 먼저 만들어 주세요."
            return@LaunchedEffect
        }
        RoutineRepository.applyView(started)
        val sid = RoutineRepository.sessionId ?: return@LaunchedEffect
        sessionId = sid

        // 2) 어젯밤 한 마디
        playLastNightMessage()

        // 3) 브리핑 세션
        micReady.await()
        stage = Stage.Briefing
        notice = "리스펙과 연결하는 중…"
        client.start { RespecApi.briefingToken(sid) ?: RespecApi.tokenError() }

        // 4) 함수 호출에 따라 다음 세션으로 넘어가거나 종료
        for (event in toolEvents) {
            if (event == "end_session") {
                client.awaitSpeechEnd()      // 작별 인사를 끝까지 들려준 뒤 닫음
                client.stop()
                break
            }

            // start_routine: 지금 세션을 닫고 새 토큰으로 코칭(콘텐츠 → 기본 루틴 순) 세션 시작
            client.awaitSpeechEnd()
            client.stop()
            stage = Stage.Coaching
            notice = "루틴을 준비하는 중…"
            val first = RespecApi.coachingToken(sid)
            val firstAuth = first.auth
            if (firstAuth == null) {
                notice = when (first.code) {
                    "COACHING_SKIPPED" -> "오늘은 루틴을 쉬어 가는 날이에요. 좋은 하루 보내세요!"
                    "ALL_STEPS_CHECKED" -> "오늘 루틴을 이미 모두 마쳤어요. 정말 멋져요!"
                    else -> "루틴을 시작하지 못했어요. 루틴 탭에서 직접 체크해 주세요."
                }
                break
            }
            first.steps?.let { RoutineRepository.applySteps(it) }

            // 첫 연결은 받아 둔 토큰, 재연결은 서버가 남은 단계만 넣은 새 토큰
            var pending: LiveAuth? = firstAuth
            client.start {
                pending?.also { pending = null } ?: RespecApi.coachingToken(sid).let { r ->
                    r.steps?.let { steps -> withContext(Dispatchers.Main) { RoutineRepository.applySteps(steps) } }
                    r.auth ?: RespecApi.tokenError()
                }
            }
        }

        stage = Stage.Finished
        delay(2500)
        onFinish()
    }

    fun skipMessage() {
        if (stage == Stage.Message) messageDone[0]?.complete(Unit)
    }

    BackHandler { onFinish() }

    val live = status == LiveClient.Status.Live
    val phase = when {
        stage == Stage.Message -> MPhase.Playing
        live && !aiSpeaking -> MPhase.Listening
        else -> MPhase.AiSpeaking
    }
    val subtitle = when {
        stage == Stage.Message || stage == Stage.Preparing || stage == Stage.Finished -> notice
        status == LiveClient.Status.Error -> liveMessage ?: "연결이 끊겼어요"
        status == LiveClient.Status.Reconnecting -> "연결이 잠시 끊겨서 다시 연결하는 중…"
        else -> lines.lastOrNull { it.role == LiveClient.Role.Assistant }?.text ?: notice
    }
    val heard = lines.lastOrNull()?.takeIf { it.role == LiveClient.Role.User }?.text.orEmpty()
    val doneCount = items.count { it.done }
    val currentTitle = items.firstOrNull { !it.done && !it.skipped }?.title

    // ---- UI ----
    Box(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            MorningHeader(elapsed)
            Spacer(Modifier.height(8.dp))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GaugeRing(
                        total = items.size,
                        done = doneCount,
                        modifier = Modifier.size(290.dp).aspectRatio(1f)
                    ) {
                        val tint = when (phase) {
                            MPhase.AiSpeaking -> RespecColors.Lime
                            MPhase.Playing -> RespecColors.Purple
                            MPhase.Listening -> RespecColors.Mint
                        }
                        Column(
                            Modifier
                                .size(228.dp)
                                .clip(CircleShape)
                                .background(RespecColors.Card)
                                .clickable(enabled = phase == MPhase.Playing) { skipMessage() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                when (phase) {
                                    MPhase.Listening -> Icons.Filled.Mic
                                    MPhase.Playing -> Icons.Filled.PlayArrow
                                    MPhase.AiSpeaking -> Icons.Filled.WbSunny
                                },
                                null,
                                tint = tint,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "$doneCount / ${items.size}",
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text("루틴 완료", color = RespecColors.TextSub, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            phase == MPhase.Playing -> "어젯밤의 내가 남긴 한 마디 · 탭하면 건너뛰기"
                            phase == MPhase.Listening -> "듣고 있어요"
                            stage == Stage.Coaching && currentTitle != null -> "지금은 · $currentTitle"
                            stage == Stage.Briefing -> "아침 브리핑"
                            else -> "리스펙이 이야기하는 중"
                        },
                        color = RespecColors.TextSub,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 크기가 고정된 자막 박스 (AI 음성 받아쓰기)
            SubtitleBox(subtitle)
            Spacer(Modifier.height(12.dp))

            // 대답 영역
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                if (phase == MPhase.Listening) {
                    val (laterText, doneText) =
                        if (stage == Stage.Coaching) BTN_LATER to BTN_DONE else BTN_WAIT to BTN_READY
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when {
                                heard.isNotEmpty() -> "들은 말: “$heard”"
                                muted -> "음소거 중이에요. 아래 버튼으로 대답해 주세요"
                                else -> "편하게 말씀해 주세요. 어려우면 아래 버튼을 눌러도 돼요"
                            },
                            color = if (heard.isNotEmpty()) Color.White else RespecColors.TextSub,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { client.sendText(laterText) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(1.5.dp, RespecColors.Purple)
                            ) {
                                Text(laterText, color = RespecColors.Purple, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { client.sendText(doneText) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RespecColors.Lime,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(doneText, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                } else {
                    Text(
                        if (stage == Stage.Finished) "통화 종료 버튼을 눌러 마칠 수 있어요"
                        else "리스펙의 이야기가 끝나면 바로 대답할 수 있어요",
                        color = RespecColors.TextSub,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                MorningRoundControl(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = "음소거",
                    active = muted,
                    onClick = { muted = !muted }
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(RespecColors.Danger)
                            .clickable { onFinish() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CallEnd, "통화 종료", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("통화 종료", color = RespecColors.TextSub, fontSize = 13.sp)
                }
                MorningRoundControl(
                    icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    label = "스피커",
                    active = !speakerOn,
                    onClick = { speakerOn = !speakerOn }
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 배너는 레이아웃에 영향을 주지 않도록 위에 겹쳐서 표시
        val shown = banner
        AnimatedVisibility(
            visible = shown != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp)
        ) {
            if (shown != null) BannerCard(shown)
        }
    }
}

/** 루틴 개수만큼 나뉜 원형 게이지. 완료할 때마다 한 칸씩 라임색으로 채워짐 */
@Composable
private fun GaugeRing(
    total: Int,
    done: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val animated by animateFloatAsState(done.toFloat(), tween(800), label = "gauge")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (total <= 0) return@Canvas
            val stroke = 12.dp.toPx()
            val gap = 8f
            val slice = 360f / total
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)

            for (i in 0 until total) {
                val fill = (animated - i).coerceIn(0f, 1f)
                val start = -90f + i * slice + gap / 2
                val sweep = slice - gap
                if (fill > 0f) {
                    // 은은한 빛
                    drawArc(
                        color = RespecColors.Lime.copy(alpha = 0.22f * fill),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    color = lerp(RespecColors.Disabled, RespecColors.Lime, fill),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

@Composable
private fun MorningHeader(elapsed: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(RespecColors.Mint))
            Spacer(Modifier.width(8.dp))
            Text("리스펙", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(
            "AI 모닝콜 통화 중 · %02d:%02d".format(elapsed / 60, elapsed % 60),
            color = RespecColors.TextSub,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun BannerCard(banner: Banner) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Lime.copy(alpha = 0.12f))
            .border(1.dp, RespecColors.Lime.copy(alpha = 0.4f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(RespecColors.Lime),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Star, null, tint = Color.Black, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(banner.title, color = RespecColors.Lime, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(banner.sub, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MorningRoundControl(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) Color.Black else Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = RespecColors.TextSub, fontSize = 13.sp)
    }
}