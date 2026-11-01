"""밤 흐름: Live 토큰 발급 → (앱이 Gemini Live로 대화) → 원문 저장 → 밤 기록 추출 → 목소리 업로드.

추출은 AI 호출이라 수십 초 걸릴 수 있어 원문 저장 응답 뒤 백그라운드에서 한다(앱은 EXTRACT_STATUS로 확인).
"""
import logging
from dataclasses import dataclass, field
from datetime import date, datetime

from backend.clients.ai_gateway import AIGateway, CalendarEvent, LiveToken, NightContext, Persona
from backend.core.errors import AppError, NotFound, ValidationFailed, check_media_type
from backend.core.timeutil import now_kst, to_kst
from backend.domain import codes
from backend.domain.alarm_rules import pick_override_alarm
from backend.domain.models import Alarm, NightRecord
from backend.domain.routine_rules import apply_changes, overrides_of, tomorrow_rows
from backend.repositories.registry import Repositories

log = logging.getLogger(__name__)

MAX_VOICE_BYTES = 10 * 1024 * 1024


@dataclass(frozen=True)
class ExtractOutcome:
    status: str                                     # DONE / FAILED
    override_alarm_id: str | None = None
    dropped_override: bool = False                  # 변경에 동의했지만 내일 울릴 알람이 없어 저장하지 않음
    routine_tomorrow: list[str] = field(default_factory=list)     # 내일만 바꾼 항목 (rs_night_routine_item)
    routine_always: list[str] = field(default_factory=list)       # 앞으로 계속 바꾼 항목 (알람 설정)
    routine_rejected: list[str] = field(default_factory=list)
    error: str = ""


class NightService:
    def __init__(self, repos: Repositories, ai: AIGateway, clock=now_kst):
        self.r, self.ai, self.clock = repos, ai, clock

    def _persona(self, user_id: str) -> Persona:
        s = self.r.users.get_setting(user_id)
        if not s:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        return Persona(s.user_call_name, s.tone, s.speech_style, s.voice_type)

    def _tomorrow_alarm(self, user_id: str, at: datetime) -> Alarm | None:
        """하루 한정 변경과 대화로 바꾼 루틴 항목을 적용할 알람 = 밤 기록이 적용되는 아침에 가장 먼저 울리는 알람.
        이 알람을 밤 기록의 OVERRIDE_ALARM_ID로 항상 남긴다(변경값이 없어도) — 내일만 바꾼 항목이 이 알람에 묶이기 때문."""
        return pick_override_alarm(self.r.alarms.list_alarms(user_id), at)

    def issue_token(self, user_id: str, events_today: list[CalendarEvent], events_tomorrow: list[CalendarEvent]) -> LiveToken:
        now = to_kst(self.clock())
        alarm = self._tomorrow_alarm(user_id, now)
        items = ()
        if alarm and alarm.morning_call_type == "ROUTINE":
            items = tuple(i.item_name for i in (self.r.alarms.get_alarm_routine(user_id, alarm.alarm_id) or []) if i.duration_sec > 0)
        ctx = NightContext(self._persona(user_id), now.strftime("%H:%M"), tuple(events_today), tuple(events_tomorrow),
                           alarm.alarm_time.strftime("%H:%M") if alarm else "", items)
        return self.ai.issue_night_token(ctx)

    def save_transcript(self, user_id: str, transcript: list[dict], started_at: datetime | None,
                        ended_at: datetime | None) -> NightRecord:
        """원문 저장(EXTRACT_STATUS = PENDING). 같은 밤에 다시 보내면 덮어쓴다. 추출은 extract()로 따로."""
        if not any(t.get("role") == "user" and str(t.get("text") or "").strip() for t in transcript):
            raise ValidationFailed("사용자 발화가 없는 대화예요", "EMPTY_TRANSCRIPT")
        self._persona(user_id)   # 온보딩 전 사용자는 rs_user 행이 없어 저장할 수 없다
        night_record_id = self.r.nights.save_night_raw(user_id, transcript, started_at, ended_at)
        return self.r.nights.get_night(user_id, night_record_id)

    def extract(self, user_id: str, night_record_id: str, saved_at: datetime | None = None) -> ExtractOutcome:
        """원문 → 밤 기록 항목 + 하루 한정 변경 대상 알람 + 대화로 바꾼 루틴 항목. 백그라운드·재추출 배치에서 부른다."""
        raw = self.r.nights.get_night_raw(night_record_id)
        if not raw or not raw.transcript:
            self.r.nights.mark_extract_failed(user_id, night_record_id)
            return ExtractOutcome("FAILED", error="원문이 없어요")
        try:
            result = self.ai.extract_night_record(raw.transcript)
        except AppError as e:
            result = None
            error = e.message
        if result is None or result.status != "DONE":
            self.r.nights.mark_extract_failed(user_id, night_record_id)
            return ExtractOutcome("FAILED", error=result.error if result else error)

        at = to_kst(saved_at or self.clock())
        alarm = self._tomorrow_alarm(user_id, at)
        fields = dict(result.fields)
        wants_override = bool(fields.get("override_wake_time") or fields.get("override_skip_coaching"))
        dropped = wants_override and alarm is None
        if dropped:   # 대상 알람 없이 변경값만 저장할 수 없다 (CHECK rs_night_override_alarm)
            fields.update(override_wake_time=None, override_skip_coaching=False)
        target = alarm.alarm_id if alarm else None
        self.r.nights.save_night_extract(user_id, night_record_id, fields, target)

        outcome = ExtractOutcome("DONE", target, dropped)
        if alarm and alarm.morning_call_type == "ROUTINE":
            outcome = self._apply_routine_changes(user_id, night_record_id, alarm, raw.transcript, outcome)
        return outcome

    def _apply_routine_changes(self, user_id: str, night_record_id: str, alarm: Alarm, transcript: list,
                               outcome: ExtractOutcome) -> ExtractOutcome:
        """대화로 바꾼 루틴 항목을 범위별로 저장한다 (정의서 11/01 변경 범위).
        앞으로 계속 → 알람 설정(rs_alarm_routine_item), 내일만 → rs_night_routine_item(알람 설정 위에 덮어씀).
        규칙(30초 단위·합계 상한·최소 한 항목)에 걸리는 요청은 건너뛴다. 실패해도 밤 기록 저장은 그대로 둔다."""
        try:
            items = self.r.nights.get_night_routine(user_id, night_record_id) or []
            req = self.ai.parse_routine_changes(transcript, items)
            if req.status != "DONE" or not req.changes:
                return outcome
            max_sec = self.r.presets.get_app_config().routine_max_sec
            always = [(c.template_item_id, c.duration_sec) for c in req.changes if c.scope == "ALWAYS"]
            tomorrow = [(c.template_item_id, c.duration_sec) for c in req.changes if c.scope != "ALWAYS"]
            done_always, done_tomorrow, rejected = [], [], []
            if always:
                r = apply_changes(self.r.alarms.get_alarm_routine(user_id, alarm.alarm_id) or [], always, max_sec)
                if r.applied:
                    self.r.alarms.replace_alarm_routine(user_id, alarm.alarm_id, overrides_of(r.items))
                done_always, rejected = r.applied, rejected + r.rejected
            if tomorrow:   # 알람 설정이 바뀌었을 수 있으니 다시 읽는다
                r = apply_changes(self.r.nights.get_night_routine(user_id, night_record_id) or [], tomorrow, max_sec,
                                  restore_to_alarm=True)
                if r.applied:
                    self.r.nights.replace_night_routine(user_id, night_record_id, tomorrow_rows(r.items))
                done_tomorrow, rejected = r.applied, rejected + r.rejected
            return ExtractOutcome(outcome.status, outcome.override_alarm_id, outcome.dropped_override,
                                  done_tomorrow, done_always, rejected)
        except AppError as e:
            log.warning("루틴 변경 반영 실패 user=%s alarm=%s: %s", user_id, alarm.alarm_id, e.message)
            return outcome

    def get_record(self, user_id: str, night_record_id: str) -> NightRecord:
        rec = self.r.nights.get_night(user_id, night_record_id)
        if not rec:
            raise NotFound("밤 기록을 찾을 수 없어요", "NIGHT_RECORD_NOT_FOUND")
        return rec

    def get_record_by_date(self, user_id: str, record_date: date) -> NightRecord:
        rec = self.r.nights.get_night_by_date(user_id, record_date)
        if not rec:
            raise NotFound("그날 밤 기록이 없어요", "NIGHT_RECORD_NOT_FOUND")
        return rec

    def save_voice(self, user_id: str, night_record_id: str, data: bytes, content_type: str | None = "audio/mp4") -> NightRecord:
        """AI가 "한 번 더 말해 달라"고 해 녹음한 목소리 → voice/{user_id}/{record_date}.m4a"""
        rec = self.get_record(user_id, night_record_id)
        if not data:
            raise ValidationFailed("목소리 파일이 비어 있어요")
        if len(data) > MAX_VOICE_BYTES:
            raise ValidationFailed(f"목소리 파일은 {MAX_VOICE_BYTES // (1024 * 1024)}MB 이하여야 해요")
        check_media_type(content_type, codes.VOICE_MIME_TYPES)
        self.r.storage.upload_voice(user_id, rec.record_date, data)
        self.r.nights.set_voice_path(user_id, night_record_id)
        return self.get_record(user_id, night_record_id)

    def voice_url(self, rec: NightRecord) -> str | None:
        return self.r.storage.signed_url("voice", rec.voice_audio_path) if rec.voice_audio_path else None
