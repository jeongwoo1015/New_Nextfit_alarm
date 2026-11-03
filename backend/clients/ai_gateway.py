"""AI 파트(feat/ai의 `ai` 패키지) 어댑터. 서비스는 이 인터페이스만 쓰고 `ai.*`는 여기서만 import한다.

`ai/`가 없으면 테스트는 FakeAIGateway로 돌고, 있으면 RealAIGateway가 실제 함수를 부른다
(입출력은 feat/ai `ai/CONTRACTS.md` 11/01판 기준, 5장 어댑터 수정 목록 반영).
루틴 항목 "사용 중" = 알람 항목 시간이 0보다 큼.
"""
import importlib.util
from dataclasses import asdict, dataclass, field
from datetime import date, time
from typing import Protocol

import httpx

from backend.core.config import Settings
from backend.core.errors import ServiceUnavailable
from backend.domain.models import AlarmRoutineItem, NightRecord, RecentRecords


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
class Weather:
    """브리핑에서 말할 오늘 날씨 (앱이 보낸 위치로 날씨 API를 불러 정리, DB에 저장하지 않음). 없는 값은 None/""."""
    summary: str = ""                       # 예: "맑음", "실 비"
    temp_c: float | None = None             # 현재 기온
    high_c: float | None = None
    low_c: float | None = None
    precip_prob: int | None = None          # 강수 확률 0~100
    location_name: str = ""                 # 예: "광진구"


@dataclass(frozen=True)
class NightContext:
    persona: Persona
    now_hhmm: str
    events_today: tuple[CalendarEvent, ...] = ()
    events_tomorrow: tuple[CalendarEvent, ...] = ()
    tomorrow_wake_time: str = ""            # 내일 울릴 알람 시각 (없으면 "") — 하루 한정 변경 제안용
    routine_items: tuple[str, ...] = ()     # 내일 알람이 루틴이면 지금 쓰는 항목 이름 (시간 변경·빼기 요청 확인용)
    routine_item_secs: tuple[int, ...] = () # routine_items와 같은 순서의 현재 시간(초)
    allow_skip_coaching: bool = True


@dataclass(frozen=True)
class NightView:
    """RS_NIGHT_RECORD 중 아침 대화에 쓰는 칸 (APPLY_DATE = 오늘인 행)."""
    ai_summary: str = ""
    tomorrow_goal: str = ""
    tomorrow_notes: tuple[str, ...] = ()
    condition_label: str = ""
    condition_text: str = ""
    encouragement: str = ""
    insight: str = ""


@dataclass(frozen=True)
class BriefingContext:
    persona: Persona
    now_hhmm: str                           # 브리핑 시작 시각 (이미 일어난 뒤라 알람 시각 대신 말함)
    events_today: tuple[CalendarEvent, ...] = ()
    night: NightView | None = None
    program_name: str = ""                  # 루틴 알람이면 프리셋 이름
    content_name: str = ""                  # 콘텐츠 알람이면 콘텐츠 이름
    coaching_skipped: bool = False          # 내일 코칭 안 하기에 동의한 날 → 브리핑만
    strategy_block: str = ""
    weather: Weather | None = None          # 없으면 브리핑에서 날씨를 건너뜀


@dataclass(frozen=True)
class CoachStep:
    """코칭 세션에 넘길 단계 (아직 PENDING인 것만)."""
    order: int
    name: str
    planned_sec: int | None
    response_type: str
    script: str = ""


@dataclass(frozen=True)
class CoachingContext:
    persona: Persona
    morning_call_type: str                  # ROUTINE / CONTENT (11/01: 둘 중 하나만, 이어지는 세션 없음)
    steps: tuple[CoachStep, ...]
    resumed: bool = False                   # 재연결이면 전체 안내 생략
    night: NightView | None = None
    strategy_block: str = ""


@dataclass(frozen=True)
class ContentStep:
    name: str
    planned_sec: int | None
    response_type: str
    script: str


@dataclass(frozen=True)
class StrategyDecision:
    strategy: dict              # 코칭 강도·브리핑 길이·응원 정도 (RS_STRATEGY_LOG.STRATEGY)
    briefing_block: str
    coaching_block: str
    log_row: dict               # DecisionLog.to_row() → rs_strategy_log


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


SCOPES = ("TOMORROW", "ALWAYS")        # 내일만(밤 대화 기본) / 앞으로 계속(사용자가 그렇게 말했을 때)


@dataclass(frozen=True)
class ItemChange:
    """밤 대화로 바꾼 루틴 항목 하나. duration_sec: 0 = 빼기, None = 되돌리기."""
    template_item_id: str
    duration_sec: int | None
    scope: str = "TOMORROW"


@dataclass(frozen=True)
class RoutineChangeRequest:
    changes: list[ItemChange] = field(default_factory=list)
    status: str = "DONE"
    note: str = ""


def night_view(rec: NightRecord | None) -> NightView | None:
    if rec is None or rec.extract_status != "DONE":
        return None
    return NightView(rec.ai_summary or "", rec.tomorrow_goal or "", tuple(rec.tomorrow_notes), rec.condition_label or "",
                     rec.condition_text or "", rec.encouragement or "", rec.insight or "")


def fit_seconds(raw: list[int | None], total: int) -> list[int]:
    """비율을 유지하며 합이 total이 되게 맞춘다 (각 단계 최소 10초). AI 콘텐츠 구성은 항상 300초라 서버 설정 시간에 맞춘다."""
    raw = [max(1, r or 1) for r in raw]
    out = [max(10, round(r * total / sum(raw))) for r in raw]
    out[out.index(max(out))] += total - sum(out)
    return out

class AIGateway(Protocol):
    def issue_night_token(self, ctx: NightContext) -> LiveToken: ...
    def extract_night_record(self, transcript: list) -> NightExtract: ...
    def parse_routine_changes(self, transcript: list, items: list[AlarmRoutineItem]) -> RoutineChangeRequest: ...
    def issue_briefing_token(self, ctx: BriefingContext) -> LiveToken: ...
    def issue_coaching_token(self, ctx: CoachingContext) -> LiveToken: ...
    def compose_content_steps(self, content_type: str, content_option: str | None, duration_sec: int,
                              today: date) -> list[ContentStep]: ...      # 실패해도 AI 쪽 기본 단계를 돌려준다
    def decide_strategy(self, user_id: str, records: RecentRecords, today: date, condition: str | None,
                        speech_style: str | None, mode: str, bandit_state: dict | None) -> StrategyDecision: ...
    def strategy_blocks(self, strategy: dict | None) -> tuple[str, str]: ...   # 재연결: 로그의 strategy → (브리핑, 코칭) 문장


def _wake(v) -> time | None:
    if isinstance(v, time):
        return v
    try:
        h, m = str(v or "").split(":")
        return time(int(h), int(m))
    except ValueError:
        return None


def to_item_changes(res) -> list[ItemChange]:
    """AI parse_routine_changes 결과(changes: [{template_item_id, duration_sec, scope}]) → ItemChange 목록.
    AI가 빼기 → 줄이기 → 늘리기 순으로 정렬해 주므로 순서를 그대로 둔다(합계 상한에 덜 걸린다)."""
    out = []
    for c in getattr(res, "changes", None) or []:
        get = c.get if isinstance(c, dict) else lambda k, d=None, c=c: getattr(c, k, d)
        scope = str(get("scope") or "TOMORROW").upper()
        out.append(ItemChange(str(get("template_item_id")), get("duration_sec"), scope if scope in SCOPES else "TOMORROW"))
    return out


def to_night_record_fields(row: dict) -> dict:
    """AI extract_night_record().to_row() → 정의서 11/01 RS_NIGHT_RECORD 컬럼."""
    out = {k: row.get(k) for k in NIGHT_RECORD_FIELDS}
    out["tomorrow_notes"] = list(row.get("tomorrow_notes") or [])
    out["override_wake_time"] = _wake(row.get("override_wake_time"))
    out["override_skip_coaching"] = row.get("override_skip_coaching") is True
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
            events_tomorrow=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_tomorrow),
            routine_items=ctx.routine_items, routine_item_secs=ctx.routine_item_secs,
            allow_skip_coaching=ctx.allow_skip_coaching)
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
        """items = 내일 진행할 항목(알람 설정 + 내일만 바꾼 값). AI가 항목별 시간(0 = 빼기, None = 되돌리기)과 범위를 준다."""
        from ai.tasks.routine_changes import RoutineItem, parse_routine_changes
        res = parse_routine_changes(transcript, [RoutineItem(i.template_item_id, i.item_name, i.duration_sec > 0, i.duration_sec)
                                                 for i in items], self.llm)
        return RoutineChangeRequest(to_item_changes(res), res.status, res.note)

    # ---------- 아침 ----------
    @staticmethod
    def _persona(ai, p: Persona):
        return ai.Persona(p.call_name, p.tone, p.speech_style, p.voice_type)

    @staticmethod
    def _night(ai, n: NightView | None):
        return ai.NightRecordView(**asdict(n)) if n else None

    def issue_briefing_token(self, ctx: BriefingContext) -> LiveToken:
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        inp = ai.BriefingInput(
            persona=self._persona(ai, ctx.persona), morning_call_type="CONTENT" if ctx.content_name else "ROUTINE",
            program_name=ctx.program_name, content_name=ctx.content_name, coaching_skipped=ctx.coaching_skipped,
            now_hhmm=ctx.now_hhmm, events_today=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_today),
            weather=ai.Weather(**asdict(ctx.weather)) if ctx.weather else None, night=self._night(ai, ctx.night),
            strategy_block=ctx.strategy_block)
        return self._issue("briefing", build_setup("briefing", inp))

    def issue_coaching_token(self, ctx: CoachingContext) -> LiveToken:
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        inp = ai.CoachingInput(
            persona=self._persona(ai, ctx.persona), morning_call_type=ctx.morning_call_type,
            steps=tuple(ai.Step(s.order, s.name, s.planned_sec, s.response_type, s.script) for s in ctx.steps),
            resumed=ctx.resumed, night=self._night(ai, ctx.night), strategy_block=ctx.strategy_block)
        return self._issue("coaching", build_setup("coaching", inp))

    def compose_content_steps(self, content_type: str, content_option: str | None, duration_sec: int, today: date) -> list[ContentStep]:
        """AI는 항상 5분(300초)으로 구성하므로, 서버 설정(CONTENT_DURATION_SEC)이 다르면 비율대로 늘이거나 줄인다."""
        from ai.tasks.content_steps import compose_content_steps
        res = compose_content_steps(content_type, content_option, self.llm, today=today)
        secs = fit_seconds([s.planned_sec for s in res.steps], duration_sec)
        return [ContentStep(s.name, sec, s.response_type, s.script) for s, sec in zip(res.steps, secs)]

    def decide_strategy(self, user_id, records, today, condition, speech_style, mode, bandit_state) -> StrategyDecision:
        from ai.personalization.agent import decide
        from ai.personalization.bandit import ContextualBandit
        from ai.personalization.records import build_sessions
        sessions = build_sessions([asdict(s) for s in records.sessions], [asdict(s) for s in records.steps],
                                  [asdict(n) for n in records.nights])
        d = decide(user_id, sessions, today, condition=condition, speech_style=speech_style, mode=mode,
                   bandit=ContextualBandit.from_dict(bandit_state) if bandit_state else None)
        return StrategyDecision(d.strategy.to_dict(), d.briefing_block, d.coaching_block, d.log.to_row())

    def strategy_blocks(self, strategy: dict | None) -> tuple[str, str]:
        from ai.personalization.strategy import Strategy, strategy_block
        s = Strategy.from_dict(strategy)
        return strategy_block(s, "briefing"), strategy_block(s, "coaching")


class UnavailableAIGateway:
    """GEMINI_API_KEY가 없거나 `ai` 패키지가 없을 때 (예: feat/backend-service 단독 실행)."""

    def __init__(self, reason: str):
        self.reason = reason

    def _fail(self, *_, **__):
        raise ServiceUnavailable(f"AI 기능을 쓸 수 없어요: {self.reason}", "AI_UNAVAILABLE")

    issue_night_token = extract_night_record = parse_routine_changes = _fail
    issue_briefing_token = issue_coaching_token = compose_content_steps = decide_strategy = _fail

    def strategy_blocks(self, strategy):
        return "", ""           # 전략 문장이 없으면 대화는 기본 방식으로 진행된다


def build_ai_gateway(settings: Settings):
    if not settings.gemini_api_key:
        return UnavailableAIGateway("GEMINI_API_KEY가 없어요")
    if importlib.util.find_spec("ai") is None:
        return UnavailableAIGateway("ai 패키지가 없어요 (main에서 feat/ai와 통합 필요)")
    return RealAIGateway(settings.gemini_api_key)
