package com.example.respeclive.live

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Live"

/**
 * Gemini Live 통화 한 건을 관리: 연결, 마이크 송신, AI 음성 재생, 자막, 끼어들기, 함수 호출, 재연결.
 *
 * 사용 순서: LiveClient(context) → onToolCall 지정 → start(setup, kickoff) → ... → stop() → release()
 * 화면은 status / aiSpeaking / userLevel / transcript / toolLog / message 를 collect 해서 그림.
 */
class LiveClient(context: Context) {
    enum class Status { Idle, Connecting, Live, Reconnecting, Error, Ended }
    enum class Role { User, Assistant }
    data class Line(val role: Role, val text: String, val atMs: Long)

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = LiveSession()
    private val audioIn = LiveAudioIn(app)
    private val audioOut = LiveAudioOut()
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _aiSpeaking = MutableStateFlow(false)
    val aiSpeaking: StateFlow<Boolean> = _aiSpeaking.asStateFlow()

    private val _userLevel = MutableStateFlow(0f)
    val userLevel: StateFlow<Float> = _userLevel.asStateFlow()

    private val _transcript = MutableStateFlow<List<Line>>(emptyList())
    val transcript: StateFlow<List<Line>> = _transcript.asStateFlow()

    private val _toolLog = MutableStateFlow<List<String>>(emptyList())
    val toolLog: StateFlow<List<String>> = _toolLog.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    var onToolCall: (FunctionCall) -> JSONObject = { JSONObject().put("ok", true) }

    /** 통화 시작/종료 시각 */
    var startedAtMs: Long = 0L
        private set
    var endedAtMs: Long = 0L
        private set

    private lateinit var baseSetup: LiveSetup
    private var kickoff: String? = null
    private var kickoffSent = false
    private var lastHandle: String? = null
    private var micStarted = false
    private var tickerJob: Job? = null

    @Volatile private var stopping = false
    private val reconnecting = AtomicBoolean(false)
    private val connectOutcome = Channel<Boolean>(Channel.CONFLATED)

    private var speakerOn = true
    private var prevMode = AudioManager.MODE_NORMAL
    private var routed = false

    // ---------------- 외부에서 쓰는 함수 ----------------

    /** 통화 시작. */
    fun start(setup: LiveSetup, kickoffText: String? = null) {
        val s = _status.value
        if (s == Status.Connecting || s == Status.Live || s == Status.Reconnecting) return

        baseSetup = setup
        kickoff = kickoffText
        kickoffSent = false
        lastHandle = null
        stopping = false
        micStarted = false
        _transcript.value = emptyList()
        _toolLog.value = emptyList()
        _message.value = null
        startedAtMs = System.currentTimeMillis()
        endedAtMs = 0L
        _status.value = Status.Connecting

        routeAudio(true)
        audioOut.start()
        startTicker()
        scope.launch {
            if (!connectOnce() && !stopping) {
                fail(_message.value ?: "연결하지 못했어요")
            }
        }
    }

    fun stop() {
        val s = _status.value
        if (s == Status.Idle || s == Status.Ended) return
        stopping = true
        tickerJob?.cancel()
        audioIn.stop()
        micStarted = false
        session.close()
        audioOut.stop()
        routeAudio(false)
        endedAtMs = System.currentTimeMillis()
        _aiSpeaking.value = false
        _userLevel.value = 0f
        if (_status.value != Status.Error) _status.value = Status.Ended
        Log.d(TAG, "통화 종료")
    }

    fun release() {
        stop()
        audioOut.release()
        scope.cancel()
    }

    /** 음소거: 마이크는 열어 두되 서버로 보내지 않음. */
    fun setMuted(muted: Boolean) {
        audioIn.muted = muted
        if (muted) session.sendAudioStreamEnd()
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (routed) applySpeaker()
    }

    /** 테스트용 텍스트 입력 */
    fun sendText(text: String) {
        session.sendText(text)
    }

    /** 테스트용: 연결을 강제로 끊어 재연결 경로를 시험 */
    fun debugDropConnection() {
        session.debugDrop()
    }

    /** 서버 저장용 대화 원문: [{role, text, at}] (RS_NIGHT_RAW.TRANSCRIPT 형식) */
    fun transcriptJson(): JSONArray {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val arr = JSONArray()
        _transcript.value.forEach { l ->
            arr.put(
                JSONObject()
                    .put("role", if (l.role == Role.User) "user" else "assistant")
                    .put("text", l.text)
                    .put("at", fmt.format(java.util.Date(l.atMs)))
            )
        }
        return arr
    }

    // ---------------- 연결 / 재연결 ----------------

    private suspend fun connectOnce(): Boolean {
        val auth = LiveTokenProvider.fetch()
        if (auth == null) {
            _message.value = "토큰을 받지 못했어요"
            return false
        }
        connectOutcome.tryReceive()      // 이전 연결의 잔여 결과 버리기
        session.connect(auth, baseSetup.copy(resumeHandle = lastHandle), ::onEvent)
        val ok = withTimeoutOrNull(LiveConfig.SETUP_TIMEOUT_MS) { connectOutcome.receive() } == true
        if (!ok && _message.value == null) _message.value = "연결 시간이 초과되었어요"
        return ok
    }

    /** maxWaitMs 동안은 AI가 말을 끝낼 때까지 기다렸다가 재연결 (goAway 대응). */
    private fun reconnect(maxWaitMs: Long) {
        if (stopping || !reconnecting.compareAndSet(false, true)) return
        scope.launch {
            try {
                _status.value = Status.Reconnecting
                Log.w(TAG, "재연결 시작 (handle=${lastHandle != null})")
                if (maxWaitMs > 0) withTimeoutOrNull(maxWaitMs) { _aiSpeaking.first { !it } }

                var ok = false
                for (attempt in 1..LiveConfig.RECONNECT_MAX) {
                    if (stopping) return@launch
                    delay(400L * attempt)
                    if (connectOnce()) {
                        ok = true
                        break
                    }
                }
                if (!ok && !stopping) fail("연결이 끊겨 다시 연결하지 못했어요")
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun fail(msg: String) {
        _message.value = msg
        Log.w(TAG, "실패: $msg")
        stop()
        _status.value = Status.Error
    }

    // ---------------- 이벤트 처리 (OkHttp 스레드에서 호출됨) ----------------

    private fun onEvent(e: LiveEvent) {
        when (e) {
            LiveEvent.SetupComplete -> {
                _status.value = Status.Live
                connectOutcome.trySend(true)
                ensureMic()
                // 첫 연결에서만 AI가 먼저 말을 걸도록 함. (재개된 세션에는 보내지 않음)
                if (!kickoffSent) {
                    kickoffSent = true
                    kickoff?.let { session.sendText(it) }
                }
            }

            is LiveEvent.Audio -> audioOut.write(e.pcm)
            is LiveEvent.InputText -> append(Role.User, e.text)
            is LiveEvent.OutputText -> append(Role.Assistant, e.text)
            LiveEvent.Interrupted -> audioOut.clear()
            LiveEvent.TurnComplete -> Unit
            is LiveEvent.ResumptionHandle -> lastHandle = e.handle
            is LiveEvent.GoAway -> reconnect(maxOf(e.timeLeftMs - 8_000L, 0L))
            is LiveEvent.Failure -> _message.value = e.message

            is LiveEvent.Closed -> when (_status.value) {
                Status.Live -> reconnect(0L)
                Status.Connecting, Status.Reconnecting -> connectOutcome.trySend(false)
                else -> Unit
            }

            is LiveEvent.ToolCall -> scope.launch {
                val results = e.calls.map { call ->
                    _toolLog.update { it + "${call.name}(${call.args})" }
                    val out = try {
                        onToolCall(call)
                    } catch (ex: Exception) {
                        JSONObject().put("error", ex.message ?: "error")
                    }
                    ToolResult(call.id, call.name, JSONObject().put("result", out))
                }
                session.sendToolResponse(results)
            }
        }
    }

    private fun append(role: Role, text: String) {
        _transcript.update { list ->
            val last = list.lastOrNull()
            if (last != null && last.role == role) {
                list.dropLast(1) + last.copy(text = last.text + text)
            } else {
                list + Line(role, text, System.currentTimeMillis())
            }
        }
    }

    // ---------------- 오디오 ----------------

    private fun ensureMic() {
        if (micStarted) return
        micStarted = audioIn.start(scope) { chunk -> session.sendAudio(chunk) }
        if (!micStarted) _message.value = "마이크를 열지 못했어요 (권한을 확인해 주세요)"
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                _aiSpeaking.value = audioOut.isSpeaking()
                _userLevel.value = if (audioIn.muted) 0f else audioIn.level
                delay(80)
            }
        }
    }

    /** 통화 모드로 전환해 에코 제거가 동작하게 하고, 스피커폰 여부를 적용. */
    private fun routeAudio(on: Boolean) {
        if (on && !routed) {
            prevMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applySpeaker()
            routed = true
        } else if (!on && routed) {
            setSpeakerphone(false)
            am.mode = prevMode
            routed = false
        }
    }

    private fun applySpeaker() = setSpeakerphone(speakerOn)

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(on: Boolean) {
        am.isSpeakerphoneOn = on
    }
}
