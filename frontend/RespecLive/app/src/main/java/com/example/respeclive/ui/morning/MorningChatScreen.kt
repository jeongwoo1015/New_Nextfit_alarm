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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.audio.CallAudio
import com.example.respeclive.audio.MessagePlayer
import com.example.respeclive.audio.VoiceTranscriber
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.CalendarEvents
import com.example.respeclive.data.CoachingToken
import com.example.respeclive.data.DeviceLocation
import com.example.respeclive.data.FortuneToken
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
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
private enum class Stage { Preparing, Message, Briefing, Fortune, Coaching, Wrapup, Finished }   // Wrapup: 마무리 안내
private data class Banner(val title: String, val sub: String)

// 말 대신 누를 수 있는 대답 (Live에 사용자 글자 입력으로 보냄). 루틴 단계: 건너뛰기 / 완료
private const val BTN_DONE = "완료했어요"

/** 루틴 게이지 최대 크기 (자리가 모자라면 줄어듦) */
private val RING_SIZE = 240.dp
private const val BTN_LATER = "오늘은 건너뛸게요"

/** 퀴즈 답에 O / X로 반응하는 시간 */
private const val QUIZ_REACTION_MS = 2800L

/** 앱이 보내는 이벤트: 코칭 중 모든 단계를 판정함 */
private const val ROUTINE_OVER = "routine_over"

/** 이미 루틴을 다 마친 날 브리핑 시작 신호에 덧붙임: 루틴 안내·시작 질문을 하지 않게 함 */
private const val ALREADY_DONE_NOTE = "\n(앱 안내: 사용자는 오늘 루틴을 이미 모두 마쳤습니다. 브리핑에서 루틴이나 콘텐츠를 안내하거나 " +
        "시작할 준비가 됐는지 묻지 말고, start_routine도 호출하지 마세요. 브리핑 마지막에는 오늘 루틴을 이미 다 마쳤다고 짧게 칭찬하고, " +
        "사용자가 끝내겠다고 하면 작별 인사 뒤 end_session을 호출해 주세요.)"

/**
 * 브리핑 마무리 질문을 '한마디·포춘부터 들어 볼까요?'로 바꿈. 브리핑 뒤에는 루틴이 아니라 한마디 → 포춘이 먼저 나오므로,
 * 여기서 루틴 준비를 묻고 "시작할게요"라고 하면 바로 한마디·포춘으로 넘어가 어색함. 루틴·콘텐츠 안내는 코칭 세션의 첫 말이 맡음.
 * what: "어젯밤 남긴 한마디와 오늘의 포춘" 또는 "오늘의 포춘"
 */
private fun listenFirstNote(what: String) = "\n(앱 안내: 브리핑 다음에는 ${what}을 먼저 듣고, 그 뒤에 루틴이나 콘텐츠를 시작합니다. " +
        "그래서 브리핑 마지막 질문은 루틴이나 콘텐츠를 시작할 준비가 됐는지가 아니라, ${what}부터 들어 볼지 묻는 한 문장으로 " +
        "말투 설정에 맞춰 해 주세요(예: \"그럼 먼저 ${what}부터 들어 볼까요?\"). 사용자가 좋다고 하면 \"같이 들어 봐요\"처럼 한마디로만 반응한 뒤 " +
        "start_routine을 호출하고, '시작할게요'처럼 루틴을 바로 시작하는 말이나 루틴 안내는 하지 마세요.)"

/**
 * 한마디·포춘 뒤 코칭 세션의 첫 연결 시작 신호에 덧붙임: 들은 것에서 루틴·콘텐츠로 넘어가는 한 문장을 먼저 하게 함
 * (없으면 포춘이 끝나자마자 1단계 안내가 나와 어색함). heard: "오늘의 포춘을" / "어젯밤 남긴 한마디를"
 */
private fun bridgeNote(heard: String) = "\n(앱 안내: 사용자는 방금 ${heard} 들었습니다. 첫 단계를 안내하기 전에 " +
        "\"잘 들었죠? 그럼 이제 시작해 볼게요\"처럼 방금 들은 것에서 오늘의 루틴이나 콘텐츠로 넘어가는 짧은 한 문장을 말투 설정에 맞춰 먼저 해 주세요. " +
        "들은 내용은 되풀이하지 말고, 이 첫 응답은 3문장까지 괜찮습니다.)"

/** 마무리 안내 자막을 보여 주는 시간 (앱은 읽지 않음) */
private const val NOTE_SHOW_MS = 2_500L

/** 포춘을 읽는 AI 응답을 기다리는 최대 시간 (넘으면 다음 단계로) */
private const val FORTUNE_MAX_MS = 40_000L
/** 포춘을 다 읽은 뒤 카드를 더 보여 주는 시간 */
private const val FORTUNE_HOLD_MS = 2_500L
/** AI 말에서 포춘 문장의 앞 글자가 이만큼 나오면 쿠키를 엶 (공백·문장부호 빼고) */
private const val FORTUNE_MATCH_CHARS = 6

/** AI 질문 뒤 이 시간 동안 대답이 없으면 재기상 음악 (브리핑의 기상 확인 전까지만) */
private const val REWAKE_SILENCE_MS = 8_000L
private const val REWAKE_LEVEL = 3

/**
 * 아침 대화 (Gemini Live). 지시문·함수는 서버가 토큰에 고정한다 (ai/live, AI CONTRACTS 2절).
 * 세션 시작 → 브리핑 세션(시작 질문에 대답) → 안내 후 어젯밤 한 마디 → 오늘의 포춘(포춘 세션, AI가 문장을 읽음)
 * → start_routine이면 코칭 세션(새 토큰) → 종료
 * 이미 다 마친 날 다시 열면 브리핑에서 루틴을 안내하지 않음
 * initialSession: 알람 수신 화면에서 이미 만든 세션 (없으면 여기서 만듦)
 * onFinish: 통화가 끝나면 세션 id(세션을 만들지 못했으면 null)와 통화 길이(초)를 넘김
 */
@Composable
fun MorningChatScreen(alarmId: Int, onFinish: (sessionId: String?, talkSec: Int) -> Unit, initialSession: JSONObject? = null) {
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
    var coachingStarted by remember { mutableStateOf(false) }   // 이번 통화의 단계를 받아 코칭 세션을 열었음
    var coachSpoke by remember { mutableStateOf(false) }        // 코칭 세션에서 AI가 첫 안내를 시작함
    // 퀴즈 O / X 반응. AI가 판정 함수에 정답 여부(answer_correct)를 보내면 그 값을 쓰고(quizByTool),
    // 받은 적이 없으면 넥스티의 말로 추측함
    var reaction by remember { mutableStateOf<NextyMotion?>(null) }
    var quizByTool by remember { mutableStateOf(false) }
    var fortune by remember { mutableStateOf<FortuneShow?>(null) }    // 오늘의 포춘 (포춘 단계에서만)
    var fortuneOpen by remember { mutableStateOf(false) }             // 쿠키를 열어 카드를 보여 줌

    fun finish() {
        val sid = sessionId ?: initialSession?.optJSONObject("session")?.optString("session_id")?.takeIf { it.isNotBlank() }
        onFinish(sid, elapsed)
    }

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
            if (newly != null) banner = Banner("${items.count { it.done }}번째 스텝 완료!", newly.title)
        }

        client.onToolCall = { call ->
            when (call.name) {
                "report_step_result" -> {
                    val sid = RoutineRepository.sessionId
                    val order = call.args.optInt("step_order", -1)
                    val verdict = call.args.optString("verdict")
                    // 퀴즈 콘텐츠: AI가 정답 여부를 함께 보냄 → 넥스티가 바로 O / X
                    if (call.args.has("answer_correct")) {
                        val correct = call.args.optBoolean("answer_correct")
                        withContext(Dispatchers.Main) {
                            quizByTool = true
                            reaction = if (correct) NextyMotion.QUIZ_O else NextyMotion.QUIZ_X
                        }
                    }
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
        suspend fun playLastNightMessage(found: Pair<String?, java.io.File?>): Boolean {
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
            return ok
        }

        // 마무리 안내: 앱이 소리 내어 읽지 않고 자막으로만 잠깐 보여 줌 (말은 AI만 함)
        suspend fun showNote(text: String) {
            stage = Stage.Wrapup
            wrapText = text
            delay(NOTE_SHOW_MS)
        }

        // 오늘의 포춘: 포춘 토큰으로 짧은 세션을 열어 AI가 문장을 읽고, 첫 응답이 끝나면(turnComplete) 다음 단계로.
        // 토큰을 못 받으면(503 AI_UNAVAILABLE 등) 건너뜀. 듣기만 하는 단계라 마이크는 열지 않음
        // tokenJob: 어젯밤 한 마디를 듣는 동안 미리 요청해 둔 포춘 토큰. 포춘을 들려줬으면 true
        suspend fun playFortune(tokenJob: Deferred<FortuneToken>): Boolean {
            stage = Stage.Fortune
            fortune = null
            fortuneOpen = false
            notice = "넥스티가 오늘의 포춘쿠키를 가져오는 중…"
            val t = tokenJob.await()
            val auth = t.auth ?: return false
            // 재료 표시: AI가 만든 문장이면 어젯밤 한 마디(받아쓴 글이 있으면 그 글)를 밝힘
            val heard = messageText?.trim()?.takeIf { it.isNotEmpty() }
            val basedOn = when {
                t.source != "ai" -> null
                heard != null -> "어젯밤 한마디 · “$heard”"
                else -> "어젯밤 이야기로 만든 포춘"
            }
            fortune = FortuneShow(t.text, t.style, basedOn)
            client.stop()
            client.start(listen = false) { auth }
            val read = client.awaitFirstTurn(FORTUNE_MAX_MS)
            fortuneOpen = true
            if (read || t.text != null) delay(FORTUNE_HOLD_MS)
            // 포춘 세션에서 온 함수 호출은 쓰지 않음 (다음 단계는 앞 세션의 start_routine / end_session대로)
            while (toolEvents.tryReceive().isSuccess) Unit
            return true
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
        val coachingSkipped = RoutineRepository.session?.coachingSkipped == true
        client.start {
            // 오늘 일정은 폰 캘린더에서 (권한은 밤 대화에서 받음. 없으면 빈 일정), 날씨용 위치는 마지막으로 알던 위치
            val auth = RespecApi.briefingToken(sid, CalendarEvents.today(context), placeJob.await())
                ?: RespecApi.tokenError()
            val note = when {
                doneAtStart -> ALREADY_DONE_NOTE
                coachingSkipped -> ""          // 코칭이 없는 날은 마무리 질문 자체가 없음
                else -> listenFirstNote(if (messageJob.await() != null) "어젯밤 남긴 한마디와 오늘의 포춘" else "오늘의 포춘")
            }
            if (note.isEmpty()) auth else LiveAuth(auth.token, auth.wsUrl, auth.model, auth.startSignal + note)
        }

        // 3) 함수 호출에 따라 다음 세션으로 넘어가거나 종료
        val message = messageJob.await()
        var messageHeard = false
        var heard: String? = null                              // 코칭 첫 말에서 이어받을 것 (bridgeNote)
        var coachJob: Deferred<CoachingToken>? = null          // 포춘을 듣는 동안 미리 요청한 코칭 토큰
        for (event in toolEvents) {
            client.awaitSpeechEnd()      // AI의 마지막 말을 끝까지 들려줌. 연결은 마무리 안내까지 유지
            client.pauseMic()

            // 브리핑이 끝나면(루틴을 시작하든 통화를 끝내든) 어젯밤 한 마디 → 오늘의 포춘을 먼저 들려줌
            // (브리핑 마무리에서 AI가 "먼저 한마디와 포춘부터 들어 볼까요?"라고 묻고 답을 들었음: listenFirstNote)
            if (!messageHeard) {
                messageHeard = true
                val fortuneJob = async { RespecApi.fortuneToken(sid) }
                if (message != null && playLastNightMessage(message)) heard = "어젯밤 남긴 한마디를"
                // 루틴으로 넘어갈 거면 포춘을 듣는 동안 코칭 토큰을 미리 받아 둠 (콘텐츠는 단계를 만드느라 오래 걸림).
                // 토큰은 받은 뒤 2분 안에 연결해야 하는데 포춘 단계는 그보다 짧음
                if (event == "start_routine") coachJob = async { RespecApi.coachingToken(sid) }
                if (playFortune(fortuneJob)) heard = "오늘의 포춘을"
            }

            // end_session, 또는 루틴의 모든 단계를 판정함(routine_over): 바로 마무리
            if (event == "end_session" || event == ROUTINE_OVER) break

            // start_routine: 코칭 토큰을 받으면 지금 세션을 닫고 새 토큰으로 코칭(콘텐츠 → 기본 루틴 순) 세션 시작
            stage = Stage.Coaching
            notice = if (RoutineRepository.session?.isContent == true) "콘텐츠를 준비하는 중…" else "루틴을 준비하는 중…"
            val first = coachJob?.await() ?: RespecApi.coachingToken(sid)
            coachJob = null
            val firstAuth = first.auth
            if (firstAuth == null) {
                first.steps?.let { RoutineRepository.applySteps(it) }
                if (first.code != "ALL_STEPS_CHECKED") {      // 다 마친 날은 아래에서 완수 안내
                    showNote(
                        if (first.code == "COACHING_SKIPPED") "오늘은 루틴을 쉬어 가는 날이에요. 좋은 하루 보내세요!"
                        else "루틴을 시작하지 못했어요. 루틴 탭에서 직접 체크해 주세요."
                    )
                }
                break
            }
            client.stop()
            first.steps?.let { RoutineRepository.applySteps(it) }
            coachingStarted = true

            // 첫 연결은 받아 둔 토큰(한마디·포춘에서 넘어가는 말을 덧붙임), 재연결은 서버가 남은 단계만 넣은 새 토큰
            var pending: LiveAuth? = heard?.let { h ->
                LiveAuth(firstAuth.token, firstAuth.wsUrl, firstAuth.model, firstAuth.startSignal + bridgeNote(h))
            } ?: firstAuth
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
        finish()
    }

    // AI가 포춘 문장을 읽기 시작하면 쿠키를 엶 (소개 한 문장 뒤)
    LaunchedEffect(lines, stage, fortune) {
        val text = fortune?.text ?: return@LaunchedEffect
        if (stage != Stage.Fortune || fortuneOpen) return@LaunchedEffect
        val head = fortuneKey(text).take(FORTUNE_MATCH_CHARS)
        val said = lines.lastOrNull { it.role == LiveClient.Role.Assistant }?.text ?: return@LaunchedEffect
        if (head.isNotEmpty() && head in fortuneKey(said)) fortuneOpen = true
    }

    fun skipMessage() {
        if (stage == Stage.Message) messageDone[0]?.complete(Unit)
    }

    BackHandler { finish() }

    val live = status == LiveClient.Status.Live
    // 루틴·콘텐츠 준비 중: 코칭 세션의 AI가 첫 안내를 시작하기 전 (아직 대답할 것이 없음)
    val preparing = stage == Stage.Coaching && !coachSpoke
    val phase = when {
        stage == Stage.Message -> MPhase.Playing
        stage == Stage.Wrapup || stage == Stage.Fortune || preparing -> MPhase.AiSpeaking
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
        (stage == Stage.Briefing || stage == Stage.Coaching) && !preparing && live && lastLine?.role == LiveClient.Role.Assistant -> lastLine
        else -> null
    }
    val spokenPage = spokenLine?.let { rememberSpokenPage(it.atMs, it.text, spoken) }
    val subtitle = when {
        spokenPage != null -> spokenPage.text
        stage == Stage.Message -> messageText?.let { "“$it”" } ?: notice
        stage == Stage.Wrapup -> wrapText
        stage == Stage.Preparing || stage == Stage.Finished -> notice
        status == LiveClient.Status.Error -> liveMessage ?: "연결이 끊겼어요"
        status == LiveClient.Status.Reconnecting -> "연결이 잠시 끊겨서 다시 연결하는 중…"
        preparing -> notice
        else -> lastLine?.text ?: notice
    }
    // 단계 타이머·시범은 이번 통화에서 코칭을 시작한 뒤에만 (브리핑·메시지 중에는 표시 안 함)
    val showRoutine = coachingStarted && RoutineRepository.hasSteps
    val currentStep = items.firstOrNull { !it.done && !it.skipped }
    val currentTitle = currentStep?.title
    val contentType = RoutineRepository.session?.contentType

    // 단계 타이머: AI가 코칭 안내를 시작한 뒤, 단계가 바뀔 때마다 그 단계의 계획 시간부터 줄어듦 (계획 시간이 없으면 없음)
    var stepStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentStep?.stepOrder, coachSpoke) { stepStartMs = System.currentTimeMillis() }
    LaunchedEffect(showRoutine) {
        while (showRoutine) {
            nowMs = System.currentTimeMillis()
            delay(200)
        }
    }
    val stepTotalMs = currentStep?.durationSec?.takeIf { it > 0 }?.times(1000L)
    val remainingMs = stepTotalMs?.takeIf { coachSpoke }?.let { (it - (nowMs - stepStartMs)).coerceIn(0L, it) }
    val timerFraction = if (remainingMs != null && stepTotalMs != null) remainingMs.toFloat() / stepTotalMs else null

    // 퀴즈 예비 방법: AI가 정답 여부를 보내지 않는 동안만, 사용자가 답한 바로 뒤 넥스티의 말로 맞고 틀림을 추측해 O / X
    // (말이 끝나기 전에도 분명한 표현이면 바로 반응)
    var judgedAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(lines, aiSpeaking) {
        if (quizByTool || contentType != "QUIZ" || !coachingStarted || lines.size < 2) return@LaunchedEffect
        val line = lines.last()
        if (line.atMs == judgedAt || line.role != LiveClient.Role.Assistant || lines[lines.lastIndex - 1].role != LiveClient.Role.User) {
            return@LaunchedEffect
        }
        quizReaction(line.text, finished = !aiSpeaking)?.let {
            judgedAt = line.atMs
            reaction = it
        }
    }
    LaunchedEffect(reaction) {
        if (reaction != null) {
            delay(QUIZ_REACTION_MS)
            reaction = null
        }
    }
    val shownReaction = reaction
    // 원 안 넥스티: 퀴즈 반응 → 스트레칭 시범(단계 이름에 맞는 동작) → 기본
    val coachMotion = when {
        shownReaction != null -> shownReaction
        contentType == "EXERCISE" && currentStep != null -> stretchMotionFor(currentStep.title, currentStep.stepOrder ?: 1)
        else -> NextyMotion.IDLE
    }
    // 루틴을 모두 판정했으면 완료/건너뛰기 버튼을 숨기고, 코칭을 끌지 않고 바로 마무리로
    val routineOver = stage == Stage.Coaching && items.isNotEmpty() && currentTitle == null
    LaunchedEffect(routineOver) { if (routineOver) toolEvents.trySend(ROUTINE_OVER) }

    // ---- UI ----
    val scale = rememberScreenScale()   // 세로가 짧은 폰에서 버튼·영역 크기를 줄임
    Box(
        Modifier
            .fillMaxSize()
            .background(RespecColors.MorningBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            MorningHeader(elapsed)
            Spacer(Modifier.height(8.dp))

            // 오늘의 포춘: 원·자막 자리를 포춘쿠키 화면으로
            if (stage == Stage.Fortune) {
                FortuneScene(
                    fortune = fortune,
                    opened = fortuneOpen,
                    aiLine = lines.lastOrNull { it.role == LiveClient.Role.Assistant }?.text ?: notice,
                    speaking = aiSpeaking,
                    onOpen = { if (fortune != null) fortuneOpen = true },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
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
                            // 코칭 중에는 단계 타이머 링만, 그 전(브리핑·메시지)에는 파동과 테두리
                            if (!showRoutine) OrbHalo(ring, tint)
                            TimerRing(
                                remaining = timerFraction,
                                visible = showRoutine,
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
                                    // 햇살 꼭지 넥스티 (말할 때 입이 움직임). 코칭 중에는 스트레칭 시범·퀴즈 O/X를 몸으로
                                    if (showRoutine) {
                                        NextyCharacter(
                                            coachMotion,
                                            Modifier.size(ring * 176 / 240),
                                            speaking = phase == MPhase.AiSpeaking && shownReaction == null
                                        )
                                    } else {
                                        NextyCharacter(
                                            NextyMotion.IDLE,
                                            Modifier.size(ring * 134 / 240),
                                            speaking = phase == MPhase.AiSpeaking
                                        )
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
                                stage == Stage.Coaching && currentTitle != null ->
                                    "지금은 · $currentTitle" + (remainingMs?.let { " · ${(it + 999) / 1000}초" } ?: "")
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
            }
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
                                    containerColor = RespecColors.Button,
                                    contentColor = RespecColors.OnButton
                                )
                            ) {
                                FitText(doneText, color = RespecColors.OnButton, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(80.dp, Alignment.CenterHorizontally),
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
                            .size(64.dp * scale)
                            .clip(CircleShape)
                            .background(RespecColors.Danger)
                            .clickable { finish() },
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

/**
 * 원 둘레의 단계 타이머. remaining(1 → 0)만큼 위에서부터 시계 방향으로 강조색 호가 남고, 시간이 지나면 줄어듦.
 * remaining이 null(계획 시간이 없는 단계)이면 빈 테두리만. visible이 false면 아무것도 그리지 않음
 */
@Composable
private fun TimerRing(
    remaining: Float?,
    visible: Boolean,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val animated by animateFloatAsState(remaining ?: 0f, tween(250), label = "timer")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (!visible) return@Canvas
            val w = stroke.toPx()
            val arcSize = Size(size.width - w, size.height - w)
            val topLeft = Offset(w / 2, w / 2)
            drawArc(RespecColors.Disabled, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            if (remaining != null && animated > 0f) {
                // 은은한 빛
                drawArc(
                    RespecColors.AccentText.copy(alpha = 0.18f), -90f, 360f * animated, false, topLeft, arcSize,
                    style = Stroke(w * 1.9f, cap = StrokeCap.Round)
                )
                drawArc(RespecColors.Lime, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/**
 * 스트레칭 단계 이름에 맞는 넥스티 시범 동작 (단계 이름·안내 문장은 ai/tasks/stretch_sets.py).
 * 이름에 여러 낱말이 겹치므로(예: "팔 흔들며 제자리 걷기"의 '팔') 더 구체적인 이름부터 확인함.
 * 맞는 것이 없으면 단계 순서대로 돌아가며 보여 줌
 */
private fun stretchMotionFor(name: String, order: Int): NextyMotion = when {
    "걷기" in name -> NextyMotion.MARCH
    "목" in name && ("돌리" in name || "턱" in name) -> NextyMotion.NECK_TURN
    "목" in name -> NextyMotion.NECK
    "으쓱" in name -> NextyMotion.SHRUG
    "교차" in name -> NextyMotion.CROSS_PULL
    "어깨뼈" in name -> NextyMotion.CHEST_W
    "어깨" in name && "돌리" in name -> NextyMotion.SHOULDER_ROLL
    "깍지" in name && "가슴" in name -> NextyMotion.BACK_CLASP
    "벽" in name && "가슴" in name -> NextyMotion.WALL_CHEST
    "가슴" in name -> NextyMotion.CHEST_W
    "옆구리" in name && "한쪽" in name -> NextyMotion.RAINBOW_HOLD
    "옆구리" in name -> NextyMotion.RAINBOW
    "비틀" in name -> NextyMotion.TWIST
    "허리" in name || "골반" in name -> NextyMotion.WAIST
    "기지개" in name || "만세" in name || "팔" in name -> NextyMotion.OVERHEAD
    "어깨" in name -> NextyMotion.SHOULDER_ROLL
    else -> STRETCH_CYCLE[(order - 1).mod(STRETCH_CYCLE.size)]
}

private val STRETCH_CYCLE = listOf(
    NextyMotion.OVERHEAD, NextyMotion.RAINBOW, NextyMotion.CHEST_W, NextyMotion.NECK, NextyMotion.WAIST
)

/** 포춘 문장 비교용: 공백·문장부호를 뺀 글자 */
private fun fortuneKey(text: String): String = text.filter { it.isLetterOrDigit() }

private val QUIZ_WRONG = Regex("아쉬|틀렸|틀리셨|아니에요|아닙니다")
private val QUIZ_RIGHT = Regex("정답이|정답입|맞았|맞아요|맞습니다|맞혔|맞히셨|맞추셨|맞췄")

/**
 * 퀴즈 답 뒤 넥스티의 말 → O / X. 분명한 표현(맞았어요, 아쉬워요 등)은 말하는 중에도 바로,
 * '정답은 ○○'처럼 애매한 표현은 말을 마친 뒤(finished)에만 오답으로 봄. 알 수 없으면 null
 */
private fun quizReaction(text: String, finished: Boolean): NextyMotion? = when {
    QUIZ_WRONG.containsMatchIn(text) -> NextyMotion.QUIZ_X
    QUIZ_RIGHT.containsMatchIn(text) -> NextyMotion.QUIZ_O
    finished && "정답은" in text -> NextyMotion.QUIZ_X
    else -> null
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