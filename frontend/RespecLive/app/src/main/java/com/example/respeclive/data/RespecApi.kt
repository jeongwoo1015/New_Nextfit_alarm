package com.example.respeclive.data

import com.example.respeclive.live.LiveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 코칭 토큰 결과. 토큰을 못 받으면 code에 서버 오류 코드 (COACHING_SKIPPED, ALL_STEPS_CHECKED 등) */
/** 말로 미루기 결과: snoozedUntilMs가 있으면 성공, 없으면 code(SNOOZE_LIMIT 등, 연결 실패면 null) */
/** 끝말잇기 한 턴 AI 판단: valid면 next(넥스티 단어, 없으면 넥스티 패배), 아니면 reason(보여 줄 이유) */
class WordChainTurn(val valid: Boolean, val next: String?, val reason: String?)

/** snoozeCount: 이 세션(알람)의 미룬 횟수, snoozesToday: 오늘 모든 알람의 합계 */
class SnoozeResult(val snoozedUntilMs: Long?, val code: String?, val snoozesToday: Int? = null, val snoozeCount: Int? = null)

class CoachingToken(val auth: LiveAuth?, val steps: JSONArray?, val code: String?)

/**
 * 포춘 토큰 결과. auth가 null이면 포춘 단계를 건너뜀 (code: 서버 오류 코드, 503이면 AI_UNAVAILABLE).
 * text: AI가 읽을 문장(자막용), style: COOKIE 포춘쿠키 / HOROSCOPE 오늘의 운세, source: ai / fallback / safe
 */
class FortuneToken(val auth: LiveAuth?, val text: String?, val style: String?, val source: String?, val code: String?)

/** 백엔드 API (backend/README.md). 실패하면 null (이유는 ApiClient.lastError) */
object RespecApi {
    /** 끝말잇기 AI 판단을 기다리는 최대 시간 (넘으면 앱 규칙으로) */
    private const val WORDCHAIN_TURN_TIMEOUT_MS = 6_000L

    /** Live 토큰을 못 받았을 때 LiveClient가 화면에 이유를 보여 주도록 예외로 바꿈 */
    fun tokenError(): Nothing = throw IllegalStateException(ApiClient.lastError ?: "서버에서 통화 토큰을 받지 못했어요.")

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

    // ---------------- 사용자·온보딩 ----------------

    /** 앱 시작 시: ProfileOut {onboarding_done, user, setting} */
    suspend fun profile(): JSONObject? {
        val r = ApiClient.get("/users/me") ?: return null
        // 기상 알람 시작 단계용으로 말로 정한 볼륨 값을 기기에 저장 (알람이 울릴 때 인터넷을 기다리지 않도록)
        return if (r.ok) r.json()?.also { WakeLevelPrefs.update(it.optJSONObject("setting")) } else null
    }

    /** 기본 정보 수정 (user_name, birth_date, gender 중 보낸 항목만) → UserOut */
    suspend fun updateUser(changes: JSONObject): JSONObject? {
        val r = ApiClient.patch("/users/me", changes) ?: return null
        return if (r.ok) r.json() else null
    }

    /**
     * 회원 탈퇴: 서버가 목소리·음성·사진 파일과 계정을 지움 (되돌릴 수 없음). 성공하면 true.
     * 파일을 지우느라 오래 걸릴 수 있어 기다리는 시간을 넉넉히
     */
    suspend fun deleteAccount(): Boolean {
        val r = ApiClient.delete("/users/me", timeoutMs = 60_000L) ?: return false
        return r.ok
    }

    /**
     * 설정 수정 (user_call_name, tone, personality, intensity, auto_intensity, english_level, voice_type, music_genre, theme 중
     * 보낸 항목만) → SettingOut
     */
    suspend fun updateSetting(changes: JSONObject): JSONObject? {
        val r = ApiClient.patch("/settings", changes) ?: return null
        return if (r.ok) r.json()?.also { WakeLevelPrefs.update(it) } else null
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

    // ---------------- 알람 ----------------

    /** 내 알람 목록 (AlarmOut: alarm_id, alarm_time "HH:MM", repeat_days "1111100", label, alarm_mode, alarm_kind WAKE/BEDTIME, morning_call_type, ...) */
    suspend fun alarms(): List<JSONObject>? {
        val r = ApiClient.get("/alarms") ?: return null
        val arr = if (r.ok) r.array() else null
        return arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    }

    /** 알람 만들기 (모닝콜: morning_call_type 필수, 루틴이면 template_id, 콘텐츠면 content_type / 굿나잇콜: alarm_kind BEDTIME과 시각만) → AlarmOut */
    suspend fun createAlarm(body: JSONObject): JSONObject? {
        val r = ApiClient.post("/alarms", body) ?: return null
        return if (r.ok) r.json() else null
    }

    /** 보낸 항목만 바꿈 (켜기/끄기는 {"enabled": false}) → AlarmOut */
    suspend fun updateAlarm(alarmId: String, changes: JSONObject): JSONObject? {
        val r = ApiClient.patch("/alarms/$alarmId", changes) ?: return null
        return if (r.ok) r.json() else null
    }

    /** 이미 지워진 알람(404)도 성공으로 봄 */
    suspend fun deleteAlarm(alarmId: String): Boolean {
        val r = ApiClient.delete("/alarms/$alarmId") ?: return false
        return r.ok || r.code == 404
    }

    /** 프리셋 하나 (비활성 프리셋도 받음) → PresetOut */
    suspend fun preset(templateId: String): JSONObject? {
        val r = ApiClient.get("/presets/$templateId") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 앱 설정 → LimitsOut {routine_max_sec, duration_step_sec, content_duration_sec} */
    suspend fun limits(): JSONObject? {
        val r = ApiClient.get("/config/limits") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 루틴 알람의 항목별 시간 → AlarmRoutineOut {alarm_id, template_id, total_sec, max_sec, items} */
    suspend fun alarmRoutine(alarmId: String): JSONObject? {
        val r = ApiClient.get("/alarms/$alarmId/routine-items") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 항목 시간 전체 교체. items: [{template_item_id, duration_sec}] (보내지 않은 항목은 기본 시간) → AlarmRoutineOut */
    suspend fun saveAlarmRoutine(alarmId: String, items: JSONArray): JSONObject? {
        val r = ApiClient.put("/alarms/$alarmId/routine-items", JSONObject().put("items", items)) ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 밤 ----------------

    /** 굿나잇콜 Live 토큰 (지시문: 호칭·말투, 일정, 내일 알람·루틴 항목). 일정은 CalendarEvents 형식 */
    suspend fun nightToken(eventsToday: JSONArray, eventsTomorrow: JSONArray): LiveAuth? {
        val body = JSONObject().put("events_today", eventsToday).put("events_tomorrow", eventsTomorrow)
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

    /** 업로드한 한 마디를 기기에서 받아쓴 글 (다음 날 포춘의 재료). 다시 녹음·업로드하면 서버가 글을 비우므로 업로드마다 다시 보냄 */
    suspend fun saveVoiceText(nightRecordId: String, text: String): Boolean =
        ApiClient.patch("/night/records/$nightRecordId/voice-text", JSONObject().put("text", text))?.ok == true

    /** 밤 기록 (voice_url: 서명된 임시 URL) */
    suspend fun nightRecord(nightRecordId: String): JSONObject? {
        val r = ApiClient.get("/night/records/$nightRecordId") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 밤 날짜(RECORD_DATE "YYYY-MM-DD")로 밤 기록 조회. 없으면(404) null */
    suspend fun nightRecordByDate(recordDate: String): JSONObject? {
        val r = ApiClient.get("/night/records?record_date=$recordDate") ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 아침 ----------------

    /**
     * 서버와 연결되지 않은 로컬 알람(동기화 전)에 대응하는 서버 alarm_id.
     * 시각이 같은 켜진 모닝콜 알람을 먼저 찾고, 없으면 첫 번째 켜진 모닝콜 알람 (굿나잇콜로는 모닝콜을 시작할 수 없음).
     */
    private suspend fun serverAlarmId(hour: Int?, minute: Int?): String? {
        val alarms = alarms()?.filter { it.optBoolean("enabled", true) && it.optString("alarm_kind", "WAKE") != "BEDTIME" } ?: return null
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

    /** 아침 브리핑 Live 토큰. 일정은 CalendarEvents 형식. 위치가 없으면 위치 칸은 보내지 않음 (날씨 없이 브리핑) */
    suspend fun briefingToken(sessionId: String, eventsToday: JSONArray, place: DeviceLocation.Place?): LiveAuth? {
        val body = JSONObject().put("events_today", eventsToday)
        // name을 비우면 서버가 날씨 API의 영어 이름(Seoul)을 씀
        if (place != null) {
            body.put("location", JSONObject().put("lat", place.lat).put("lon", place.lon).put("name", place.name.orEmpty()))
        }
        val r = ApiClient.post("/morning/sessions/$sessionId/briefing-token", body, ApiConfig.TOKEN_TIMEOUT_MS) ?: return null
        return if (r.ok) liveAuth(r.json()) else null
    }

    /** 포춘 단계(어젯밤 한 마디 → 포춘 → 코칭)에서 호출. 재연결해도 같은 문장 */
    suspend fun fortuneToken(sessionId: String): FortuneToken {
        val r = ApiClient.post("/morning/sessions/$sessionId/fortune-token", JSONObject(), ApiConfig.TOKEN_TIMEOUT_MS)
            ?: return FortuneToken(null, null, null, null, null)
        if (!r.ok) return FortuneToken(null, null, null, null, r.errorCode ?: if (r.code == 503) "AI_UNAVAILABLE" else null)
        val json = r.json()
        fun str(key: String) = json?.takeUnless { it.isNull(key) }?.optString(key)?.takeIf { it.isNotBlank() }
        return FortuneToken(liveAuth(json), str("fortune_text"), str("fortune_style"), str("fortune_source"), null)
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
    suspend fun startMorningFor(alarm: Alarm?): JSONObject? =
        (alarm?.serverId ?: serverAlarmId(alarm?.hour, alarm?.minute))?.let { startMorning(it) }

    /**
     * 미루기. 버튼은 둘 다 null(서버가 5분), 말로 미루기(AI reschedule_alarm, CONTRACTS B21)는 minutesLater(1~60) 또는
     * atTime("HH:MM", 오늘 60분 안) 중 하나.
     * 성공하면 다시 울릴 시각(snoozed_until), 실패하면 code (한도를 넘으면 SNOOZE_LIMIT, 연결 실패면 null)
     */
    suspend fun snoozeTo(sessionId: String, minutesLater: Int?, atTime: String?): SnoozeResult {
        val body = JSONObject()
        minutesLater?.let { body.put("minutes_later", it) }
        atTime?.let { body.put("at_time", it) }
        val r = ApiClient.post("/morning/sessions/$sessionId/snooze", body) ?: return SnoozeResult(null, null)
        if (!r.ok) return SnoozeResult(null, r.errorCode ?: "HTTP_${r.code}")
        val json = r.json()
        val until = json?.optString("snoozed_until")?.takeIf { it.isNotBlank() }?.let { parseServerTime(it) }
        return SnoozeResult(until, null, json?.optInt("snoozes_today", -1)?.takeIf { it >= 0 }, json?.optInt("snooze_count", -1)?.takeIf { it >= 0 })
    }

    /**
     * 아침 기상 볼륨 결과 보고 (AI CONTRACTS B23 학습 입력): 알람이 울리기 시작한 단계와 반응한 순간의 단계(1~5).
     * 반응한 단계 = 전화를 받거나 미루기 버튼을 누른 순간의 단계(11/25). 받지 않고 껐으면 null.
     * 서버는 그날 첫 보고만 남기고, 다시 울린 알람의 보고는 무시함 (미룬 날은 서버가 볼륨 학습에서 실패로 봄)
     */
    suspend fun reportWakeResult(sessionId: String, startLevel: Int, receivedLevel: Int?): Boolean {
        val body = JSONObject().put("start_level", startLevel).put("received_level", receivedLevel ?: JSONObject.NULL)
        return ApiClient.post("/morning/sessions/$sessionId/wake-volume", body)?.ok == true
    }

    /** 다음 아침 첫 모닝콜의 시작 볼륨·재기상 볼륨 → WakeNextOut {session_date, start_level, rewake_level, ...}. 다음 알람이 없으면(404) null */
    suspend fun wakeVolumeNext(): JSONObject? {
        val r = ApiClient.get("/wake-volume/next") ?: return null
        return if (r.ok) r.json() else null
    }

    /**
     * 게임 기록(주고받은 횟수, 앱이 셈) 저장 → GameScoreOut {score, best_score, is_best, user_wins?}. 게임 세션이 아니면 null.
     * winner: 끝말잇기 승패 "USER"/"NEXTY" (승패가 안 났으면 보내지 않음). 서버에 요청한 필드(11/26):
     * 서버가 받으면 넥스티를 이긴 횟수(user_wins)를 돌려줌. 아직 모르는 서버는 이 필드를 무시함
     */
    suspend fun saveGameScore(sessionId: String, stepOrder: Int, score: Int, winner: String? = null): JSONObject? {
        val body = JSONObject().put("score", score)
        if (winner != null) body.put("winner", winner)
        val r = ApiClient.put("/morning/sessions/$sessionId/steps/$stepOrder/score", body) ?: return null
        return if (r.ok) r.json() else null
    }

    /**
     * 끝말잇기 한 턴을 AI가 판단 (서버 API 준비 전: 요청한 형식). words: 지금까지 이어진 단어(순서대로), word: 이번에 낸 단어.
     * 실패(연결·시간 초과·API 없음)면 null → 앱 규칙과 단어 목록으로 이어 감
     */
    suspend fun wordChainTurn(words: List<String>, word: String): WordChainTurn? {
        val body = JSONObject().put("words", JSONArray(words)).put("word", word)
        val r = ApiClient.post("/wordchain/turn", body, WORDCHAIN_TURN_TIMEOUT_MS) ?: return null
        if (!r.ok) return null
        val json = r.json() ?: return null
        if (!json.has("valid")) return null
        return WordChainTurn(
            valid = json.optBoolean("valid"),
            next = json.optString("next").takeIf { !json.isNull("next") && it.isNotBlank() },
            reason = json.optString("reason").takeIf { !json.isNull("reason") && it.isNotBlank() }
        )
    }

    /** 대화로 설정 바꾸기 (AI update_setting, CONTRACTS B22): 인자를 그대로 서버에 → 바뀐 설정, 거절·실패면 null */
    /**
     * 모닝콜에서 사용자가 말한 잠든 시각 저장 (AI report_bedtime, 11/26 서버에 요청한 API: 아직 없으면 404로 실패하고 끝).
     * PUT /morning/sessions/{id}/bedtime {bed_time: "HH:MM"}. 서버가 어젯밤 취침 시각을 이 값으로 다시 기록함
     */
    suspend fun reportBedtime(sessionId: String, time: String): Boolean {
        val r = ApiClient.put("/morning/sessions/$sessionId/bedtime", JSONObject().put("bed_time", time)) ?: return false
        return r.ok
    }

    suspend fun aiChangeSetting(field: String, value: String, scope: String?): JSONObject? {
        val body = JSONObject().put("field", field).put("value", value)
        scope?.takeIf { it.isNotBlank() }?.let { body.put("scope", it) }
        val r = ApiClient.post("/settings/ai-change", body) ?: return null
        return if (r.ok) r.json()?.also { WakeLevelPrefs.update(it) } else null
    }

    /** 서버 시각(ISO, 시간대 포함) → epoch ms */
    private fun parseServerTime(iso: String): Long? = runCatching {
        val clean = iso.replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+00:00")
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(clean)?.time
    }.getOrNull()

    /** AI 질문 뒤 8초 무응답으로 재기상 음악을 틀 때마다 */
    suspend fun rewake(sessionId: String): Boolean =
        ApiClient.post("/morning/sessions/$sessionId/rewake")?.ok == true

    /** 브리핑 확인 질문에 사용자가 처음 대답했을 때 (AI CONTRACTS 6-1) → 서버가 기상 성공 판정. SessionViewOut */
    suspend fun wakeCheck(sessionId: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/wake-check") ?: return null
        return if (r.ok) r.json() else null
    }

    /**
     * 코칭 중 select_stretch_area 전달: 아직 판정하지 않은 단계를 그 부위 루틴으로 바꿈 → SessionViewOut.
     * area: AI가 고른 값을 그대로 보냄 (11/18부터 12개: FULL / NECK / SHOULDER / CHEST_BACK / WRIST_HAND / ELBOW_ARM / LOW_BACK /
     * PELVIS / GLUTE / THIGH / CALF / ANKLE_FOOT, 옛 5개 값은 서버가 바꿔 읽음). 이미 판정한 단계가 있거나 부위를 골랐으면 409 → null
     */
    suspend fun selectStretchArea(sessionId: String, area: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/stretch-area", JSONObject().put("area", area)) ?: return null
        return if (r.ok) r.json() else null
    }

    /** 코칭 중 adjust_step_time 전달: 오늘만 단계 시간 변경 (30초 단위, 0 = 오늘만 빼기) → SessionViewOut. 거절이면 null */
    suspend fun changeStepDuration(sessionId: String, stepOrder: Int, durationSec: Int): JSONObject? {
        val r = ApiClient.patch("/morning/sessions/$sessionId/steps/$stepOrder/duration",
            JSONObject().put("duration_sec", durationSec)) ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 리포트 ----------------

    /**
     * period "week"/"month"/"year" (오늘이 속한 기간) → ReportOut
     * {overall(전체 누적), totals(기간 합계), series[{label, avg_wake_clock, routine_done/total, routine_completion_rate, ...}],
     *  current_streak, best_streak}. 비율은 0~1, 기록이 없으면 null
     */
    suspend fun report(period: String): JSONObject? {
        val r = ApiClient.get("/reports?period=$period") ?: return null
        return if (r.ok) r.json() else null
    }

    // ---------------- 콘텐츠 화면 ----------------

    /**
     * 그날 콘텐츠 단계의 내용 (서버 API 준비 전: 백엔드에 요청한 형식, 없거나 실패하면 null).
     * GET /contents/steps?date=YYYY-MM-DD → [{session_id, step_order, step_name, step_status, content_type, content_option,
     *   stretch_area, script, words, score}]
     * script: 스트레칭 동작 설명 / 영어 "영어 / 뜻 / 발음" / 끝말잇기 "시작 단어: 기차" / 초성 "초성: ㄱㅊ",
     * words: 게임에서 말한 단어 목록(끝말잇기·초성), score: 게임 기록
     */
    suspend fun contentSteps(date: String): JSONArray? {
        val r = ApiClient.get("/contents/steps?date=$date") ?: return null
        if (!r.ok) return null
        return r.json()?.optJSONArray("steps") ?: runCatching { JSONArray(r.body) }.getOrNull()
    }

    // ---------------- 한 줄 일기 ----------------

    /** 달력: month "YYYY-MM" → CalendarDayOut 목록 (date, photo_url, routine_completion_rate 0~1, routine_done/total, ...) */
    suspend fun diaryCalendar(month: String): List<JSONObject>? {
        val r = ApiClient.get("/diary/calendar?month=$month") ?: return null
        val arr = if (r.ok) r.array() else null
        return arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    }

    /** 날짜 상세: DiaryDayOut {date, summary, night(한 줄 일기 등), sessions[{session, steps}], photo_url} */
    suspend fun diaryDay(date: String): JSONObject? {
        val r = ApiClient.get("/diary/$date") ?: return null
        return if (r.ok) r.json() else null
    }

    /** 일기 사진 올리기 (JPEG 본문 그대로, 다시 올리면 덮어씀) → 새 photo_url. 실패하면 null */
    suspend fun uploadDiaryPhoto(date: String, jpeg: ByteArray): String? {
        val r = ApiClient.putBytes("/diary/$date/photo", jpeg, "image/jpeg", ApiConfig.TOKEN_TIMEOUT_MS) ?: return null
        return if (r.ok) r.json()?.optString("photo_url")?.takeIf { it.isNotBlank() && it != "null" } else null
    }

    /** 일기 사진 지우기 (사진이 없어도 성공) */
    suspend fun deleteDiaryPhoto(date: String): Boolean {
        val r = ApiClient.delete("/diary/$date/photo") ?: return false
        return r.ok
    }

    // ---------------- 음악 ----------------

    /** 음악 서명 URL. type: "rewake"(재기상, 12시간 유효) / "sleep"(수면). 없으면(404 MUSIC_NOT_FOUND) null */
    suspend fun musicUrl(type: String): String? {
        val r = ApiClient.get("/music/$type") ?: return null
        return if (r.ok) r.json()?.optString("url")?.takeIf { it.isNotBlank() } else null
    }

    /**
     * 장르를 고를 때 미리 듣는 재기상 음악 (GET /music/rewake?genre=). 응답의 genre가 고른 장르와 같을 때만 URL.
     * 서버가 genre 파라미터를 아직 모르면 설정 장르 곡을 주거나(다르면 버림) 온보딩 전이라 404 → null
     */
    suspend fun rewakeSample(genre: String): String? {
        val r = ApiClient.get("/music/rewake?genre=$genre") ?: return null
        val j = if (r.ok) r.json() else null
        if (j?.optString("genre") != genre) return null
        return j.optString("url").takeIf { it.isNotBlank() }
    }

    /** 수면 음악 한 곡 (music_id로 곡이 바뀌었는지 비교) */
    data class SleepTrack(val musicId: String, val url: String)

    /**
     * 수면 음악 (GET /music/sleep). mood: GOOD / NORMAL / TIRED / STRESSED.
     * null이면 서버가 오늘 밤 기록의 컨디션으로 고름(추출 전이면 NORMAL). 없으면(404) null
     */
    suspend fun sleepMusic(mood: String?): SleepTrack? {
        val r = ApiClient.get("/music/sleep" + (mood?.let { "?mood=$it" } ?: "")) ?: return null
        val j = if (r.ok) r.json() else null
        val url = j?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
        return SleepTrack(j.optString("music_id"), url)
    }

    /**
     * 모닝콜을 순서표 끝(포춘까지)까지 마쳤을 때 한 번 (11/27 서버, flow_completed_at). 리포트·일기의 기상 성공은 이 호출이 온 세션만 셈.
     * 통화 종료 버튼·말로 미루기·연결 끊김처럼 중간에 끝났으면 부르지 않음
     */
    suspend fun completeMorning(sessionId: String): Boolean =
        ApiClient.post("/morning/sessions/$sessionId/complete")?.ok == true

    /** end_session을 받았거나 사용자가 통화를 끊었을 때 */
    suspend fun endMorning(sessionId: String): JSONObject? {
        val r = ApiClient.post("/morning/sessions/$sessionId/end") ?: return null
        return if (r.ok) r.json() else null
    }
}
