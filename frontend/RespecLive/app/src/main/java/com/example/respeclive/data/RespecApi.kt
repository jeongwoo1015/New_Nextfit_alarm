package com.example.respeclive.data

import com.example.respeclive.live.LiveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 코칭 토큰 결과. 토큰을 못 받으면 code에 서버 오류 코드 (COACHING_SKIPPED, ALL_STEPS_CHECKED 등) */
class CoachingToken(val auth: LiveAuth?, val steps: JSONArray?, val code: String?)

/** 백엔드 API (backend/README.md). 실패하면 null (이유는 ApiClient.lastError) */
object RespecApi {

    /** Live 토큰을 못 받았을 때 LiveClient가 화면에 이유를 보여 주도록 예외로 바꿈 */
    fun tokenError(): Nothing = throw IllegalStateException(ApiClient.lastError ?: "서버에서 통화 토큰을 받지 못했어요")

    private fun liveAuth(json: JSONObject?): LiveAuth? {
        json ?: return null
        val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
        return LiveAuth(
            token = token,
            wsUrl = json.optString("ws_url"),
            model = json.optString("model"),
            startSignal = json.optString("start_signal")
        )
    }

    /** 폰 캘린더 연동 전까지는 빈 일정을 보냄 (서버는 일정 없이도 토큰을 줌) */
    private fun noEvents() = JSONArray()

    // ---------------- 사용자·온보딩 ----------------

    /** 앱 시작 시: ProfileOut {onboarding_done, user, setting} */
    suspend fun profile(): JSONObject? {
        val r = ApiClient.get("/users/me") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 선택 가능한 루틴 프리셋 (PresetOut 목록: template_id, template_name, target_desc, description, total_sec, items) */
    suspend fun presets(): List<JSONObject>? {
        val r = ApiClient.get("/presets") ?: return null
        val arr = if (r.ok) r.array() else null
        return arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    }

    /** 온보딩 1~3단계 값을 한 번에 저장 (07:00 첫 알람이 자동으로 생김). 이미 마친 사용자(409)도 성공으로 봄 */
    suspend fun onboard(body: JSONObject): Boolean {
        val r = ApiClient.post("/onboarding", body) ?: return false
        return r.ok || r.errorCode == "ONBOARDING_DONE"
    }

    // ---------------- 밤 ----------------

    /** 굿나잇콜 Live 토큰 (지시문: 호칭·말투, 일정, 내일 알람·루틴 항목) */
    suspend fun nightToken(): LiveAuth? {
        val body = JSONObject().put("events_today", noEvents()).put("events_tomorrow", noEvents())
        val r = ApiClient.post("/night/token", body, ApiConfig.TOKEN_TIMEOUT_MS) ?: return null
        return if (r.ok) liveAuth(r.json()) else null
    }

    /** 대화 원문 저장 → NightRecordOut (night_record_id, apply_date). 추출은 서버 백그라운드 */
    suspend fun saveTranscript(transcript: JSONArray, startedAt: String?, endedAt: String?): JSONObject? {
        val body = JSONObject().put("transcript", transcript)
        startedAt?.let { body.put("started_at", it) }
        endedAt?.let { body.put("ended_at", it) }
        val r = ApiClient.post("/night/transcript", body) ?: return null
        return if (r.ok) r.json() else null
    }

    /** '내일의 나에게 한 마디' m4a 업로드 */
    suspend fun uploadVoice(nightRecordId: String, file: File): Boolean {
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return false
        return ApiClient.putBytes("/night/records/$nightRecordId/voice", bytes, "audio/mp4", ApiConfig.TOKEN_TIMEOUT_MS)?.ok == true
    }

    /** 밤 기록 (voice_url: 서명된 임시 URL) */
    suspend fun nightRecord(nightRecordId: String): JSONObject? {
        val r = ApiClient.get("/night/records/$nightRecordId") ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 아침 ----------------

    /**
     * 앱의 로컬 알람에 대응하는 서버 alarm_id. 시각이 같은 켜진 알람을 먼저 찾고, 없으면 첫 번째 켜진 알람.
     * (로컬 알람과 서버 알람 동기화 전까지의 임시 연결)
     */
    suspend fun serverAlarmId(hour: Int?, minute: Int?): String? {
        val r = ApiClient.get("/alarms") ?: return null
        val arr = if (r.ok) r.array() else null
        arr ?: return null
        val alarms = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("enabled", true) }
        val hhmm = if (hour != null && minute != null) "%02d:%02d".format(hour, minute) else null
        val match = alarms.firstOrNull { hhmm != null && it.optString("alarm_time") == hhmm } ?: alarms.firstOrNull()
        return match?.optString("alarm_id")?.takeIf { it.isNotBlank() }
    }

    /** 오늘 세션 만들기(또는 기존 세션) → SessionViewOut {session, steps, progress} */
    suspend fun startMorning(alarmId: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions", JSONObject().put("alarm_id", alarmId)) ?: return null
        return if (r.ok) r.json() else null
    }

    suspend fun todaySession(): JSONObject? {
        val r = ApiClient.get("/morning/sessions/today") ?: return null
        return if (r.ok) r.json() else null
    }

    suspend fun briefingToken(sessionId: String): LiveAuth? {
        val body = JSONObject().put("events_today", noEvents())
        val r = ApiClient.post("/morning/sessions/$sessionId/briefing-token", body, ApiConfig.TOKEN_TIMEOUT_MS) ?: return null
        return if (r.ok) liveAuth(r.json()) else null
    }

    /** start_routine 뒤(재연결도) 호출. 처음이면 서버가 단계를 PENDING으로 만듦 */
    suspend fun coachingToken(sessionId: String): CoachingToken {
        val r = ApiClient.post("/morning/sessions/$sessionId/coaching-token", JSONObject(), ApiConfig.TOKEN_TIMEOUT_MS)
            ?: return CoachingToken(null, null, null)
        if (!r.ok) return CoachingToken(null, null, r.errorCode)
        val json = r.json()
        return CoachingToken(liveAuth(json), json?.optJSONArray("steps"), null)
    }

    /** Live report_step_result 전달 → SessionViewOut */
    suspend fun reportVerdict(sessionId: String, stepOrder: Int, verdict: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/steps/$stepOrder/verdict", JSONObject().put("verdict", verdict))
            ?: return null
        return if (r.ok) r.json() else null
    }

    /** 체크리스트에서 직접 체크: DONE / SKIPPED / PENDING → SessionViewOut */
    suspend fun markStep(sessionId: String, stepOrder: Int, status: String): JSONObject? {
        val r = ApiClient.patch("/morning/sessions/$sessionId/steps/$stepOrder", JSONObject().put("step_status", status))
            ?: return null
        return if (r.ok) r.json() else null
    }

    /** 알람 후 첫 음성 응답 (서버가 처음 한 번만 기록) */
    suspend fun firstResponse(sessionId: String): Boolean =
        ApiClient.post("/morning/sessions/$sessionId/response")?.ok == true

    /** 로컬 알람에 대응하는 서버 알람으로 오늘 세션 시작. 알람이 울릴 때(스누즈 뒤 다시 울릴 때도) 호출 → 같은 세션 */
    suspend fun startMorningFor(hour: Int?, minute: Int?): JSONObject? =
        serverAlarmId(hour, minute)?.let { startMorning(it) }

    /** "5분 뒤 다시 울리기" (한 번이라도 하면 그날은 기상 실패) */
    suspend fun snooze(sessionId: String): Boolean =
        ApiClient.post("/morning/sessions/$sessionId/snooze")?.ok == true

    /** AI 질문 뒤 8초 무응답으로 재기상 음악을 틀 때마다 */
    suspend fun rewake(sessionId: String): Boolean =
        ApiClient.post("/morning/sessions/$sessionId/rewake")?.ok == true

    /** 브리핑 확인 질문에 사용자가 처음 대답했을 때 (AI CONTRACTS 6-1) → 서버가 기상 성공 판정. SessionViewOut */
    suspend fun wakeCheck(sessionId: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/wake-check") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 코칭 중 adjust_step_time 전달: 오늘만 단계 시간 변경 (30초 단위, 0 = 오늘만 빼기) → SessionViewOut. 거절이면 null */
    suspend fun changeStepDuration(sessionId: String, stepOrder: Int, durationSec: Int): JSONObject? {
        val r = ApiClient.patch("/morning/sessions/$sessionId/steps/$stepOrder/duration",
            JSONObject().put("duration_sec", durationSec)) ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 음악 ----------------

    /** 음악 서명 URL. type: "rewake"(재기상, 12시간 유효) / "sleep"(수면). 없으면(404 MUSIC_NOT_FOUND) null */
    suspend fun musicUrl(type: String): String? {
        val r = ApiClient.get("/music/$type") ?: return null
        return if (r.ok) r.json()?.optString("url")?.takeIf { it.isNotBlank() } else null
    }

    /** end_session을 받았거나 사용자가 통화를 끊었을 때 */
    suspend fun endMorning(sessionId: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/end") ?: return null
        return if (r.ok) r.json() else null
    }
}
