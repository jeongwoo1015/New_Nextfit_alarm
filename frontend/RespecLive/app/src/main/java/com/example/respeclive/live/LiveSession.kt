package com.example.respeclive.live

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "Live"

data class FunctionCall(val id: String, val name: String, val args: JSONObject)

class ToolResult(val id: String, val name: String, val response: JSONObject)

/**
 * 서버(POST /night/token, /morning/sessions/{id}/briefing-token, coaching-token)가 발급한 1회용 임시 토큰.
 * 지시문·함수 선언·목소리는 토큰에 고정돼 있어 앱은 model만 보낸다. 재연결할 때는 새 토큰을 받아야 한다.
 */
class LiveAuth(
    val token: String,
    val wsUrl: String,
    val model: String,
    val startSignal: String     // 연결 직후 realtimeInput.text로 보내는 시작 신호 (받아쓰기에 넣지 않음)
)

sealed interface LiveEvent {
    object SetupComplete : LiveEvent
    class Audio(val pcm: ByteArray) : LiveEvent
    class InputText(val text: String) : LiveEvent      // 사용자 음성 받아쓰기
    class OutputText(val text: String) : LiveEvent     // AI 음성 받아쓰기 (자막)
    object Interrupted : LiveEvent                      // 사용자가 끼어들어 AI 응답이 중단됨
    object TurnComplete : LiveEvent
    class ToolCall(val calls: List<FunctionCall>) : LiveEvent
    class GoAway(val timeLeftMs: Long) : LiveEvent      // 곧 연결이 끊긴다는 예고
    class Closed(val code: Int, val reason: String) : LiveEvent
    class Failure(val message: String) : LiveEvent
}

/** Gemini Live API WebSocket 연결 하나. */
class LiveSession {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 스트리밍 연결: 읽기 타임아웃 없음
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var ready = false

    val isReady: Boolean get() = ready

    @Synchronized
    fun connect(auth: LiveAuth, onEvent: (LiveEvent) -> Unit) {
        close()
        val gen = ++generation
        // OkHttp는 ws(s)://도 받지만 쿼리를 안전하게 붙이려고 http(s)로 바꿔서 조립
        val url = auth.wsUrl.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http").toHttpUrl()
            .newBuilder()
            .addQueryParameter("access_token", auth.token)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                Log.d(TAG, "ws 연결됨 → setup 전송 (model=${auth.model})")
                webSocket.send(buildSetup(auth.model).toString())
            }

            // 서버는 JSON을 텍스트 또는 바이너리 프레임으로 보낼 수 있어 둘 다 처리
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handle(text, onEvent)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handle(bytes.utf8(), onEvent)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                ready = false
                Log.w(TAG, "ws 종료 code=$code reason=$reason")
                onEvent(LiveEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                ready = false
                val httpCode = response?.code
                Log.w(TAG, "ws 실패: ${t.message} http=$httpCode")
                onEvent(LiveEvent.Failure("${t.message ?: "연결 실패"}${httpCode?.let { " (HTTP $it)" } ?: ""}"))
                onEvent(LiveEvent.Closed(httpCode ?: -1, t.message ?: ""))
            }
        }
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    /** 이전 소켓의 이벤트는 이후 무시됨. */
    @Synchronized
    fun close() {
        generation++
        ready = false
        socket?.close(1000, "bye")
        socket = null
    }

    // ---------------- 보내기 ----------------

    private fun sendJson(obj: JSONObject): Boolean = socket?.send(obj.toString()) ?: false

    /** setupComplete 전에는 보내지 않음. (false 반환) */
    fun sendAudio(pcm: ByteArray, len: Int = pcm.size): Boolean {
        if (!ready) return false
        val data = Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP)
        return sendJson(
            JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", data)
                        .put("mimeType", "audio/pcm;rate=${LiveConfig.INPUT_SAMPLE_RATE}")
                )
            )
        )
    }

    fun sendText(text: String): Boolean {
        if (!ready) return false
        return sendJson(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
    }

    /** 마이크를 껐을 때 알림 */
    fun sendAudioStreamEnd(): Boolean {
        if (!ready) return false
        return sendJson(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)))
    }

    fun sendToolResponse(results: List<ToolResult>): Boolean {
        val arr = JSONArray()
        results.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("response", it.response))
        }
        return sendJson(JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)))
    }

    // ---------------- 설정 / 받기 ----------------

    /** 나머지 설정은 토큰에 고정돼 있으므로 model만 보낸다 (AI CONTRACTS 2-1). */
    private fun buildSetup(model: String): JSONObject {
        val name = if (model.startsWith("models/")) model else "models/$model"
        return JSONObject().put("setup", JSONObject().put("model", name))
    }

    private fun handle(raw: String, emit: (LiveEvent) -> Unit) {
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "JSON 파싱 실패")
            return
        }

        if (msg.has("setupComplete")) {
            ready = true
            Log.d(TAG, "setupComplete")
            emit(LiveEvent.SetupComplete)
        }

        msg.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) emit(LiveEvent.Audio(Base64.decode(data, Base64.DEFAULT)))
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let { emit(LiveEvent.InputText(it)) }
            sc.optJSONObject("outputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let { emit(LiveEvent.OutputText(it)) }
            if (sc.optBoolean("interrupted")) {
                Log.d(TAG, "interrupted")
                emit(LiveEvent.Interrupted)
            }
            if (sc.optBoolean("turnComplete")) emit(LiveEvent.TurnComplete)
        }

        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
            val calls = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FunctionCall(o.optString("id"), o.optString("name"), o.optJSONObject("args") ?: JSONObject())
            }
            if (calls.isNotEmpty()) {
                Log.d(TAG, "toolCall ${calls.joinToString { it.name }}")
                emit(LiveEvent.ToolCall(calls))
            }
        }

        msg.optJSONObject("goAway")?.let {
            val ms = parseDurationMs(it.optString("timeLeft"))
            Log.w(TAG, "goAway timeLeft=${ms}ms")
            emit(LiveEvent.GoAway(ms))
        }
    }

    /** "50s", "3.5s" 형태 */
    private fun parseDurationMs(s: String): Long =
        s.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
}
