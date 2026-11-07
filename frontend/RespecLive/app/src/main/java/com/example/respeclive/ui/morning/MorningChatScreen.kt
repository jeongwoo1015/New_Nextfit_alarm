package com.example.respeclive.ui.morning

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.respeclive.audio.AiSpeaker
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.audio.CallAudio
import com.example.respeclive.audio.MessagePlayer
import com.example.respeclive.audio.VoiceTranscriber
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.CalendarEvents
import com.example.respeclive.data.DeviceLocation
import com.example.respeclive.data.MusicStore
import com.example.respeclive.data.NightMessageStore
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.live.LiveAuth
import com.example.respeclive.live.LiveClient
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.common.SubtitleBox
import com.example.respeclive.ui.common.rememberHeldUserLine
import com.example.respeclive.ui.common.rememberScreenScale
import com.example.respeclive.ui.common.rememberSpokenPage
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
private enum class Stage { Preparing, Message, Briefing, Coaching, Wrapup, Finished }   // Wrapup: 마무리 안내
private data class Banner(val title: String, val sub: String)

// 말 대신 누를 수 있는 대답 (Live에 사용자 글자 입력으로 보냄). 루틴 단계: 건너뛰기 / 완료
private const val BTN_DONE = "완료했어요"

/** 루틴 게이지 최대 크기 (자리가 모자라면 줄어듦) */
private val RING_SIZE = 240.dp
private const val BTN_LATER = "오늘은 건너뛸게요"

/** 앱이 보내는 이벤트: 코칭 중 모든 단계를 판정함 */
private const val ROUTINE_OVER = "routine_over"

/** 이미 루틴을 다 마친 날 브리핑 시작 신호에 덧붙임: 루틴 안내·시작 질문을 하지 않게 함 */
private const val ALREADY_DONE_NOTE = "\n(앱 안내: 사용자는 오늘 루틴을 이미 모두 마쳤습니다. 브리핑에서 루틴이나 콘텐츠를 안내하거나 " +
        "시작할 준비가 됐는지 묻지 말고, start_routine도 호출하지 마세요. 브리핑 마지막에는 오늘 루틴을 이미 다 마쳤다고 짧게 칭찬하고, " +
        "사용자가 끝내겠다고 하면 작별 인사 뒤 end_session을 호출해 주세요.)"

/** 어젯밤 메시지를 재생하기 직전의 안내 (AI 목소리) */
private const val MESSAGE_INTRO = "그전에 어젯밤 남긴 한 마디를 들려 드릴게요."

/** AI 질문 뒤 이 시간 동안 대답이 없으면 재기상 음악 (브리핑의 기상 확인 전까지만) */
private const val REWAKE_SILENCE_MS = 8_000L
private const val REWAKE_LEVEL = 3

/**
 * 아침 대화 (Gemini Live). 지시문·함수는 서버가 토큰에 고정한다 (ai/live, AI CONTRACTS 2절).
 * 세션 시작 → 브리핑 세션(시작 질문에 대답) → 안내 후 어젯밤 한 마디 → start_routine이면 코칭 세션(새 토큰) → 종료
 * 이미 다 마친 날 다시 열면 브리핑에서 루틴을 안내하지 않음
 * initialSession: 알람 수신 화면에서 이미 만든 세션 (없으면 여기서 만듦)
 */
@Composable
fun MorningChatScreen(alarmId: Int, onFinish: () -> Unit, initialSession: JSONObject? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val client = remember { LiveClient(context) }
    val messagePlayer = remember { MessagePlayer() }
    val speaker = remember { AiSpeaker(context) }     // Live 연결이 끊겼을 때만 마무리 안내를 대신 읽음
    val rewakePlayer = remember { AlarmSoundPlayer(context) }
    val messageDone = remember { arrayOfNulls<CompletableDeferred<Unit>>(1) }
    val toolEvents = remember { Channel<String>(Channel.UNLIMITED) }   // start_routine / end_session
    val micReady = remember { CompletableDeferred<Unit>() }
    val items = RoutineRepository.items

    val status by client.status.collectAsState()
    val aiSpeaking by client.aiSpeaking.collectAsState()
    val lines by client.transcript.collectAsState()
    val liveMessage by client.message.collectAsState()
    val spoken by client.spokenFraction.collectAsState()

    var stage by remember { mutableStateOf(Stage.Preparing) }
    var notice by remember { mutableStateOf("리스펙이 준비하는 중…") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var responded by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Banner?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var wakeChecked by remember { mutableStateOf(false) }   // 확인 질문에 대답함 → 재기상 음악 더 이상 안 틂
    var rewaking by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf<String?>(null) }   // 어젯밤 한 마디를 받아쓴 글자
    var wrapText by remember { mutableStateOf("") }        // 마무리 안내 문장
    var liveSaying by remember { mutableStateOf(false) }   // 마무리 안내를 AI 목소리로 읽는 중 (자막은 AI가 실제로 한 말)
    var coachingStarted by remember { mutableStateOf(false) }   // 이번 통화의 단계를 받아 코칭 세션을 열었음
    var coachSpoke by remember { mutableStateOf(false) }        // 코칭 세션에서 AI가 첫 안내를 시작함

    LaunchedEffect(coachingStarted, aiSpeaking) {
        if (coachingStarted && aiSpeaking) coachSpoke = true
    }

    // ---- 마이크·위치 권한: 받기 전에 Live를 열면 마이크가 열리지 않으므로 결과를 기다렸다가 연결 ----
    // (위치는 없어도 브리핑은 됨, 날씨만 빠짐)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == false) {
            Toast.makeText(context, "마이크 권한이 없으면 버튼으로 대답해 주세요", Toast.LENGTH_SHORT).show()
        }
        micReady.complete(Unit)
    }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) micReady.complete(Unit) else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---- 정리 + 화면 켜짐 유지. 화면을 벗어나면 서버에 세션 종료 기록 ----
    // 통화 볼륨으로 통일: 브리핑·코칭 사이와 마지막 한 마디 재생 중에도 통화 모드 유지
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        CallAudio.acquire(context)
        CallAudio.volumeKeys(context, AudioManager.STREAM_VOICE_CALL)
        onDispose {
            view.keepScreenOn = false
            CallAudio.release(context)
            CallAudio.volumeKeys(context, AudioManager.USE_DEFAULT_STREAM_TYPE)
            client.release()
            speaker.shutdown()
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

    // 브리핑 확인 질문("잘 주무셨어요?")에 대한 첫 대답(말 또는 버튼): 첫 응답 기록 → 기상 확인 (AI CONTRACTS 6-1).
    // AI는 wake_check_done을 부르지 않으므로 앱이 판정 시점을 알린다. 그 뒤로는 재기상 음악을 틀지 않는다.
    LaunchedEffect(lines, sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!responded && lines.any { it.role == LiveClient.Role.User }) {
            responded = true
            wakeChecked = true
            RespecApi.firstResponse(sid)
            RespecApi.wakeCheck(sid)?.let { RoutineRepository.applyView(it) }
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
                // 루틴 코칭 중 "오늘 스트레칭 좀 적게" → 오늘만 단계 시간 변경 (seconds: 30초 단위, 0 = 오늘만 빼기)
                "adjust_step_time" -> {
                    val sid = RoutineRepository.sessionId
                    val order = call.args.optInt("step_order", -1)
                    val sec = call.args.optInt("seconds", -1)
                    val view = if (sid != null && order > 0 && sec >= 0) RespecApi.changeStepDuration(sid, order, sec) else null
                    if (view != null) {
                        withContext(Dispatchers.Main) { RoutineRepository.applyView(view) }
                        JSONObject().put("result", "ok")
                    } else {
                        // 거절(30초 단위 아님, 합계 초과 등) 응답 형식은 팀 합의 전 (AI CONTRACTS B7의 예시 형식)
                        JSONObject().put("result", "error").put("reason", ApiClient.lastErrorCode ?: "INVALID_REQUEST")
                    }
                }
                // 응답을 보내지 않음: 앱이 세션을 닫거나 다음 세션으로 넘어감
                "start_routine", "end_session" -> {
                    toolEvents.trySend(call.name)
                    null
                }
                else -> JSONObject().put("result", "ok")
            }
        }

        // 어젯밤 남긴 한 마디 찾기: 서버 음성(서명 URL)과 기기 파일. 오늘 아침용이 없으면 null
        suspend fun findLastNightMessage(): Pair<String?, java.io.File?>? {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val applyDate = NightMessageStore.applyDate(context)
            if (applyDate != null && applyDate != today) return null       // 오늘 아침용 메시지가 아님

            val url = NightMessageStore.recordId(context)
                ?.let { id -> withTimeoutOrNull(5000) { RespecApi.nightRecord(id) } }
                ?.takeUnless { it.isNull("voice_url") }
                ?.optString("voice_url")
                ?.takeIf { it.isNotBlank() }
            val file = NightMessageStore.latestMessage(context)
            return if (url == null && file == null) null else url to file
        }

        // 서버 음성을 먼저 시도하고, 안 되면 기기 파일로 재생
        suspend fun playLastNightMessage(found: Pair<String?, java.io.File?>) {
            val (url, file) = found

            stage = Stage.Message
            notice = "어젯밤의 내가 남긴 한 마디"
            // 밤에 받아쓴 글자. 없으면 기기 파일을 지금 받아씀 (서버 음성만 있으면 글자 없이 재생)
            messageText = NightMessageStore.latestText(context)
            if (messageText == null && file != null) {
                launch { VoiceTranscriber.transcribe(context, file)?.let { if (stage == Stage.Message) messageText = it } }
            }
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

        // 마무리 안내: 연결된 AI 목소리로 그대로 읽게 하고, 연결이 없으면 기기 TTS
        suspend fun say(text: String) {
            stage = Stage.Wrapup
            wrapText = text
            liveSaying = true
            val byLive = client.speakExact(text)
            liveSaying = false
            if (byLive) client.transcript.value.getOrNull(client.exactLineIndex)?.text?.let { wrapText = it }
            else speaker.speakAndWait(text)
        }

        // 1) 오늘 세션 (수신 화면에서 만들었으면 그대로, 아니면 지금 만듦)
        val started = initialSession ?: run {
            val local = AlarmRepository.get(alarmId)
                ?: AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE && it.enabled }
            RespecApi.startMorningFor(local)
        }
        if (started == null) {
            stage = Stage.Finished
            notice = ApiClient.lastError ?: "서버에서 알람을 찾지 못했어요. 알람을 먼저 만들어 주세요."
            return@LaunchedEffect
        }
        RoutineRepository.applyView(started)
        if (initialSession == null) launch { RoutineRepository.loadPlan() }   // 수신 화면을 거치지 않았으면 여기서
        val sid = RoutineRepository.sessionId ?: return@LaunchedEffect
        sessionId = sid
        val messageJob = async { findLastNightMessage() }

        // 2) 브리핑 세션. 이미 루틴을 다 마친 날이면 루틴 안내를 하지 않도록 시작 신호에 덧붙임
        // 이번 세션의 서버 단계로만 판단 (단계가 아직 없으면 처음 시작)
        val sessionSteps = items.filter { it.stepOrder != null }
        val doneAtStart = sessionSteps.isNotEmpty() && sessionSteps.all { it.done }
        micReady.await()
        val placeJob = async { DeviceLocation.lastKnown(context) }   // 재연결 때마다 다시 찾지 않도록 한 번만
        stage = Stage.Briefing
        notice = "리스펙과 연결하는 중…"
        client.start {
            // 오늘 일정은 폰 캘린더에서 (권한은 밤 대화에서 받음. 없으면 빈 일정), 날씨용 위치는 마지막으로 알던 위치
            val auth = RespecApi.briefingToken(sid, CalendarEvents.today(context), placeJob.await())
                ?: RespecApi.tokenError()
            if (doneAtStart) LiveAuth(auth.token, auth.wsUrl, auth.model, auth.startSignal + ALREADY_DONE_NOTE) else auth
        }

        // 3) 함수 호출에 따라 다음 세션으로 넘어가거나 종료
        val message = messageJob.await()
        var messageHeard = false
        for (event in toolEvents) {
            client.awaitSpeechEnd()      // AI의 마지막 말을 끝까지 들려줌. 연결은 마무리 안내까지 유지
            client.pauseMic()

            // 브리핑이 끝나면(루틴을 시작하든 통화를 끝내든) 안내 후 어젯밤 한 마디를 먼저 들려줌
            if (!messageHeard) {
                messageHeard = true
                if (message != null) {
                    say(MESSAGE_INTRO)
                    playLastNightMessage(message)
                }
            }

            // end_session, 또는 루틴의 모든 단계를 판정함(routine_over): 바로 마무리
            if (event == "end_session" || event == ROUTINE_OVER) break

            // start_routine: 코칭 토큰을 받으면 지금 세션을 닫고 새 토큰으로 코칭(콘텐츠 → 기본 루틴 순) 세션 시작
            stage = Stage.Coaching
            notice = if (RoutineRepository.session?.isContent == true) "콘텐츠를 준비하는 중…" else "루틴을 준비하는 중…"
            val first = RespecApi.coachingToken(sid)
            val firstAuth = first.auth
            if (firstAuth == null) {
                first.steps?.let { RoutineRepository.applySteps(it) }
                if (first.code != "ALL_STEPS_CHECKED") {      // 다 마친 날은 아래에서 완수 안내
                    say(
                        if (first.code == "COACHING_SKIPPED") "오늘은 루틴을 쉬어 가는 날이에요. 좋은 하루 보내세요!"
                        else "루틴을 시작하지 못했어요. 루틴 탭에서 직접 체크해 주세요."
                    )
                }
                break
            }
            client.stop()
            first.steps?.let { RoutineRepository.applySteps(it) }
            coachingStarted = true

            // 첫 연결은 받아 둔 토큰, 재연결은 서버가 남은 단계만 넣은 새 토큰
            var pending: LiveAuth? = firstAuth
            client.start {
                pending?.also { pending = null } ?: RespecApi.coachingToken(sid).let { r ->
                    r.steps?.let { steps -> withContext(Dispatchers.Main) { RoutineRepository.applySteps(steps) } }
                    r.auth ?: RespecApi.tokenError()
                }
            }
        }

        client.stop()

        stage = Stage.Finished
        notice = "좋은 하루 보내세요!"
        delay(1000)
        onFinish()
    }

    fun skipMessage() {
        if (stage == Stage.Message) messageDone[0]?.complete(Unit)
    }

    BackHandler { onFinish() }

    val live = status == LiveClient.Status.Live
    // 루틴·콘텐츠 준비 중: 코칭 세션의 AI가 첫 안내를 시작하기 전 (아직 대답할 것이 없음)
    val preparing = stage == Stage.Coaching && !coachSpoke
    val phase = when {
        stage == Stage.Message -> MPhase.Playing
        stage == Stage.Wrapup || preparing -> MPhase.AiSpeaking
        live && !aiSpeaking -> MPhase.Listening
        else -> MPhase.AiSpeaking
    }
    // AI가 바로 대답해도 사용자 말을 잠깐 더 보여 줌
    val heldUser = rememberHeldUserLine(lines).takeIf { live && (stage == Stage.Briefing || stage == Stage.Coaching) }
    val lastLine = heldUser ?: lines.lastOrNull()
    val subtitleFromUser = when (stage) {
        Stage.Message -> messageText != null
        Stage.Briefing, Stage.Coaching -> live && !preparing && lastLine?.role == LiveClient.Role.User
        else -> false
    }
    // AI가 말하는 줄: 지금 말하는 박스를 통째로 먼저 보여 줌
    val spokenLine = when {
        stage == Stage.Wrapup && liveSaying -> lines.getOrNull(client.exactLineIndex)
        (stage == Stage.Briefing || stage == Stage.Coaching) && !preparing && live && lastLine?.role == LiveClient.Role.Assistant -> lastLine
        else -> null
    }
    val spokenPage = spokenLine?.let { rememberSpokenPage(it.atMs, it.text, spoken) }
    val subtitle = when {
        spokenPage != null -> spokenPage.text
        stage == Stage.Message -> messageText?.let { "“$it”" } ?: notice
        stage == Stage.Wrapup && liveSaying -> ""
        stage == Stage.Wrapup -> wrapText
        stage == Stage.Preparing || stage == Stage.Finished -> notice
        status == LiveClient.Status.Error -> liveMessage ?: "연결이 끊겼어요"
        status == LiveClient.Status.Reconnecting -> "연결이 잠시 끊겨서 다시 연결하는 중…"
        preparing -> notice
        else -> lastLine?.text ?: notice
    }
    // 게이지·개수는 이번 통화에서 코칭을 시작한 뒤에만 (브리핑·메시지 중에는 표시 안 함)
    val showRoutine = coachingStarted && RoutineRepository.hasSteps
    val doneCount = items.count { it.done }
    val currentTitle = items.firstOrNull { !it.done && !it.skipped }?.title
    // 루틴을 모두 판정했으면 완료/건너뛰기 버튼을 숨기고, 코칭을 끌지 않고 바로 마무리로
    val routineOver = stage == Stage.Coaching && items.isNotEmpty() && currentTitle == null
    LaunchedEffect(routineOver) { if (routineOver) toolEvents.trySend(ROUTINE_OVER) }

    // ---- UI ----
    val scale = rememberScreenScale()   // 세로가 짧은 폰에서 버튼·영역 크기를 줄임
    Box(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            MorningHeader(elapsed)
            Spacer(Modifier.height(8.dp))

            // 세로가 짧은 폰에서는 자막·버튼과 겹치지 않도록 남은 공간에 맞춰 줄임 (아래 상태 문구 자리 30dp)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ring = minOf(RING_SIZE, maxHeight - 30.dp, maxWidth).coerceAtLeast(0.dp)
                val k = ring / RING_SIZE
                val tint = when (phase) {
                    MPhase.AiSpeaking -> RespecColors.AccentText
                    MPhase.Playing -> RespecColors.Mint
                    MPhase.Listening -> RespecColors.Purple
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                        // 코칭 중에는 루틴 게이지만, 그 전(브리핑·메시지)에는 파동과 테두리
                        if (!showRoutine) OrbHalo(ring, tint)
                        GaugeRing(
                            total = if (showRoutine) items.size else 0,
                            done = doneCount,
                            stroke = 12.dp * k,
                            modifier = Modifier.size(ring)
                        ) {
                            Column(
                                Modifier
                                    .size(ring * (if (showRoutine) 188 else 156) / 240)
                                    .clip(CircleShape)
                                    .background(RespecColors.Card)
                                    .border(2.dp, if (showRoutine) Color.Transparent else tint.copy(alpha = 0.7f), CircleShape)
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
                                    modifier = Modifier.size(if (showRoutine) 44.dp * k else ring * 64 / 230)
                                )
                                if (showRoutine) {
                                    Spacer(Modifier.height(4.dp * k))
                                    Text(
                                        "$doneCount / ${items.size}",
                                        color = RespecColors.Text,
                                        fontSize = 30.sp * k,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    FitText("루틴 완료", color = RespecColors.TextSub, fontSize = 13.sp * k, minSize = 8.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FitText(
                        when {
                            phase == MPhase.Playing -> "어젯밤의 내가 남긴 한 마디 · 탭하면 건너뛰기"
                            preparing -> "곧 시작해요"
                            phase == MPhase.Listening -> "듣고 있어요"
                            stage == Stage.Coaching && currentTitle != null -> "지금은 · $currentTitle"
                            stage == Stage.Briefing -> "아침 브리핑"
                            else -> "리스펙이 이야기하는 중"
                        },
                        color = if (showRoutine) RespecColors.TextSub else tint,
                        fontSize = if (showRoutine) 13.sp else 14.sp,
                        fontWeight = if (showRoutine) FontWeight.Normal else FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 크기가 고정된 자막 박스 (AI 음성 받아쓰기)
            SubtitleBox(subtitle, fromUser = subtitleFromUser, pageKey = spokenPage?.key)
            Spacer(Modifier.height(12.dp))

            // 대답 버튼: 대답을 기다릴 때만 (말 대신 누르기). 시작 전(preparing은 Listening이 아님)과 루틴을 다 마쳤으면 숨김
            val answers = when {
                phase == MPhase.Listening && !routineOver && stage == Stage.Coaching -> BTN_LATER to BTN_DONE
                else -> null
            }
            Box(Modifier.fillMaxWidth().height(64.dp * scale), contentAlignment = Alignment.Center) {
                if (answers != null) {
                    val (laterText, doneText) = answers
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { client.sendText(laterText) },
                                modifier = Modifier.weight(1f).height(52.dp * scale),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(1.5.dp, RespecColors.Purple)
                            ) {
                                FitText(laterText, color = RespecColors.Purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { client.sendText(doneText) },
                                modifier = Modifier.weight(1f).height(52.dp * scale),
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RespecColors.Lime,
                                    contentColor = RespecColors.OnAccent
                                )
                            ) {
                                FitText(doneText, color = RespecColors.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top
            ) {
                MorningRoundControl(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = "음소거",
                    size = 64.dp * scale,
                    active = muted,
                    onClick = { muted = !muted }
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(76.dp * scale)
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

/** 가운데 원을 둘러싸는 파동: 바깥 테두리와 옅은 색 원이 천천히 커졌다 작아짐 (밤 대화와 같은 모양) */
@Composable
private fun OrbHalo(size: Dp, tint: Color) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        Modifier
            .size(size * 216 / 230)
            .scale(pulse)
            .border(2.dp, tint.copy(alpha = 0.35f), CircleShape)
    )
    Box(
        Modifier
            .size(size * 184 / 230)
            .scale(pulse)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.08f))
    )
}

/** 루틴 개수만큼 나뉜 원형 게이지. 완료할 때마다 한 칸씩 라임색으로 채워짐 */
@Composable
private fun GaugeRing(
    total: Int,
    done: Int,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val animated by animateFloatAsState(done.toFloat(), tween(800), label = "gauge")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (total <= 0) return@Canvas
            val stroke = stroke.toPx()
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
                        color = RespecColors.AccentText.copy(alpha = 0.22f * fill),
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
            Text("리스펙", color = RespecColors.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
        FitText(
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
            Icon(Icons.Filled.Star, null, tint = RespecColors.OnAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(banner.title, color = RespecColors.AccentText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(banner.sub, color = RespecColors.Text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MorningRoundControl(icon: ImageVector, label: String, size: Dp, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) RespecColors.Text else RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) RespecColors.Background else RespecColors.Text)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = RespecColors.TextSub, fontSize = 13.sp)
    }
}