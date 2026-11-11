package com.example.respeclive.ui.night

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import com.example.respeclive.audio.CallAudio
import com.example.respeclive.audio.SleepLevel
import com.example.respeclive.audio.SleepMusic
import com.example.respeclive.audio.VoiceRecorder
import com.example.respeclive.audio.VoiceTranscriber
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.CalendarEvents
import com.example.respeclive.data.MusicStore
import com.example.respeclive.data.NightMessageStore
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.WakeOverrides
import com.example.respeclive.live.LiveClient
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.common.SubtitleBox
import com.example.respeclive.ui.common.rememberHeldUserLine
import com.example.respeclive.ui.common.rememberScreenScale
import com.example.respeclive.ui.common.rememberSpokenPage
import com.example.respeclive.ui.common.typeSubtitlePages
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NightDecor
import com.example.respeclive.ui.nexty.NextyFace
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 대화(Live)가 끝난 뒤 앱이 읽는 고정 안내 문장
private const val SAVE_QUESTION = "내일 아침의 나에게 들려줄 한 마디를 남겨 볼까요?"
// 마무리 인사: 한 마디를 저장했으면 아침에 들려준다는 말을 앞에 붙임
private const val CLOSING = "오늘 하루도 정말 수고했어요."
private const val CLOSING_SAVED = "내일 아침 모닝콜에서 오늘 남긴 한 마디를 들려 드릴게요. 오늘 하루도 정말 수고했어요."

// 말 감지 설정 (한 마디 녹음용)
private const val MIN_THRESHOLD = 2500
private const val SILENCE_END_MS = 1500
private const val NO_SPEECH_TIMEOUT_MS = 20_000
private const val MAX_SPEECH_MS = 60_000

/** 원문·목소리 업로드를 마무리 멘트 뒤 최대 이만큼 기다림 (화면을 닫아도 업로드는 계속됨) */
private const val SAVE_WAIT_MS = 15_000L

// 수면 타이머: 마무리 뒤 음악만 흐르기 시작한 때(그 전에 화면을 나가면 나간 때)부터 10분 뒤 서서히 끔
private const val SLEEP_MUSIC_MS = 10 * 60_000L

// 컨디션 곡 전환: 대화 원문 저장 뒤 밤 기록 추출(extract_status)이 끝났는지 이 간격으로 이만큼 확인
private const val EXTRACT_POLL_MS = 3_000L
private const val EXTRACT_WAIT_MS = 3 * 60_000L

private enum class Phase { AiSpeaking, Listening, Recording, Review }

/** 동그라미 최대 크기 (자리가 모자라면 줄어듦) */
private val ORB_SIZE = 230.dp

private fun isoUtc(ms: Long): String? = if (ms <= 0L) null else
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

/**
 * 굿나잇콜. ① Gemini Live 대화 (지시문은 서버가 토큰에 고정, AI가 끝내지 않고 사용자가 끝냄)
 * → ② 원문을 서버로 (밤 기록 추출은 서버 백그라운드) → ③ 내일의 한 마디 녹음 → ④ 목소리 업로드
 */
@Composable
fun NightChatScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    val client = remember { LiveClient(context) }
    val speaker = remember { AiSpeaker(context) }
    val recorder = remember { VoiceRecorder(context) }
    val previewHolder = remember { arrayOfNulls<MediaPlayer>(1) }
    val toolEvents = remember { Channel<String>(Channel.UNLIMITED) }      // end_session
    val endTalk = remember { Channel<Unit>(Channel.CONFLATED) }           // 사용자가 버튼으로 대화를 끝냄
    val reviewChoice = remember { Channel<Boolean>(Channel.CONFLATED) }   // true=저장, false=다시 녹음
    val micReady = remember { CompletableDeferred<Unit>() }

    val status by client.status.collectAsState()
    val aiSpeaking by client.aiSpeaking.collectAsState()
    val userLevel by client.userLevel.collectAsState()
    val lines by client.transcript.collectAsState()
    val liveMessage by client.message.collectAsState()
    val spoken by client.spokenFraction.collectAsState()

    var talking by remember { mutableStateOf(true) }      // Live 대화 단계
    var phase by remember { mutableStateOf(Phase.AiSpeaking) }   // 대화 뒤 한 마디 단계의 상태
    var subtitle by remember { mutableStateOf("") }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recLevel by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableIntStateOf(0) }
    var musicOn by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var saveStep by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }   // 녹음한 한 마디를 받아쓴 글자
    var transcribing by remember { mutableStateOf(false) }
    var skipAi by remember { mutableStateOf(false) }
    var skipSave by remember { mutableStateOf(false) }
    var finishNow by remember { mutableStateOf(false) }
    var musicStarted by remember { mutableStateOf(false) } // 굿나잇콜 시작과 함께 수면 음악을 틀었음 (SleepMusicService)
    var sleeping by remember { mutableStateOf(false) }     // 마무리 뒤 수면 음악만 흐르는 상태
    val musicPlaying by SleepMusic.playing.collectAsState()
    var liveSaying by remember { mutableStateOf(false) }   // 안내 문장을 AI 목소리로 읽는 중 (자막은 AI가 실제로 한 말)

    // ---- 마이크·캘린더 권한: 결과를 받은 뒤 Live 연결 (캘린더는 없어도 대화는 됨, 일정만 빠짐) ----
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == false) {
            Toast.makeText(context, "마이크 권한이 있어야 대답할 수 있어요", Toast.LENGTH_SHORT).show()
        }
        micReady.complete(Unit)
    }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) micReady.complete(Unit) else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---- 통화 볼륨으로 통일: 화면에 있는 동안 통화 모드 유지. 수면 음악만 남으면 풀고 미디어 볼륨으로 ----
    val callAudioHeld = remember { booleanArrayOf(false) }
    fun releaseCallAudio() {
        if (callAudioHeld[0]) CallAudio.release(context)
        callAudioHeld[0] = false
    }
    LaunchedEffect(sleeping) {
        if (sleeping) {
            releaseCallAudio()
            CallAudio.volumeKeys(context, AudioManager.STREAM_MUSIC)
        }
    }

    // ---- 정리 + 화면 켜짐 유지 (음악만 흐르는 동안은 화면을 꺼도 됨) ----
    LaunchedEffect(sleeping) { view.keepScreenOn = !sleeping }
    val musicOnNow by rememberUpdatedState(musicOn)
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        CallAudio.acquire(context)
        callAudioHeld[0] = true
        CallAudio.volumeKeys(context, AudioManager.STREAM_VOICE_CALL)
        onDispose {
            view.keepScreenOn = false
            releaseCallAudio()
            CallAudio.volumeKeys(context, AudioManager.USE_DEFAULT_STREAM_TYPE)
            client.release()
            recorder.cancel()
            previewHolder[0]?.release()
            speaker.shutdown()
            // 화면을 나가도 수면 음악은 서비스에서 이어짐: 보통 볼륨으로 되돌리고 수면 타이머를 걸어 둠 (이미 걸었으면 그대로).
            // 음악을 꺼 둔 채로 나가면 끝냄
            if (musicOnNow) {
                SleepMusic.setLevel(context, SleepLevel.NORMAL)
                SleepMusic.resume(context)
                SleepMusic.startTimer(context, SLEEP_MUSIC_MS)
            } else {
                SleepMusic.stop(context)
            }
        }
    }

    // ---- 수면 음악 볼륨: 대화·녹음 듣기 중에는 아주 작게, 한 마디 녹음·들어보기 중에는 멈춤 (마이크에 섞이지 않게) ----
    LaunchedEffect(musicOn, musicStarted, talking, phase, saveStep) {
        if (!musicStarted) return@LaunchedEffect
        if (!musicOn || (saveStep && phase != Phase.AiSpeaking)) {
            SleepMusic.pause(context)
        } else {
            SleepMusic.setLevel(context, if (talking || phase == Phase.Listening) SleepLevel.QUIET else SleepLevel.NORMAL)
            SleepMusic.resume(context)
        }
    }

    // ---- 음악만 흐르는 중에 수면 타이머·알림창 정지로 음악이 끝나면 화면도 닫음 ----
    LaunchedEffect(sleeping, musicPlaying) {
        if (sleeping && !musicPlaying) onClose()
    }

    LaunchedEffect(muted) { client.setMuted(muted) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed += 1
        }
    }

    // ---- 굿나잇콜 전체 진행 ----
    LaunchedEffect(Unit) {

        // 고정 안내 문장: 자막(페이지별) + 음성. 대화와 같은 AI 목소리로 읽고, 연결이 끊겼으면 기기 TTS
        suspend fun say(text: String) {
            phase = Phase.AiSpeaking
            subtitle = ""
            skipAi = false
            liveSaying = true
            val byLive = client.speakExact(text)
            liveSaying = false
            if (byLive) {
                subtitle = client.transcript.value.getOrNull(client.exactLineIndex)?.text ?: text
                return
            }
            coroutineScope {
                val typing = launch {
                    typeSubtitlePages(text, { subtitle = it }, { skipAi })
                }
                val speech = launch { speaker.speakAndWait(text) }
                typing.join()
                speech.join()
            }
        }

        // '내일의 한 마디' 녹음: 말 시작 감지 → 말 끝 감지. 말이 없었거나 너무 짧으면 null
        suspend fun recordOneLiner(): File? {
            phase = Phase.Listening
            delay(400)
            while (muted && !skipSave) delay(300)   // 음소거 중이면 해제할 때까지 대기
            if (skipSave) return null

            val file = NightMessageStore.newMessageFile(context)
            if (!recorder.start(file)) {
                Toast.makeText(context, "마이크를 열지 못했어요", Toast.LENGTH_SHORT).show()
                return null
            }
            finishNow = false
            var stopped = false
            try {
                var floor = 0
                repeat(5) {
                    delay(100)
                    floor = maxOf(floor, recorder.amplitude())
                }
                val threshold = maxOf(MIN_THRESHOLD, (floor * 2.2f).toInt())

                var loudMs = 0
                var speechMs = 0
                var silenceMs = 0
                var waitedMs = 0
                var speaking = false

                while (true) {
                    delay(100)
                    if (skipSave) break
                    val amp = recorder.amplitude()
                    recLevel = (amp / 12000f).coerceIn(0f, 1f)
                    val loud = amp > threshold

                    if (!speaking) {
                        loudMs = if (loud) loudMs + 100 else maxOf(0, loudMs - 50)
                        waitedMs += 100
                        if (loudMs >= 300) {
                            speaking = true
                            phase = Phase.Recording
                            speechMs = 0
                            recordSeconds = 0
                        } else if (waitedMs >= NO_SPEECH_TIMEOUT_MS) {
                            break
                        }
                    } else {
                        speechMs += 100
                        recordSeconds = speechMs / 1000
                        silenceMs = if (loud) 0 else silenceMs + 100
                        if (silenceMs >= SILENCE_END_MS || speechMs >= MAX_SPEECH_MS || finishNow) break
                    }
                }

                val result = recorder.stop()
                stopped = true
                recLevel = 0f
                return if (speaking) result else {
                    result?.delete()
                    null
                }
            } finally {
                if (!stopped) recorder.cancel()
                recLevel = 0f
            }
        }

        // end_session: 응답을 보내지 않고 작별 인사가 끝나면 닫음 (AI CONTRACTS 2-3)
        client.onToolCall = { call ->
            if (call.name == "end_session") {
                toolEvents.trySend(call.name)
                null
            } else {
                JSONObject().put("result", "ok")
            }
        }

        // ===== 0) 굿나잇콜 시작과 함께 기본 수면 음악(NORMAL)을 작은 볼륨으로 =====
        // 컨디션은 통화가 끝나고 밤 기록을 추출한 뒤에 정해지므로 우선 NORMAL 곡. 서버 곡이 없으면 앱 내 파일
        launch {
            val track = RespecApi.sleepMusic("NORMAL")
            if (SleepMusic.play(context, track, SleepLevel.QUIET)) {
                musicStarted = true
            } else {
                musicOn = false
                Toast.makeText(context, "재생할 수면 음악이 없어요", Toast.LENGTH_SHORT).show()
            }
        }

        // ===== 1) Live 대화. 끝은 사용자가 정함 (말로 → end_session, 또는 버튼) =====
        micReady.await()
        // 토큰마다(재연결 포함) 그때의 폰 캘린더 일정을 넣음. 권한이 없으면 빈 일정
        client.start {
            RespecApi.nightToken(CalendarEvents.today(context), CalendarEvents.tomorrow(context)) ?: RespecApi.tokenError()
        }
        // AI가 마무리하며 끝냈으면(end_session) 한마디 녹음 안내를 AI가 이미 함. 사용자가 버튼으로 끝냈으면 앱이 안내
        val endedByAi = select<Boolean> {
            toolEvents.onReceive {
                client.awaitSpeechEnd()
                true
            }
            endTalk.onReceive { false }
        }
        client.pauseMic()     // 연결은 안내 문장을 읽을 때까지 유지 (같은 목소리), 마이크는 한 마디 녹음에 넘김
        val talkEndedAt = System.currentTimeMillis()
        talking = false

        // 내일 아침 재기상 음악 미리 받기 (12시간 유효)
        ApiClient.scope.launch { MusicStore.prefetchRewake(context.applicationContext) }

        // ===== 2) 원문 저장은 백그라운드로 (화면을 닫아도 계속). 사용자 발화가 없으면 서버가 받지 않음 =====
        val turns = client.transcript.value
        val hasUser = turns.any { it.role == LiveClient.Role.User && it.text.isNotBlank() }
        val transcript = client.transcriptJson()
        val startedAt = isoUtc(client.startedAtMs)
        val endedAt = isoUtc(talkEndedAt)
        val appCtx = context.applicationContext
        val saveJob = ApiClient.scope.async {
            if (!hasUser) return@async null
            RespecApi.saveTranscript(transcript, startedAt, endedAt)?.also { r ->
                NightMessageStore.saveRecord(appCtx, r.optString("night_record_id"), r.optString("apply_date"))
            }
        }

        // 밤 기록 추출이 끝나면(DONE): ① 대화로 하루만 바꾼 내일 기상 시각을 알람에 반영
        // ② 오늘 컨디션 곡을 다시 받아, 곡이 다르면 서서히 바꿈.
        // 화면을 닫아도 계속 확인. 추출 실패·시간 초과·요청 실패면 그대로 (기상 시각은 홈을 열 때 다시 확인)
        ApiClient.scope.launch {
            val id = saveJob.await()?.optString("night_record_id")?.takeIf { it.isNotBlank() } ?: return@launch
            var record: JSONObject? = null
            withTimeoutOrNull(EXTRACT_WAIT_MS) {
                while (record?.optString("extract_status").let { it != "DONE" && it != "FAILED" }) {
                    delay(EXTRACT_POLL_MS)
                    record = RespecApi.nightRecord(id) ?: record
                }
            }
            val done = record?.takeIf { it.optString("extract_status") == "DONE" } ?: return@launch
            WakeOverrides.applyNightRecord(appCtx, done)
            if (!SleepMusic.playing.value) return@launch
            RespecApi.sleepMusic(null)?.let { SleepMusic.switchTo(appCtx, it) }
        }

        // ===== 3) 내일의 한 마디 녹음 =====
        saveStep = true
        // AI가 "내일의 나에게 남길 한마디를 녹음해 볼게요"로 마무리했으면 같은 안내를 다시 읽지 않고 자막만 보여 줌.
        // 사용자가 먼저 "잘게요" 해서 AI가 인사만 하고 끝냈거나 버튼으로 끝냈으면 앱이 안내를 읽음
        val lastAi = turns.lastOrNull { it.role == LiveClient.Role.Assistant }?.text.orEmpty()
        val aiAnnounced = endedByAi && ("녹음" in lastAi || "한마디" in lastAi || "한 마디" in lastAi)
        if (aiAnnounced) subtitle = SAVE_QUESTION else say(SAVE_QUESTION)

        var voice: File? = null
        var attempts = 0
        while (voice == null && attempts < 2) {
            skipSave = false
            val rec = recordOneLiner()
            if (rec == null) {
                if (skipSave) break
                attempts += 1
                if (attempts < 2) say("잘 못 들었어요. 한 마디를 다시 들려주세요.")
                continue
            }
            pendingFile = rec
            pendingText = null
            reviewChoice.tryReceive()
            phase = Phase.Review
            val textJob = async {
                transcribing = true
                VoiceTranscriber.transcribe(context, rec).also {
                    pendingText = it
                    transcribing = false
                }
            }
            val save = reviewChoice.receive()
            previewHolder[0]?.release()
            previewHolder[0] = null
            if (save) {
                voice = rec
                // 기기에도 보관 (서버 조회 실패 시 대체 재생, 아침에 글자로도 보여 줌)
                NightMessageStore.save(context, rec, withTimeoutOrNull(5_000) { textJob.await() })
                Toast.makeText(context, "내일의 나에게 남긴 한 마디를 저장했어요", Toast.LENGTH_SHORT).show()
            } else {
                textJob.cancel()
                rec.delete()
            }
            transcribing = false
            pendingFile = null
            pendingText = null
        }
        saveStep = false

        // ===== 4) 밤 기록이 저장되면 목소리 업로드, 그동안 마무리 멘트 =====
        val sendVoice = voice
        val uploadJob = ApiClient.scope.async {
            val rec = saveJob.await() ?: return@async false
            val id = rec.optString("night_record_id")
            sendVoice == null || (id.isNotBlank() && RespecApi.uploadVoice(id, sendVoice))
        }

        say(if (voice != null) CLOSING_SAVED else CLOSING)
        client.stop()

        val saved = withTimeoutOrNull(SAVE_WAIT_MS) { uploadJob.await() } == true
        if (!saved && (hasUser || voice != null)) {
            Toast.makeText(context, "서버에 저장하지 못했어요. 한 마디는 기기에 보관돼요", Toast.LENGTH_SHORT).show()
        }
        delay(800)
        if (musicOn && SleepMusic.playing.value) {
            sleeping = true      // 음악만 흐르는 상태. 수면 타이머를 걸고, 화면을 끄거나 나가도 음악은 이어짐
            SleepMusic.startTimer(context, SLEEP_MUSIC_MS, force = true)
            subtitle = "수면 음악이 흐르고 있어요. 10분 뒤에 저절로 꺼져요. 화면을 꺼도 계속 들려요. 편히 주무세요."
        } else {
            onClose()
        }
    }

    // ---- 동작 ----
    fun skipSpeech() {
        if (!talking && phase == Phase.AiSpeaking) {
            skipAi = true
            client.interruptSpeech()
            speaker.stop()
        }
    }

    fun preview() {
        val file = pendingFile ?: return
        try {
            previewHolder[0]?.release()
            previewHolder[0] = MediaPlayer().apply {
                setAudioAttributes(CallAudio.VOICE_ATTRIBUTES)   // AI 음성과 같은 통화 볼륨
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    previewHolder[0] = null
                }
                prepare()
                setVolume(CallAudio.VOICE_GAIN, CallAudio.VOICE_GAIN)
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "재생하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }

    // 대화 중에는 대화만 끝내고 한 마디 단계로, 그 뒤에는 바로 닫음 (원문 저장은 백그라운드에서 계속)
    fun endCall() {
        if (talking) {
            endTalk.trySend(Unit)
            return
        }
        recorder.cancel()
        pendingFile?.delete()
        onClose()
    }

    BackHandler { endCall() }

    val shownPhase = when {
        !talking -> phase
        status == LiveClient.Status.Live && !aiSpeaking -> Phase.Listening
        else -> Phase.AiSpeaking
    }
    // AI가 바로 대답해도 사용자 말을 잠깐 더 보여 줌
    val heldUser = rememberHeldUserLine(lines).takeIf { talking && status == LiveClient.Status.Live }
    val lastLine = heldUser ?: lines.lastOrNull()
    val shownFromUser = when {
        !talking -> phase == Phase.Review
        else -> status == LiveClient.Status.Live && lastLine?.role == LiveClient.Role.User
    }
    // AI가 말하는 줄: 지금 말하는 박스를 통째로 먼저 보여 줌
    val spokenLine = when {
        !talking && liveSaying -> lines.getOrNull(client.exactLineIndex)
        talking && status == LiveClient.Status.Live && lastLine?.role == LiveClient.Role.Assistant -> lastLine
        else -> null
    }
    val spokenPage = spokenLine?.let { rememberSpokenPage(it.atMs, it.text, spoken) }
    val shownSubtitle = when {
        spokenPage != null -> spokenPage.text
        !talking && phase == Phase.Review -> when {
            pendingText != null -> "“$pendingText”"
            transcribing -> "녹음한 말을 글자로 바꾸는 중…"
            else -> "녹음은 됐지만 글자로 바꾸지 못했어요. 들어보기로 확인해 주세요"
        }
        !talking && liveSaying -> ""
        !talking -> subtitle
        status == LiveClient.Status.Error -> liveMessage ?: "연결이 끊겼어요. 통화 종료를 누르면 한 마디 남기기로 넘어가요"
        status == LiveClient.Status.Reconnecting -> "연결이 잠시 끊겨서 다시 연결하는 중…"
        status != LiveClient.Status.Live -> "리스펙과 연결하는 중…"
        else -> lastLine?.text.orEmpty()
    }

    // ---- UI ----
    val scale = rememberScreenScale()   // 세로가 짧은 폰에서 버튼·영역 크기를 줄임
    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        CallHeader(elapsed = elapsed, musicOn = musicOn, onMusicChange = { musicOn = it })

        // 세로가 짧은 폰에서는 자막·버튼과 겹치지 않도록 남은 공간에 맞춰 줄임 (아래 상태 문구 자리 28dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            VoiceOrb(
                size = minOf(ORB_SIZE, maxHeight - 28.dp, maxWidth).coerceAtLeast(0.dp),
                phase = shownPhase,
                level = if (talking) userLevel else recLevel,
                isSaveStep = saveStep,
                onTap = { skipSpeech() }
            )
        }

        SubtitleBox(shownSubtitle, fromUser = shownFromUser, pageKey = spokenPage?.key)
        Spacer(Modifier.height(12.dp))

        // 한 마디 녹음 단계에서만 버튼 표시 (대화 중에는 통화 종료 버튼으로 끝냄)
        if (!talking && phase != Phase.AiSpeaking) AnswerArea(
            minHeight = 120.dp * scale,
            phase = shownPhase,
            isSaveStep = saveStep,
            sleeping = sleeping,
            muted = muted,
            recordSeconds = recordSeconds,
            onSkip = {
                if (talking) endTalk.trySend(Unit)
                else if (saveStep) skipSave = true
            },
            onFinishNow = { finishNow = true },
            onSave = { reviewChoice.trySend(true) },
            onRetake = { reviewChoice.trySend(false) },
            onPreview = { preview() }
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(80.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top
        ) {
            RoundControl(
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = "음소거",
                size = 64.dp * scale,
                active = muted,
                onClick = { if (phase != Phase.Recording) muted = !muted }
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(64.dp * scale)
                        .clip(CircleShape)
                        .background(RespecColors.Danger)
                        .clickable { endCall() },
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
}

@Composable
private fun CallHeader(elapsed: Int, musicOn: Boolean, onMusicChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RespecColors.Mint))
                Spacer(Modifier.width(8.dp))
                Text("리스펙", color = RespecColors.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            FitText(
                "AI 굿나잇콜 통화 중 · %02d:%02d".format(elapsed / 60, elapsed % 60),
                color = RespecColors.TextSub,
                fontSize = 13.sp
            )
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.Lime.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = RespecColors.AccentText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("수면 음악", color = RespecColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = musicOn,
                onCheckedChange = onMusicChange,
                modifier = Modifier.scale(0.8f),
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
    }
}

/**
 * 밤의 넥스티가 든 원과 상태를 보여 주는 파동. AI 말하기(강조색, 입이 움직임, 한 마디 단계에서는 탭하면 건너뛰기)
 * / 듣는 중(보라) / 말하는 중(음량 반응)
 */
@Composable
private fun VoiceOrb(size: Dp, phase: Phase, level: Float, isSaveStep: Boolean, onTap: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val smoothLevel by animateFloatAsState(level, tween(120), label = "level")

    val tint = when (phase) {
        Phase.AiSpeaking -> RespecColors.AccentText
        Phase.Recording -> if (isSaveStep) RespecColors.Danger else RespecColors.Mint
        else -> RespecColors.Purple
    }
    val reactive = phase == Phase.Recording
    val outerScale = if (reactive) 1f + smoothLevel * 0.3f else pulse
    val status = when (phase) {
        Phase.AiSpeaking -> if (isSaveStep) "리스펙이 이야기하는 중 · 탭하면 건너뛰기" else "리스펙이 이야기하는 중"
        Phase.Listening -> "듣고 있어요"
        Phase.Recording -> if (isSaveStep) "녹음 중" else "말씀을 듣고 있어요"
        Phase.Review -> "녹음을 확인해 주세요"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size)
        ) {
            Box(
                Modifier
                    .size(size * 216 / 230)
                    .scale(outerScale)
                    .border(2.dp, tint.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                Modifier
                    .size(size * 184 / 230)
                    .scale(if (reactive) 1f + smoothLevel * 0.15f else pulse)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.08f))
            )
            Box(
                Modifier
                    .size(size * 150 / 230)
                    .clip(CircleShape)
                    .background(RespecColors.Card)
                    .border(2.dp, tint.copy(alpha = 0.7f), CircleShape)
                    .clickable(enabled = phase == Phase.AiSpeaking, onClick = onTap),
                contentAlignment = Alignment.Center
            ) {
                // 머리 위·발 아래 여백이 있는 캔버스라 원보다 크게 그려야 원을 알맞게 채움
                NextyCharacter(
                    NextyMotion.IDLE,
                    Modifier.requiredSize(size * 180 / 230),
                    face = NextyFace.SLEEPY,
                    night = true,
                    speaking = phase == Phase.AiSpeaking
                )
            }
            NightDecor(RespecColors.AccentText, Modifier.fillMaxSize(), inRing = true)
        }
        FitText(status, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun AnswerArea(
    minHeight: Dp,
    phase: Phase,
    isSaveStep: Boolean,
    sleeping: Boolean,
    muted: Boolean,
    recordSeconds: Int,
    onSkip: () -> Unit,
    onFinishNow: () -> Unit,
    onSave: () -> Unit,
    onRetake: () -> Unit,
    onPreview: () -> Unit
) {
    val time = "%02d:%02d".format(recordSeconds / 60, recordSeconds % 60)

    Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        when (phase) {
            Phase.AiSpeaking -> Unit

            Phase.Listening, Phase.Recording -> {
                val recording = phase == Phase.Recording
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 보라색 마이크 아이콘은 제거. 안내 문구와 버튼만 표시
                    val hint = when {
                        muted -> "음소거 중이에요. 해제하면 말할 수 있어요"
                        recording && isSaveStep -> "녹음 중  $time"
                        recording -> "듣고 있어요…"
                        isSaveStep -> "내일의 나에게 남길 한 마디를 말해 주세요"
                        else -> "편하게 대답해 주세요"
                    }
                    Text(
                        text = hint,
                        color = when {
                            recording && isSaveStep -> RespecColors.Danger
                            isSaveStep -> RespecColors.AccentText
                            else -> RespecColors.TextSub
                        },
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    if (recording) {
                        TextButton(onClick = onFinishNow) {
                            Text("말 끝났어요", color = RespecColors.Text, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(onClick = onSkip) {
                            Text(if (isSaveStep) "다음에 남길게요" else "대화 마치기", color = RespecColors.TextSub)
                        }
                    }
                }
            }

            Phase.Review -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FitText(
                            "한 마디를 녹음했어요 ($time)",
                            color = RespecColors.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(onClick = onPreview) {
                            Text("▶ 들어보기", color = RespecColors.AccentText, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                    Text("내일 아침 모닝콜에서 들려드릴까요?", color = RespecColors.TextSub, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onRetake,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(1.5.dp, RespecColors.Purple)
                        ) {
                            FitText("다시 녹음", color = RespecColors.Purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RespecColors.Button,
                                contentColor = RespecColors.OnButton
                            )
                        ) {
                            FitText("저장하기", color = RespecColors.OnButton, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, size: Dp, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) RespecColors.Text else RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) RespecColors.Background else RespecColors.Text)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = RespecColors.TextSub, fontSize = 13.sp)
    }
}