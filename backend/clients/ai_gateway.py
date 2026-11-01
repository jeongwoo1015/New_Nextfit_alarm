"""AI 파트(feat/ai의 `ai` 패키지) 어댑터. 서비스는 이 인터페이스만 쓰고 `ai.*`는 여기서만 import한다.

feat/backend-service 브랜치에는 `ai/`가 없으므로 테스트는 FakeAIGateway로 돌고, main에서 통합된 뒤
RealAIGateway가 실제 함수를 부른다(입출력은 feat/ai `ai/CONTRACTS.md` 기준).

AI 코드가 정의서 11/01 기준으로 바뀌기 전까지 여기서 맞춘다:
- 추출 결과 override_coaching_min 0 → override_skip_coaching = TRUE, 10(코칭 줄이기)은 11/01에 없어 버린다.
- 루틴 항목 "사용 중" = 알람 항목 시간이 0보다 큼.
"""
import importlib.util
from dataclasses import dataclass, field
from datetime import time
from typing import Protocol

import httpx

from backend.core.config import Settings
from backend.core.errors import ServiceUnavailable
from backend.domain.models import AlarmRoutineItem

AUTH_TOKEN_URL = "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"

NIGHT_RECORD_FIELDS = ("ai_summary", "tomorrow_goal", "tomorrow_notes", "condition_label", "condition_text",
                       "encouragement", "insight", "diary_text")


@dataclass(frozen=True)
class Persona:
    """RS_USER_SETTING: USER_CALL_NAME, TONE, SPEECH_STYLE, VOICE_TYPE"""
    call_name: str
    tone: str = "FORMAL"
    speech_style: str = "KIND"
    voice_type: str = "FEMALE"


@dataclass(frozen=True)
class CalendarEvent:
    """폰 캘린더 일정 (앱이 토큰 요청에 같이 보냄, DB에 저장하지 않음). start/end는 "HH:MM"(KST)."""
    title: str
    start: str = ""
    end: str = ""
    all_day: bool = False


@dataclass(frozen=True)
class NightContext:
    persona: Persona
    now_hhmm: str
    events_today: tuple[CalendarEvent, ...] = ()
    events_tomorrow: tuple[CalendarEvent, ...] = ()
    tomorrow_wake_time: str = ""            # 내일 울릴 알람 시각 (없으면 "") — 하루 한정 변경 제안용
    routine_items: tuple[str, ...] = ()     # 내일 알람이 루틴이면 지금 쓰는 항목 이름 (빼기 요청 확인용)
    allow_skip_coaching: bool = True


@dataclass(frozen=True)
class LiveToken:
    """앱이 Gemini Live에 직접 연결할 1회용 임시 토큰. 지시문은 토큰에 고정돼 있다."""
    token: str                  # ws_url?access_token=<token>
    ws_url: str
    model: str
    start_signal: str           # 연결 직후 realtimeInput.text로 보내는 시작 신호 (받아쓰기에 넣지 않음)
    expires_at: str


@dataclass(frozen=True)
class NightExtract:
    status: str                 # DONE / FAILED (RS_NIGHT_RECORD.EXTRACT_STATUS)
    fields: dict = field(default_factory=dict)   # 11/01 컬럼 이름 + override_wake_time(time) / override_skip_coaching(bool)
    error: str = ""


@dataclass(frozen=True)
class RoutineChangeRequest:
    remove: list[str] = field(default_factory=list)    # 뺄 template_item_id
    restore: list[str] = field(default_factory=list)   # 다시 넣을 template_item_id
    status: str = "DONE"
    note: str = ""


class AIGateway(Protocol):
    def issue_night_token(self, ctx: NightContext) -> LiveToken: ...
    def extract_night_record(self, transcript: list) -> NightExtract: ...
    def parse_routine_changes(self, transcript: list, items: list[AlarmRoutineItem]) -> RoutineChangeRequest: ...


def _wake(v) -> time | None:
    if isinstance(v, time):
        return v
    try:
        h, m = str(v or "").split(":")
        return time(int(h), int(m))
    except ValueError:
        return None


def to_night_record_fields(row: dict) -> dict:
    """AI extract_night_record().to_row() → 정의서 11/01 RS_NIGHT_RECORD 컬럼."""
    out = {k: row.get(k) for k in NIGHT_RECORD_FIELDS}
    out["tomorrow_notes"] = list(row.get("tomorrow_notes") or [])
    out["override_wake_time"] = _wake(row.get("override_wake_time"))
    out["override_skip_coaching"] = row.get("override_skip_coaching") is True or row.get("override_coaching_min") == 0
    return out


class RealAIGateway:
    def __init__(self, api_key: str, http: httpx.Client | None = None, llm=None):
        self._key = api_key
        self._http = http or httpx.Client(timeout=15)
        self._llm = llm

    @property
    def llm(self):
        if self._llm is None:
            from ai.tasks.llm import GeminiText
            self._llm = GeminiText(api_key=self._key)
        return self._llm

    def issue_night_token(self, ctx: NightContext) -> LiveToken:
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        inp = ai.NightInput(
            persona=ai.Persona(ctx.persona.call_name, ctx.persona.tone, ctx.persona.speech_style, ctx.persona.voice_type),
            events_today=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_today),
            now_hhmm=ctx.now_hhmm, tomorrow_wake_time=ctx.tomorrow_wake_time,
            tomorrow_coaching_min=None,     # 11/01: 코칭 길이 선택 없음 ("내일 코칭 안 하기"만)
            events_tomorrow=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_tomorrow),
            routine_items=ctx.routine_items, allow_skip_coaching=ctx.allow_skip_coaching)
        return self._issue("night", build_setup("night", inp))

    def _issue(self, kind: str, setup: dict) -> LiveToken:
        from ai.live.config import start_signal, token_request_body
        body = token_request_body(setup)
        try:
            r = self._http.post(AUTH_TOKEN_URL, json=body, headers={"x-goog-api-key": self._key})
            r.raise_for_status()
            name = r.json()["name"]
        except (httpx.HTTPError, KeyError, ValueError) as e:
            raise ServiceUnavailable(f"Live 토큰을 발급하지 못했어요: {e}", "AI_UNAVAILABLE") from None
        return LiveToken(name, LIVE_WS_URL, setup["model"], start_signal(kind), body["expireTime"])

    def extract_night_record(self, transcript: list) -> NightExtract:
        from ai.tasks.night_record import extract_night_record
        res = extract_night_record(transcript, self.llm)
        return NightExtract(res.status, to_night_record_fields(res.to_row()), res.error)

    def parse_routine_changes(self, transcript: list, items: list[AlarmRoutineItem]) -> RoutineChangeRequest:
        from ai.tasks.routine_changes import RoutineItem, parse_routine_changes
        res = parse_routine_changes(transcript, [RoutineItem(i.template_item_id, i.item_name, i.duration_sec > 0) for i in items], self.llm)
        return RoutineChangeRequest(list(res.remove), list(res.restore), res.status, res.note)


class UnavailableAIGateway:
    """GEMINI_API_KEY가 없거나 `ai` 패키지가 없을 때 (예: feat/backend-service 단독 실행)."""

    def __init__(self, reason: str):
        self.reason = reason

    def _fail(self, *_, **__):
        raise ServiceUnavailable(f"AI 기능을 쓸 수 없어요: {self.reason}", "AI_UNAVAILABLE")

    issue_night_token = extract_night_record = parse_routine_changes = _fail


def build_ai_gateway(settings: Settings):
    if not settings.gemini_api_key:
        return UnavailableAIGateway("GEMINI_API_KEY가 없어요")
    if importlib.util.find_spec("ai") is None:
        return UnavailableAIGateway("ai 패키지가 없어요 (main에서 feat/ai와 통합 필요)")
    return RealAIGateway(settings.gemini_api_key)
