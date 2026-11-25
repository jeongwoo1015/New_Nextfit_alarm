package com.example.respeclive.ui.morning

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
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
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.audio.AlarmVibrator
import com.example.respeclive.data.SnoozeCount
import com.example.respeclive.data.VoiceSnooze
import com.example.respeclive.data.WakeLevelPrefs
import com.example.respeclive.data.WordChainLine
import com.example.respeclive.data.WordChainMorning
import com.example.respeclive.data.WordChainReason
import com.example.respeclive.data.WordChainResult
import com.example.respeclive.data.WordChainStore
import com.example.respeclive.data.WordChainWinner
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
import com.example.respeclive.ui.common.CallLayout
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.common.SubtitleBox
import com.example.respeclive.ui.common.rememberHeldUserLine
import com.example.respeclive.ui.common.rememberScreenScale
import com.example.respeclive.ui.common.rememberSubtitleHeight
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private val RING_SIZE = CallLayout.ORB_SIZE
private const val BTN_LATER = "오늘은 건너뛸게요"

/**
 * 판정 함수를 부르기 전에 AI가 이미 한 말이 다음 단계 안내(대답 방법으로 끝남: "'했어요'라고 말해 주세요")나
 * 코칭 마무리(포춘으로 넘기기·하루 응원)인지. 그렇다면 함수 응답 뒤 AI가 이어서 하는 응답은 되풀이이거나
 * 사용자 대답 전에 다음 단계를 미리 판정하고 마무리하는 말이라 들려주지 않음
 */
private val GUIDE_SPOKEN = Regex("라고\\s?(말|대답|얘기)|포춘|좋은 하루")
/** 코칭 마무리 말 (다음 순서인 포춘으로 넘기거나 하루를 응원): 이 말이 나오면 완료·건너뛰기 버튼을 숨김 */
private val COACH_CLOSING = Regex("포춘|좋은 하루")

/** 퀴즈 답에 O / X로 반응하는 시간 */
private const val QUIZ_REACTION_MS = 2800L

/** 앱이 보내는 이벤트: 코칭 중 모든 단계를 판정함 */
private const val ROUTINE_OVER = "routine_over"
/** 끝말잇기 내 차례 제한 시간 (콘텐츠 탭의 글자 이어 하기와 같게) */
private const val WORDCHAIN_TURN_MS = 20_000L

// 모닝콜 순서표의 단계 (ai/tasks/morning_plan.py, AI CONTRACTS B15)
private const val STAGE_BRIEFING = "BRIEFING"
private const val STAGE_ONE_LINER = "ONE_LINER"
private const val STAGE_COACHING = "COACHING"
private const val STAGE_FORTUNE = "FORTUNE"
private val PLAN_STAGES = setOf(STAGE_BRIEFING, STAGE_ONE_LINER, STAGE_COACHING, STAGE_FORTUNE)
/** 서버가 순서표를 주지 않을 때의 순서. AI 프롬프트도 순서표가 없으면 이 순서(LEGACY_ORDER)로 안내함 */
private val LEGACY_PLAN = listOf(STAGE_BRIEFING, STAGE_ONE_LINER, STAGE_FORTUNE, STAGE_COACHING)

/**
 * 세션 시작 응답의 morning_plan (예: ["BRIEFING", "ONE_LINER", "COACHING", "FORTUNE"]).
 * 모르는 값·중복은 버림. 없거나 브리핑이 없으면 null
 */
private fun morningPlan(view: JSONObject): List<String>? {
    val arr = view.optJSONArray("morning_plan") ?: view.optJSONObject("session")?.optJSONArray("morning_plan") ?: return null
    val plan = (0 until arr.length()).map { arr.optString(it).trim().uppercase(Locale.ROOT) }.filter { it in PLAN_STAGES }.distinct()
    return plan.takeIf { it.firstOrNull() == STAGE_BRIEFING }
}


/** 재기상 음악을 틀 때 AI에게 보내는 알림: 이름을 불러 깨우고 깨우는 말을 한 단계 강하게 함 (AI CONTRACTS B17) */
private const val REWAKE_NOTICE = "(앱 알림: 재기상 음악 재생)"

/** 판정 보고가 사용자 말 받아쓰기보다 먼저 올 때, 받아쓰기를 기다리는 시간 (B19) */
private const val REPORT_SPEECH_WAIT_MS = 1_500L

/**
 * 보류한 판정 보고로 화면을 다음 단계로 넘기려면 직전 보고(또는 코칭 시작) 뒤 이만큼 지나야 함.
 * 사용자 말 받아쓰기가 늦은 진짜 대답은 단계 시간만큼 지나서 오고, AI가 다음 단계를 안내하며 미리 보낸 보고는 몇 초 만에 옴
 */
private const val HELD_ADVANCE_GAP_MS = 8_000L
/** 판정을 보류한 뒤 이 시간 안에 들어온 사용자 말만 그 단계 대답의 늦은 받아쓰기로 봄 (그 뒤의 말은 다음 단계 대답) */
private const val LATE_SPEECH_MS = 5_000L
/** 단계를 마친 것으로 보는 판정 (ambiguous·other는 AI가 다시 물으므로 그 단계에 머묾) */
private val FINAL_VERDICTS = setOf("done", "skipped", "not_done")

/** 마무리 안내 자막을 보여 주는 시간 (앱은 읽지 않음) */
private const val NOTE_SHOW_MS = 2_500L

/** 포춘을 읽는 AI 응답을 기다리는 최대 시간 (넘으면 다음 단계로) */
private const val FORTUNE_MAX_MS = 40_000L
/** 포춘을 다 읽은 뒤 카드를 더 보여 주는 시간 */
private const val FORTUNE_HOLD_MS = 2_500L
/** AI 말에서 포춘 문장의 앞 글자가 이만큼 나오면 쿠키를 엶 (공백·문장부호 빼고) */
private const val FORTUNE_MATCH_CHARS = 6

/** AI가 말을 마친 뒤 이 시간 동안 대답이 없으면 재기상 음악 (브리핑·코칭 내내, 스트레칭은 단계 타이머가 끝난 뒤부터) */
private const val REWAKE_SILENCE_MS = 8_000L

/**
 * 아침 대화 (Gemini Live). 지시문·함수는 서버가 토큰에 고정한다 (ai/live, AI CONTRACTS 2절).
 * 세션 시작 → 순서표(morning_plan)대로 진행 → 종료. 단계: 브리핑 세션 / 어젯밤 한 마디 재생 / 코칭 세션(브리핑이 start_routine을 부른 날만)
 * / 오늘의 포춘(포춘 세션, AI가 문장을 읽음). 순서표가 없으면 브리핑 → 한마디 → 포춘 → 코칭
 * 단계를 잇는 말(다음 순서 안내, 앞 단계와 이어 말하기, 작별 인사)은 AI 프롬프트가 순서표를 보고 함
 * 이미 다 마친 날 다시 열면 브리핑에서 루틴을 안내하지 않음
 * initialSession: 알람 수신 화면에서 이미 만든 세션 (없으면 여기서 만듦)
 * onFinish: 통화가 끝나면 세션 id(세션을 만들지 못했으면 null)와 통화 길이(초)를 넘김
 */
@Composable
fun MorningChatScreen(
    alarmId: Int,
    onFinish: (sessionId: String?, talkSec: Int) -> Unit,
    initialSession: JSONObject? = null,
    onSnoozed: () -> Unit = {},     // 말로 미루기를 마쳤을 때 (리포트 없이 홈으로)
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val client = remember { LiveClient(context) }
    val messagePlayer = remember { MessagePlayer() }
    val rewakePlayer = remember { AlarmSoundPlayer(context) }
    val rewakeVibration = remember { AlarmVibrator(context) }
    val messageDone = remember { arrayOfNulls<CompletableDeferred<Unit>>(1) }
    val toolEvents = remember { Channel<String>(Channel.UNLIMITED) }   // start_routine / end_session
    val micReady = remember { CompletableDeferred<Unit>() }
    val items = RoutineRepository.items

    val status by client.status.collectAsState()
    val aiSpeaking by client.aiSpeaking.collectAsState()
    val lines by client.transcript.collectAsState()
    val liveMessage by client.message.collectAsState()
    val spoken by client.spokenFraction.collectAsState()
    val userTurns by client.userTurnsFlow.collectAsState()   // 받아쓰기 없이 끊김으로 센 말도 포함

    var stage by remember { mutableStateOf(Stage.Preparing) }
    var notice by remember { mutableStateOf("리스펙이 준비하는 중…") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var responded by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Banner?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var rewaking by remember { mutableStateOf(false) }
    var rewakeTurns by remember { mutableIntStateOf(0) }    // 재기상 음악을 튼 때의 사용자 말 횟수
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
    // 스트레칭 부위를 고르는 중 (세션 시작 응답에 부위별 루틴이 왔고 아직 select_stretch_area가 오지 않음).
    // 이때 화면의 1번 단계는 전신 루틴이라 AI가 부위를 묻는 동안 단계 이름·타이머·시범을 보여 주지 않음
    var areaPending by remember { mutableStateOf(false) }
    // 보류한 판정 보고 (단계 번호 → verdict). AI는 보고한 단계를 마친 것으로 보고 다음 단계로 넘어가므로 화면은 바로 넘기고,
    // 서버 기록은 사용자 말 받아쓰기가 도착하거나 AI가 다음 단계를 보고하거나 코칭이 끝날 때 함 (B19)
    val heldReports = remember { mutableStateMapOf<Int, String>() }
    var heldShown by remember { mutableIntStateOf(0) }     // 보류한 보고로 화면을 넘긴 마지막 단계 번호 (화면은 그다음 단계)
    var rescheduleArgs by remember { mutableStateOf<JSONObject?>(null) }
    var snoozedByVoice by remember { mutableStateOf(false) }   // 말로 미룸: 사용자가 통화 종료를 누르면 리포트 없이 홈으로
    fun finish() {
        if (snoozedByVoice) {
            onSnoozed()
            return
        }
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
            rewakeVibration.stop()
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
    // AI는 wake_check_done을 부르지 않으므로 앱이 판정 시점을 알린다.
    // 받아쓰기 글자가 들어올 때마다 이 효과가 다시 시작되므로, 요청은 효과 밖(ApiClient.scope)에서 끝까지 보냄
    // (효과 안에서 보내면 첫 응답 요청 중에 취소돼 기상 확인을 보내지 못하고 '기상 미확인'으로 남음)
    LaunchedEffect(lines, sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!responded && lines.any { it.role == LiveClient.Role.User }) {
            responded = true
            ApiClient.scope.launch {
                RespecApi.firstResponse(sid)
                RespecApi.wakeCheck(sid)?.let { v -> withContext(Dispatchers.Main) { RoutineRepository.applyView(v) } }
            }
        }
    }

    // ---- 아침 대화 진행 ----
    LaunchedEffect(Unit) {
        var acceptedUserTurns = 0       // 마지막으로 받아들인 판정 보고 때의 사용자 말 횟수 (B19)
        var skipUserTurns = -1          // 건너뛰기 보고를 받아들인 사용자 말 (같은 말에 이어 오는 skipped는 받음)

        // 스텝 완료 배너: 서버 응답을 기다리지 않고 화면이 다음 단계로 넘어가는 때(AI의 done 판정을 받아들인 때) 한 단계에 한 번만.
        // 서버 응답 뒤에 띄우면 늦게 뜨고, 보류했던 보고를 한꺼번에 기록할 때 두 개가 연달아 떴음
        val announced = mutableSetOf<Int>()
        fun announce(orders: List<Int>) {
            val fresh = orders.filter { announced.add(it) }
            val last = fresh.maxOrNull() ?: return
            val idx = items.indexOfFirst { it.stepOrder == last }
            if (idx >= 0) banner = Banner("${idx + 1}번째 스텝 완료!", items[idx].title)
        }

        // 판정 보고 처리는 한 번에 하나씩 (보고·보류 기록·받아쓰기 도착이 서로 끼어들지 않게)
        val reportLock = Mutex()
        var lastReportAt = 0L           // 직전 판정 보고(또는 코칭 시작·부위 선택) 시각
        var heldAt = 0L                 // 마지막으로 판정을 보류한 시각

        suspend fun record(order: Int, verdict: String) {
            val sid = RoutineRepository.sessionId ?: return
            RespecApi.reportVerdict(sid, order, verdict)?.let { RoutineRepository.applyView(it) }
        }

        // 보류한 보고 중 below보다 앞 단계를 기록
        suspend fun flushHeld(below: Int = Int.MAX_VALUE) {
            val orders = heldReports.keys.filter { it < below }.sorted()
            announce(orders.filter { heldReports[it] == "done" })     // 여러 개면 마지막 하나만 배너
            for (o in orders) {
                val v = heldReports[o] ?: continue
                record(o, v)
                heldReports.remove(o)
            }
        }

        // 판정 보고 검사 (AI CONTRACTS B19): AI가 사용자 대답 전에 미리 판정하는 일이 있어,
        // ① 지금 진행 중인 단계이고 ② 직전에 받아들인 보고 뒤에 사용자가 말했을 때 바로 기록함.
        // 사용자 말 받아쓰기는 판정보다 몇 초 늦거나 빠지기도 해서, 그렇지 않은 보고도 버리지 않고 보류함
        // (버리면 앱의 지금 단계가 AI보다 뒤에 멈춰 이후 보고가 모두 순서가 어긋나 무시되던 문제, 11/17 실기기).
        // 예외: 건너뛰기(skipped)는 사용자가 말했으면 순서와 관계없이, 같은 말에 이어 온 skipped도 받음(전체 건너뛰기)
        // 돌려주는 값: AI의 판정으로 받아들였는가(바로 기록 또는 보류). 이미 지난 단계의 보고면 false
        suspend fun handleReport(order: Int, verdict: String): Boolean {
            // AI가 다음 단계를 보고했으면 앞에서 보류한 단계는 AI가 그대로 넘어간 것이므로 먼저 확정
            if (heldReports.keys.any { it < order }) flushHeld(below = order)
            val current = items.firstOrNull { !it.done && !it.skipped }?.stepOrder
            val skip = verdict == "skipped"
            // 완료 배너는 받아쓰기를 기다리지 않고 바로: 사용자가 말했거나, 직전 보고 뒤 충분히 지난(미리 보낸 보고가 아닌) 지금 단계의 done
            // (말로 대답하면 받아쓰기가 판정보다 늦게 와서 아래 대기만큼 배너가 늦게 떴음. 버튼은 받아쓰기 없이 바로 세어 빨랐음)
            val sinceLast = System.currentTimeMillis() - lastReportAt
            if (verdict == "done" && order == current && (client.userTurns > acceptedUserTurns || sinceLast >= HELD_ADVANCE_GAP_MS)) {
                announce(listOf(order))
            }
            val lastUser = client.transcript.value.lastOrNull { it.role == LiveClient.Role.User }
            Log.d("Morning", "판정 도착: step=$order verdict=$verdict 마지막 사용자 말 뒤 ${lastUser?.let { System.currentTimeMillis() - it.atMs } ?: -1}ms")
            // 판정이 사용자 말 받아쓰기보다 먼저 도착하기도 해서, 지금 단계 보고면 받아쓰기를 잠깐 기다림
            if (order == current && client.userTurns <= acceptedUserTurns) {
                withTimeoutOrNull(REPORT_SPEECH_WAIT_MS) {
                    while (client.userTurns <= acceptedUserTurns) delay(100)
                }
            }
            val turns = client.userTurns
            val spoke = turns > acceptedUserTurns
            val ok = (spoke && (order == current || skip)) || (skip && turns == skipUserTurns)
            val now = System.currentTimeMillis()
            val gap = now - lastReportAt
            lastReportAt = now
            if (ok) {
                acceptedUserTurns = turns
                if (skip) skipUserTurns = turns
                heldReports.remove(order)
                if (verdict !in FINAL_VERDICTS) heldShown = minOf(heldShown, order - 1)   // AI가 그 단계를 다시 물음
                if (verdict == "done") announce(listOf(order))
                record(order, verdict)
                return true
            }
            if (current == null || order < current) {
                Log.w("Morning", "판정 보고 무시(이미 지난 단계): step=$order verdict=$verdict current=$current")
                return false
            }
            Log.w("Morning", "판정 보고 보류: step=$order verdict=$verdict current=$current spoke=$spoke")
            heldReports[order] = verdict
            heldAt = now
            // 화면은 AI를 따라 다음 단계로. 단, 직전 보고 직후에 온 보고는 미리 보낸 것일 수 있어 화면은 그대로 둠
            if (verdict in FINAL_VERDICTS && gap >= HELD_ADVANCE_GAP_MS) {
                heldShown = maxOf(heldShown, order)
                if (verdict == "done") announce(listOf(order))
            }
            return true
        }

        // 보류한 보고가 있을 때 사용자 말이 들어오면 보류한 단계를 기록.
        // 보류 직후(LATE_SPEECH_MS 안)에 온 말만 그 단계 대답의 늦은 받아쓰기로 보고 써 버림.
        // 그보다 늦은 말은 AI가 이미 넘어간 다음 단계의 대답이므로 남겨 둬서 다음 판정의 근거로 씀
        // (전부 써 버리면 한 번 보류된 뒤로 매 단계의 "했어"가 앞 단계 몫으로 쓰여 판정이 계속 보류됐음, 11/17 실기기)
        launch {
            client.userTurnsFlow.collect {     // 받아쓰기 없이 끊김으로 센 말도 포함
                if (heldReports.isEmpty() || client.userTurns <= acceptedUserTurns) return@collect
                reportLock.withLock {
                    if (heldReports.isNotEmpty() && client.userTurns > acceptedUserTurns) {
                        if (System.currentTimeMillis() - heldAt <= LATE_SPEECH_MS) acceptedUserTurns = client.userTurns
                        flushHeld()
                    }
                }
            }
        }

        client.onToolCall = { call ->
            when (call.name) {
                "report_step_result" -> {
                    // 끝말잇기는 한 단계짜리라 판정 뒤 AI가 이어서 하는 말은 늘 마무리 되풀이
                    // ("재미있게 잘하셨어요. 다음 순서로…" 뒤에 "오늘 끝말잇기도 잘 마치셨어요. 포춘을…")라 조건 없이 들려주지 않음
                    if (RoutineRepository.session?.contentOption == "WORDCHAIN") client.muteReplyToToolCall(call)
                    else client.muteReplyToToolCall(call, GUIDE_SPOKEN)
                    val order = call.args.optInt("step_order", -1)
                    val verdict = call.args.optString("verdict")
                    // 지난 단계의 보고는 기록·화면 갱신을 건너뛰고, AI에게는 똑같이 ok만 돌려줌
                    // (에러로 답하면 AI가 이유를 설명하려고 또 말함)
                    // AI는 함수 응답을 받아야 다음 말을 하므로 ok는 바로 돌려주고, 받아쓰기 대기·서버 기록은 뒤에서 함
                    // (끝날 때까지 기다렸다 돌려주면 "했어" 한 마디에 받아쓰기 대기 1.5초 + 서버 왕복만큼 넥스티가 늦게 말했음).
                    // 보고 순서는 reportLock(먼저 온 순서대로)이 지킴
                    if (order > 0 && verdict.isNotBlank()) launch(Dispatchers.Main) {
                        val taken = reportLock.withLock { handleReport(order, verdict) }
                        // 끝말잇기 승패 (AI에 요청한 칸: game_result USER_WIN/AI_WIN, game_reason TIMEOUT/NO_WORD). 아직 AI가 보내지 않으면 무시
                        val gameResult = call.args.optString("game_result")
                        val sid = sessionId
                        // 시간 초과(TIMEOUT)는 승패가 아니라 일시중지라 저장하지 않음 (콘텐츠 탭에서 이어 함)
                        if (sid != null && RoutineRepository.session?.contentOption == "WORDCHAIN" && gameResult in setOf("USER_WIN", "AI_WIN") &&
                            call.args.optString("game_reason") != "TIMEOUT"
                        ) {
                            WordChainStore.saveMorningResult(context, sid, WordChainResult(
                                if (gameResult == "USER_WIN") WordChainWinner.USER else WordChainWinner.NEXTY,
                                WordChainReason.NO_WORD,
                                inMorning = true
                            ))
                        }
                        // 퀴즈 콘텐츠: AI가 정답 여부를 함께 보냄 → 넥스티가 바로 O / X
                        if (taken && call.args.has("answer_correct")) {
                            quizByTool = true
                            reaction = if (call.args.optBoolean("answer_correct")) NextyMotion.QUIZ_O else NextyMotion.QUIZ_X
                        }
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
                // 스트레칭 부위 선택 (AI CONTRACTS B17): 아직 판정하지 않은 단계를 그 부위 루틴(1번부터)으로 바꿈.
                // 이미 판정한 단계가 있거나 부위를 골랐으면 서버가 409(STRETCH_AREA_LOCKED) → 기존 단계로 진행.
                // AI에게는 어느 쪽이든 ok만 돌려줌
                "select_stretch_area" -> {
                    // AI는 부위를 알리고 1번 단계 안내까지 말한 뒤 이 함수를 부름 → 함수 응답 뒤 1번 안내를 되풀이하는 말은 버림
                    client.muteReplyToToolCall(call)
                    val sid = RoutineRepository.sessionId
                    val area = call.args.optString("area")
                    val view = if (sid != null && area.isNotBlank()) RespecApi.selectStretchArea(sid, area) else null
                    withContext(Dispatchers.Main) {
                        areaPending = false
                        heldReports.clear()
                        heldShown = 0
                        announced.clear()
                        lastReportAt = System.currentTimeMillis()
                        if (view != null) {
                            RoutineRepository.applyView(view)
                            // 부위를 말한 대답은 새 1번 단계의 판정 근거가 아님 → 1번은 이 뒤의 사용자 말부터 받음 (B19)
                            acceptedUserTurns = client.userTurns
                            skipUserTurns = -1
                        } else {
                            Log.w("Morning", "스트레칭 부위 변경 안 됨: area=$area ${ApiClient.lastErrorCode ?: ""}")
                        }
                    }
                    JSONObject().put("result", "ok")
                }
                // 응답을 보내지 않음: 앱이 세션을 닫거나 다음 세션으로 넘어감
                "start_routine", "end_session" -> {
                    toolEvents.trySend(call.name)
                    null
                }
                // 말로 미루기 (B21): end_session처럼 응답을 보내지 않고, 안내 음성이 끝나면 연결을 닫은 뒤 미루기
                "reschedule_alarm" -> {
                    rescheduleArgs = call.args
                    toolEvents.trySend(call.name)
                    null
                }
                // 대화로 설정 바꾸기 (B22): 서버에 바꾸고 AI에는 늘 ok (거절돼도 AI가 다시 설명하지 않도록)
                "update_setting" -> {
                    RespecApi.aiChangeSetting(call.args.optString("field"), call.args.optString("value"),
                        call.args.optString("scope").takeIf { it.isNotBlank() })
                    JSONObject().put("result", "ok")
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

        // 오늘의 포춘: 포춘 토큰으로 짧은 세션을 열어 AI가 문장을 읽고, 첫 응답과 남은 음성이 끝나면 다음 단계로.
        // 포춘이 마지막 순서면 AI가 작별 인사까지 하고 끝남(AI CONTRACTS B15).
        // 토큰을 못 받으면(503 AI_UNAVAILABLE 등) 건너뜀. 듣기만 하는 단계라 마이크는 열지 않음
        // tokenJob: 미리 요청해 둔 포춘 토큰. 포춘을 들려줬으면 true
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
            // 듣기만 하는 단계지만 마이크는 열어 둠(보내지는 않음): 마이크가 닫히면 폰이 다른 음량 경로로 재생해 목소리가 작아짐
            client.start(listen = false, silentMic = true) { auth }
            val read = client.awaitFirstTurn(FORTUNE_MAX_MS)
            fortuneOpen = true
            if (read || t.text != null) delay(FORTUNE_HOLD_MS)
            // 포춘 세션에서 온 함수 호출은 쓰지 않음
            while (toolEvents.tryReceive().isSuccess) Unit
            return true
        }

        // 말로 미루기 (B21·B25): 연결을 닫고 서버에 미룬 뒤 그 시각에 오늘 한 번 다시 울리도록 예약.
        // 브리핑·코칭 어느 통화에서든 같고, 미룬 뒤에는 남은 단계(한마디·콘텐츠·포춘)를 열지 않음.
        // 회의(11/20)·AI 안내("지금 알람은 직접 종료해 주세요")대로 사용자가 통화 종료를 눌러야 끝남
        suspend fun voiceSnooze(sid: String) {
            client.stop()
            val args = rescheduleArgs
            val req = VoiceSnooze.check(
                args?.takeIf { it.has("minutes_later") && !it.isNull("minutes_later") }?.optInt("minutes_later"),
                args?.optString("at_time")?.takeIf { it.isNotBlank() && it != "null" }
            )
            val message = if (req == null) {
                "다시 울릴 시각을 알아듣지 못했어요. 알람을 직접 맞춰 주세요"
            } else {
                val r = RespecApi.snoozeTo(sid, req.minutesLater, req.atTime)
                when {
                    r.code == "SNOOZE_LIMIT" -> {
                        SnoozeCount.set(context, SnoozeCount.MAX_PER_DAY)
                        "오늘은 더 미룰 수 없어요 (하루 ${SnoozeCount.MAX_PER_DAY}번까지)"
                    }
                    else -> {
                        // 서버가 정한 시각, 서버에 닿지 못했으면 앱이 계산한 시각
                        val at = r.snoozedUntilMs ?: req.triggerMillis
                        AlarmScheduler.scheduleSnoozeAt(context, alarmId, VoiceSnooze.label(at), at)
                        r.snoozesToday?.let { SnoozeCount.set(context, it) } ?: SnoozeCount.increment(context)
                        "${VoiceSnooze.label(at)}에 다시 울려요"
                    }
                }
            }
            snoozedByVoice = true
            stage = Stage.Finished
            notice = "$message · 통화를 종료해 주세요"
        }

        // 코칭(루틴·콘텐츠) 세션: 새 토큰으로 연결하고, 모든 단계를 판정하거나(routine_over) end_session이 오면
        // AI의 마무리 말을 끝까지 들려준 뒤 끝냄. 앞 단계와 이어 말하기, 다음 단계(포춘)로 넘기는 말은 AI가 함(B15)
        // tokenJob: 미리 요청해 둔 코칭 토큰(없으면 지금 요청), hasNext: 코칭 뒤에 이어지는 단계가 있음
        // 돌려주는 값: 코칭 중 말로 미뤘으면 true (B25)
        suspend fun runCoaching(sid: String, tokenJob: Deferred<CoachingToken>?, hasNext: Boolean): Boolean {
            while (toolEvents.tryReceive().isSuccess) Unit      // 앞 세션에서 남은 이벤트
            stage = Stage.Coaching
            notice = if (RoutineRepository.session?.isContent == true) "콘텐츠를 준비하는 중…" else "루틴을 준비하는 중…"
            val first = tokenJob?.await() ?: RespecApi.coachingToken(sid)
            val firstAuth = first.auth
            if (firstAuth == null) {
                first.steps?.let { RoutineRepository.applySteps(it) }
                if (first.code != "ALL_STEPS_CHECKED") {      // 다 마친 날은 안내 없이 다음 단계로
                    showNote(
                        if (first.code == "COACHING_SKIPPED") {
                            if (hasNext) "오늘은 루틴을 쉬어 가는 날이에요." else "오늘은 루틴을 쉬어 가는 날이에요. 좋은 하루 보내세요!"
                        } else "루틴을 시작하지 못했어요."
                    )
                }
                return false
            }
            client.stop()
            first.steps?.let { RoutineRepository.applySteps(it) }
            acceptedUserTurns = client.userTurns       // 판정 보고 검사(B19)는 코칭을 시작한 뒤의 사용자 말부터 셈
            skipUserTurns = -1
            lastReportAt = System.currentTimeMillis()
            coachingStarted = true

            // 첫 연결은 받아 둔 토큰, 재연결은 서버가 남은 단계만 넣은 새 토큰
            var pending: LiveAuth? = firstAuth
            client.start {
                pending?.also { pending = null } ?: RespecApi.coachingToken(sid).let { r ->
                    r.steps?.let { steps -> withContext(Dispatchers.Main) { RoutineRepository.applySteps(steps) } }
                    r.auth ?: RespecApi.tokenError()
                }
            }
            var snoozed = false
            while (true) {
                val event = toolEvents.receive()
                if (event == "reschedule_alarm") snoozed = true
                if (snoozed || event == "end_session" || event == ROUTINE_OVER) break
            }
            // 마지막 판정 뒤 AI는 마무리 말을 이어서 함: 그 응답이 끝나고 음성 재생까지 마친 뒤 다음 단계로.
            // 말로 미뤘으면 미루기 안내 음성이 끝날 때까지 들려줌
            client.awaitReplyEnd()
            client.pauseMic()
            reportLock.withLock { flushHeld() }      // 아직 보류 중인 판정도 AI의 판정대로 기록 (리포트에 반영)
            if (snoozed) voiceSnooze(sid)
            return snoozed
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
        areaPending = started.optJSONObject("stretch_routines")?.length()?.let { it > 0 } == true
        if (initialSession == null) launch { RoutineRepository.loadPlan() }   // 수신 화면을 거치지 않았으면 여기서
        val sid = RoutineRepository.sessionId ?: return@LaunchedEffect
        sessionId = sid
        val messageJob = async { findLastNightMessage() }
        // 그날 순서표: 서버가 준 morning_plan, 없으면 예전 순서 (AI도 순서표가 없으면 예전 순서로 말함)
        val plan = morningPlan(started) ?: LEGACY_PLAN

        // 2) 브리핑 세션. 이미 루틴을 다 마친 날은 서버가 AI 입력(already_done)으로 알려 루틴 안내를 하지 않음
        // 다음 단계를 묻는 마무리 질문과 단계 연결 말은 AI 프롬프트가 순서표를 보고 함 (앱은 덧붙이지 않음)
        micReady.await()
        val placeJob = async { DeviceLocation.lastKnown(context) }   // 재연결 때마다 다시 찾지 않도록 한 번만
        stage = Stage.Briefing
        notice = "리스펙과 연결하는 중…"
        client.start {
            // 오늘 일정은 폰 캘린더에서 (권한은 밤 대화에서 받음. 없으면 빈 일정), 날씨용 위치는 마지막으로 알던 위치
            RespecApi.briefingToken(sid, CalendarEvents.today(context), placeJob.await())
                ?: RespecApi.tokenError()
        }

        // 브리핑은 start_routine(코칭으로) 또는 end_session(코칭 없이 다음 단계로, 또는 사용자가 끝내자고 함)으로 끝남
        val briefingEvent = toolEvents.receive()
        client.awaitReplyEnd()      // AI의 마지막 말을 끝까지 들려줌
        client.pauseMic()

        if (briefingEvent == "reschedule_alarm") {
            voiceSnooze(sid)
            return@LaunchedEffect
        }
        val message = messageJob.await()

        // 3) 순서표의 나머지 단계를 차례로. 한마디는 재생할 녹음이 있을 때만, 코칭은 브리핑이 start_routine을 불렀을 때만
        val rest = plan.drop(plan.indexOf(STAGE_BRIEFING) + 1).filter {
            when (it) {
                STAGE_ONE_LINER -> message != null
                STAGE_COACHING -> briefingEvent == "start_routine"
                else -> true
            }
        }
        // 토큰은 받은 뒤 2분 안에 연결해야 하므로, 듣기만 하는 짧은 단계(한마디·포춘) 동안 바로 다음 단계의 토큰만 미리 받아 둠
        var fortuneJob: Deferred<FortuneToken>? = null
        var coachJob: Deferred<CoachingToken>? = null
        fun prefetch(next: String?) {
            when (next) {
                STAGE_FORTUNE -> if (fortuneJob == null) fortuneJob = async { RespecApi.fortuneToken(sid) }
                STAGE_COACHING -> if (coachJob == null) coachJob = async { RespecApi.coachingToken(sid) }
            }
        }
        var endedOnFortune = false      // 마지막 단계로 포춘을 들려줌 → 끝 안내 없이 포춘 화면에서 바로 리포트로
        for ((i, step) in rest.withIndex()) {
            val next = rest.getOrNull(i + 1)
            when (step) {
                STAGE_ONE_LINER -> {
                    prefetch(next)
                    message?.let { playLastNightMessage(it) }
                }
                STAGE_FORTUNE -> {
                    prefetch(STAGE_FORTUNE)
                    prefetch(next)
                    val played = fortuneJob?.let { playFortune(it) } == true
                    fortuneJob = null
                    endedOnFortune = played && next == null
                }
                STAGE_COACHING -> {
                    val snoozed = runCoaching(sid, coachJob, hasNext = next != null)
                    coachJob = null
                    // 코칭 중 말로 미룸 (B25): 남은 단계(포춘 등)는 열지 않고, 사용자가 통화 종료를 누를 때까지 안내를 둠
                    if (snoozed) {
                        fortuneJob?.cancel()
                        return@LaunchedEffect
                    }
                    // 끝말잇기: 모닝콜에서 주고받은 단어 수를 게임 기록으로 저장 (이어 하기에서 다시 보내면 바뀜)
                    if (RoutineRepository.session?.contentOption == "WORDCHAIN") {
                        val app = context.applicationContext
                        val score = WordChainStore.score(WordChainStore.get(app, sid))
                        ApiClient.scope.launch {
                            RespecApi.saveGameScore(sid, 1, score)?.let { WordChainStore.saveBest(app, it.optInt("best_score", score)) }
                        }
                    }
                }
            }
        }

        client.stop()

        if (!endedOnFortune) {
            stage = Stage.Finished
            notice = "좋은 하루 보내세요!"
            delay(1000)
        }
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
    // 부위를 고르기 전에 단계를 판정했으면(부위 질문 없이 진행) 고르는 중이 아님
    val choosingArea = areaPending && stage == Stage.Coaching && items.none { it.done || it.skipped }
    // 화면의 지금 단계는 AI를 따라감: 보류한 보고로 넘긴 단계가 있으면 그다음 단계부터
    val currentStep = items.firstOrNull { !it.done && !it.skipped && (it.stepOrder ?: 0) > heldShown }?.takeUnless { choosingArea }
    val currentTitle = currentStep?.title
    val contentType = RoutineRepository.session?.contentType
    // 끝말잇기는 정답·오답이 없는 게임이라 넥스티 말로 O / X를 추측하지 않음
    val isWordChain = RoutineRepository.session?.contentOption == "WORDCHAIN"

    // 단계 타이머: AI가 코칭 안내를 시작한 뒤, 단계가 바뀔 때마다 그 단계의 계획 시간부터 줄어듦 (계획 시간이 없으면 없음)
    var stepStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentStep?.stepOrder, currentStep?.title, coachSpoke) { stepStartMs = System.currentTimeMillis() }
    LaunchedEffect(showRoutine) {
        while (showRoutine) {
            nowMs = System.currentTimeMillis()
            delay(200)
        }
    }
    // 끝말잇기는 5분 단계 시간 대신 내 차례 제한 시간(20초): 넥스티가 말을 마치면 시작, 내가 말하면 멈춤. 넘기면 넥스티 승
    // 넥스티의 마지막 말에 끝말잇기로 이어진 단어가 있을 때만 내 차례로 봄: 마무리 칭찬("잘했어요! 이제 포춘…") 뒤
    // 판정이 늦게 기록되는 동안 타이머가 돌아 시간 초과 패배로 잘못 남지 않도록
    val wordChainPoint = remember(lines, isWordChain) {
        if (isWordChain) WordChainMorning.resumePoint(lines.map { WordChainLine(it.role == LiveClient.Role.User, it.text) }) else null
    }
    val wordChainMyTurn = isWordChain && stage == Stage.Coaching && coachSpoke && currentTitle != null &&
        !aiSpeaking && lines.lastOrNull()?.role == LiveClient.Role.Assistant &&
        wordChainPoint?.mine == false && lines.last().text.contains(wordChainPoint.word) &&
        !WordChainMorning.isWrapUp(lines.last().text) &&
        currentStep?.stepOrder !in heldReports.keys
    var turnStartMs by remember { mutableLongStateOf(0L) }
    var turnUserTurns by remember { mutableIntStateOf(0) }
    LaunchedEffect(wordChainMyTurn) {
        if (wordChainMyTurn) {
            turnStartMs = System.currentTimeMillis()
            turnUserTurns = client.userTurns
        }
    }
    val wordChainTurnLive = wordChainMyTurn && userTurns <= turnUserTurns
    val stepTotalMs = if (isWordChain) WORDCHAIN_TURN_MS else currentStep?.durationSec?.takeIf { it > 0 }?.times(1000L)
    val remainingMs = when {
        isWordChain -> if (wordChainTurnLive) (WORDCHAIN_TURN_MS - (nowMs - turnStartMs)).coerceIn(0L, WORDCHAIN_TURN_MS) else null
        else -> stepTotalMs?.takeIf { coachSpoke }?.let { (it - (nowMs - stepStartMs)).coerceIn(0L, it) }
    }
    // 끝말잇기에서 넥스티 차례에는 링을 가득 채워 두고, 내 차례가 되면 줄어듦
    val timerFraction = when {
        remainingMs != null && stepTotalMs != null -> remainingMs.toFloat() / stepTotalMs
        isWordChain && stage == Stage.Coaching && coachSpoke -> 1f
        else -> null
    }

    // ---- 재기상: AI가 말을 마친 뒤 8초 동안 대답이 없으면 음악 + POST /rewake. 사용자가 말하면(버튼 포함) 멈춤 ----
    // 멈춤은 말 횟수로 봄: 사용자 줄과 AI 줄이 거의 같이 들어오면 마지막 줄만 보고는 사용자 말을 놓쳐 음악이 계속 남 (11/18 시연, 스트레칭)
    // 한 번 대답한 뒤에도 브리핑·코칭 내내 다시 8초를 셈 (한 마디 재생·포춘처럼 듣기만 하는 단계는 제외).
    // 스트레칭은 동작하는 동안 말이 없는 게 정상이라, 단계 타이머가 도는 동안은 세지 않고 타이머가 끝난 뒤부터 셈
    val lastRole = lines.lastOrNull()?.role
    val talkStage = stage == Stage.Briefing || (stage == Stage.Coaching && coachSpoke)
    // 끝말잇기: 주고받은 말을 기기에 저장 (콘텐츠 탭에서 확인·이어 하기. 서버는 아침 통화 원문을 저장하지 않음).
    // '패스'·'제가 졌어요' 같은 말이면 승패가 난 것으로 봄 (AI는 다섯 번쯤 주고받으면 마무리하고 승패를 말하지 않음)
    LaunchedEffect(lines, stage, coachingStarted, isWordChain) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!isWordChain || !coachingStarted || stage != Stage.Coaching || lines.isEmpty()) return@LaunchedEffect
        WordChainStore.saveMorning(context, sid, lines.map { WordChainLine(it.role == LiveClient.Role.User, it.text) })
    }
    // 내 차례 20초가 지나면 넥스티 승: 결과를 저장하고 단계를 완료로 맞춘 뒤 다음 순서(포춘 등)로
    val wordChainTimeUp = wordChainTurnLive && remainingMs == 0L
    LaunchedEffect(wordChainTimeUp) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!wordChainTimeUp) return@LaunchedEffect
        WordChainStore.saveMorningResult(context, sid, WordChainResult(WordChainWinner.NEXTY, WordChainReason.TIMEOUT, inMorning = true))
        Toast.makeText(context, "시간이 다 됐어요. 이번 끝말잇기는 넥스티가 이겼어요", Toast.LENGTH_LONG).show()
        currentStep?.stepOrder?.let { order -> scope.launch { RespecApi.markStep(sid, order, "DONE")?.let { RoutineRepository.applyView(it) } } }
        toolEvents.trySend(ROUTINE_OVER)
    }
    val stretchTimerRunning = stage == Stage.Coaching && contentType == "EXERCISE" && remainingMs != null && remainingMs > 0L
    LaunchedEffect(talkStage, status, aiSpeaking, lastRole, stretchTimerRunning, rewaking, sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        // 끝말잇기는 내 차례 20초 타이머가 대답 없음을 처리하므로 재기상 음악을 틀지 않음
        if (!talkStage || status != LiveClient.Status.Live || aiSpeaking || stretchTimerRunning || rewaking ||
            (isWordChain && stage == Stage.Coaching)
        ) return@LaunchedEffect
        if (lastRole != LiveClient.Role.Assistant) return@LaunchedEffect
        delay(REWAKE_SILENCE_MS)
        rewakeTurns = client.userTurns
        rewaking = true
        scope.launch {
            // 밤에 받아 둔 파일 → 서버 URL → (둘 다 없으면) 기기 기본 알람음
            val source = MusicStore.rewakeFile(context)?.absolutePath ?: RespecApi.musicUrl("rewake")
            if (rewaking) {
                rewakePlayer.start(level = WakeLevelPrefs.rewakeLevel(), soundUrl = source)
                rewakeVibration.startStrong()   // 재기상은 유형과 상관없이 세게
                client.sendNotice(REWAKE_NOTICE)      // AI가 이름을 불러 깨움 (대화 원문·자막에는 안 남음)
            }
            RespecApi.rewake(sid)
        }
    }
    LaunchedEffect(lastRole, userTurns, talkStage, rewaking) {
        if (rewaking && (lastRole == LiveClient.Role.User || userTurns > rewakeTurns || !talkStage)) {
            rewakePlayer.stop()
            rewakeVibration.stop()
            rewaking = false
        }
    }

    // 퀴즈 예비 방법: AI가 정답 여부를 보내지 않는 동안만, 사용자가 답한 바로 뒤 넥스티의 말로 맞고 틀림을 추측해 O / X
    // (말이 끝나기 전에도 분명한 표현이면 바로 반응)
    var judgedAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(lines, aiSpeaking) {
        if (quizByTool || contentType != "QUIZ" || isWordChain || !coachingStarted || lines.size < 2) return@LaunchedEffect
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
    // 루틴을 모두 판정했으면 완료/건너뛰기 버튼을 숨기고, 코칭을 끌지 않고 바로 마무리로.
    // 세션 단계가 들어온 뒤에만 봄: 코칭 토큰을 기다리는 동안은 단계 번호 없는 알람 미리보기만 있어 currentStep이 null이라,
    // 여기서 ROUTINE_OVER가 큐에 들어가 토큰이 오자마자 코칭이 끝나던 문제 (영어는 단계를 AI로 만들어 토큰이 7~10초 걸려 늘 걸림, 11/18 시연)
    val routineOver = stage == Stage.Coaching && !choosingArea && RoutineRepository.hasSteps && currentTitle == null
    // AI가 루틴을 마친 것으로 보는 중: 모든 단계를 판정(보류 포함)했거나, 마무리 말(포춘으로 넘기기 등)을 함
    val aiWrapping = stage == Stage.Coaching && coachSpoke && (
        (items.isNotEmpty() && items.all { it.done || it.skipped || it.stepOrder in heldReports.keys }) ||
            lines.lastOrNull()?.let { it.role == LiveClient.Role.Assistant && COACH_CLOSING.containsMatchIn(it.text) } == true
        )
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

            // 오늘의 포춘: 원·자막 자리를 포춘쿠키 화면으로
            if (stage == Stage.Fortune) {
                FortuneScene(
                    fortune = fortune,
                    opened = fortuneOpen,
                    onOpen = { if (fortune != null) fortuneOpen = true },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                // 원 + 자막 박스. 자막 박스는 아래(대답 버튼 위)에 붙고 위치는 밤 대화와 같음.
                // 자리가 모자라면 원을 먼저 줄이고(최소 CallLayout.ORB_MIN), 그래도 모자랄 때만 자막 박스를 줄임 (밤 대화와 같은 규칙)
                val subtitlePref = rememberSubtitleHeight()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 원 위·원과 자막 사이 최소 간격(GAP × 2)을 뺀 자리. 원과 상태 문구는 그 사이 가운데 (밤 대화와 같은 위치)
                val free = maxHeight - CallLayout.ORB_STATUS_HEIGHT - CallLayout.GAP * 2
                val subtitleHeight = minOf(subtitlePref, maxOf(CallLayout.SUBTITLE_MIN, free - CallLayout.ORB_MIN))
                val ringMax = minOf(RING_SIZE, free - subtitleHeight, maxWidth).coerceAtLeast(0.dp)
                Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ring = ringMax
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
                                        .size(if (showRoutine) ring * 188 / 240 else ring * CallLayout.ORB_INNER)
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
                        Spacer(Modifier.height(CallLayout.ORB_STATUS_GAP))
                        FitText(
                            when {
                                phase == MPhase.Playing -> "어젯밤의 내가 남긴 한 마디 · 탭하면 건너뛰기"
                                preparing -> "곧 시작해요"
                                phase == MPhase.Listening -> "듣고 있어요"
                                choosingArea -> "오늘 풀어 줄 부위를 고르는 중"
                                stage == Stage.Coaching && currentTitle != null ->
                                    "지금은 · $currentTitle" + (remainingMs?.let { " · ${(it + 999) / 1000}초" } ?: "")
                                stage == Stage.Briefing -> "아침 브리핑"
                                else -> "리스펙이 이야기하는 중"
                            },
                            color = if (showRoutine) RespecColors.TextSub else tint,
                            fontSize = CallLayout.ORB_STATUS_TEXT,
                            fontWeight = if (showRoutine) FontWeight.Normal else FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 자막 박스 (AI 음성 받아쓰기)
                SubtitleBox(subtitle, fromUser = subtitleFromUser, pageKey = spokenPage?.key, height = subtitleHeight)
                }
                }
            }
            Spacer(Modifier.height(CallLayout.GAP))

            // 대답 버튼: 대답을 기다릴 때만 (말 대신 누르기). 시작 전(preparing은 Listening이 아님)과 루틴을 다 마쳤으면 숨김
            val answers = when {
                // 스트레칭 부위를 묻는 동안은 아직 할 단계가 없으므로 숨김 (부위는 말로 대답)
                // 끝말잇기는 끝이 정해진 동작이 아니라 '완료했어요'가 맞지 않고, 잘못 누르면 게임이 끝난 것으로 기록돼 건너뛰기만 둠
                phase == MPhase.Listening && !routineOver && !aiWrapping && !choosingArea && stage == Stage.Coaching ->
                    BTN_LATER to (if (isWordChain) null else BTN_DONE)
                else -> null
            }
            Box(Modifier.fillMaxWidth().height(CallLayout.ANSWER_HEIGHT * scale), contentAlignment = Alignment.Center) {
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
                            if (doneText != null) Button(
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
            Spacer(Modifier.height(CallLayout.GAP))

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
            .size(size * CallLayout.ORB_RING)
            .scale(pulse)
            .border(2.dp, tint.copy(alpha = 0.35f), CircleShape)
    )
    Box(
        Modifier
            .size(size * CallLayout.ORB_GLOW)
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
 * 스트레칭 단계 이름에 맞는 넥스티 시범 동작. 넥스티 동작 이름이 AI 동작 이름(ai/tasks/stretch_sets.py MOVE_NAMES 34개)과
 * 같아서 단계 이름 전체로 찾음. 모르는 이름(나중에 AI에 동작이 더 생긴 경우)이면 멈추지 않고 전신 루틴 동작을 단계 순서대로 돌아가며 보여 줌
 */
private fun stretchMotionFor(name: String, order: Int): NextyMotion {
    val key = name.trim()
    return NextyMotion.entries.firstOrNull { it.label == key }
        ?: STRETCH_CYCLE[(order - 1).mod(STRETCH_CYCLE.size)]
}

private val STRETCH_CYCLE = listOf(
    NextyMotion.LIE_REACH, NextyMotion.KNEE_ROCK, NextyMotion.KNEE_HUG, NextyMotion.NECK_TILT, NextyMotion.OVERHEAD
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
    Column(Modifier.fillMaxWidth().height(CallLayout.HEADER_HEIGHT), verticalArrangement = Arrangement.Center) {
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