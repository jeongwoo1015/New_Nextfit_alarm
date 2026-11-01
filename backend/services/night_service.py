"""밤 흐름: Live 토큰 발급 → (앱이 Gemini Live로 대화) → 원문 저장 → 밤 기록 추출 → 목소리 업로드.

추출은 AI 호출이라 수십 초 걸릴 수 있어 원문 저장 응답 뒤 백그라운드에서 한다(앱은 EXTRACT_STATUS로 확인).
"""
import logging
from dataclasses import dataclass, field
from datetime import date, datetime

from backend.clients.ai_gateway import AIGateway, CalendarEvent, LiveToken, NightContext, Persona
from backend.core.errors import AppError, NotFound, ValidationFailed
from backend.core.timeutil import now_kst, to_kst
from backend.domain.alarm_rules import pick_override_alarm
from backend.domain.models import Alarm, NightRecord
from backend.domain.routine_rules import apply_conversation
from backend.repositories.registry import Repositories

log = logging.getLogger(__name__)

MAX_VOICE_BYTES = 10 * 1024 * 1024


@dataclass(frozen=True)
class ExtractOutcome:
    status: str                                     # DONE / FAILED
    override_alarm_id: str | None = None
    dropped_override: bool = False                  # 변경에 동의했지만 내일 울릴 알람이 없어 저장하지 않음
    routine_removed: list[str] = field(default_factory=list)
    routine_restored: list[str] = field(default_factory=list)
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
        """하루 한정 변경과 대화로 바꾼 루틴 항목을 적용할 알람 = 밤 기록이 적용되는 아침에 가장 먼저 울리는 알람."""
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
        self.r.nights.save_night_extract(user_id, night_record_id, fields, alarm.alarm_id if alarm and wants_override else None)

        outcome = ExtractOutcome("DONE", alarm.alarm_id if alarm and wants_override else None, dropped)
        if alarm and alarm.morning_call_type == "ROUTINE":
            outcome = self._apply_routine_changes(user_id, alarm, raw.transcript, outcome)
        return outcome

    def _apply_routine_changes(self, user_id: str, alarm: Alarm, transcript: list, outcome: ExtractOutcome) -> ExtractOutcome:
        """루틴 변경은 부가 기능이라 실패해도 밤 기록 저장은 그대로 둔다."""
        try:
            items = self.r.alarms.get_alarm_routine(user_id, alarm.alarm_id) or []
            req = self.ai.parse_routine_changes(transcript, items)
            if req.status != "DONE" or not (req.remove or req.restore):
                return outcome
            change = apply_conversation(items, req.remove, req.restore, self.r.presets.get_app_config().routine_max_sec)
            if change.removed or change.restored:
                self.r.alarms.replace_alarm_routine(user_id, alarm.alarm_id, change.overrides)
            return ExtractOutcome(outcome.status, outcome.override_alarm_id, outcome.dropped_override,
                                  change.removed, change.restored, change.rejected)
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

    def save_voice(self, user_id: str, night_record_id: str, data: bytes) -> NightRecord:
        """AI가 "한 번 더 말해 달라"고 해 녹음한 목소리 → voice/{user_id}/{record_date}.m4a"""
        rec = self.get_record(user_id, night_record_id)
        if not data:
            raise ValidationFailed("목소리 파일이 비어 있어요")
        if len(data) > MAX_VOICE_BYTES:
            raise ValidationFailed(f"목소리 파일은 {MAX_VOICE_BYTES // (1024 * 1024)}MB 이하여야 해요")
        self.r.storage.upload_voice(user_id, rec.record_date, data)
        self.r.nights.set_voice_path(user_id, night_record_id)
        return self.get_record(user_id, night_record_id)

    def voice_url(self, rec: NightRecord) -> str | None:
        return self.r.storage.signed_url("voice", rec.voice_audio_path) if rec.voice_audio_path else None
