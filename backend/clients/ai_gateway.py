"""AI 파트(feat/ai의 `ai` 패키지) 어댑터. 서비스는 이 인터페이스만 쓰고 `ai.*`는 여기서만 import한다.

`ai/`가 없으면 테스트는 FakeAIGateway로 돌고, 있으면 RealAIGateway가 실제 함수를 부른다
(입출력은 feat/ai `ai/CONTRACTS.md` 11/01판 기준, 5장 어댑터 수정 목록 반영).
루틴 항목 "사용 중" = 알람 항목 시간이 0보다 큼.
"""
import importlib.util
import logging
import random
import time as _time
from dataclasses import asdict, dataclass, field
from datetime import date, time
from typing import Protocol

import httpx

from backend.core.config import Settings
from backend.core.errors import ServiceUnavailable
from backend.domain.models import (AlarmRoutineItem, MorningSession, NightRecord, RecentRecords, SessionStep, StrategyLog,
                                   UserSetting)

log = logging.getLogger(__name__)

# 끝말잇기 판정 시간 예산 (앱 요청 11/25: 5초 안에 답이 없으면 5xx. 네트워크 시간을 빼고 서버 안에서 최악 약 4.1초).
# GeminiText 기본값(HTTP 12초, 한 호출 30초)에 judge_wordchain 재시도(최대 3번)가 겹치면 수십 초가 걸려,
# 앱이 포기한 뒤에도 서버 스레드와 Gemini 사용량을 계속 쓴다. 보통 판정은 0.7~1.5초(AI B26).
WORDCHAIN_BUDGET_SEC = 4.0
WORDCHAIN_DEADLINE_FLOOR_SEC = 0.05  # 남은 시간이 짧을 때 deadline 최솟값(0이면 첫 시도도 못 한다). 넘칠 수 있는 최대 시간
WORDCHAIN_HTTP_TIMEOUT_SEC = 2.5
WORDCHAIN_MIN_CALL_SEC = 0.8        # 남은 시간이 이보다 적으면 새 호출을 시작하지 않는다

AUTH_TOKEN_URL = "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"

NIGHT_RECORD_FIELDS = ("ai_summary", "tomorrow_goal", "tomorrow_notes", "condition_label", "condition_text",
                       "encouragement", "insight", "diary_text")


@dataclass(frozen=True)
class Persona:
    """RS_USER_SETTING: USER_CALL_NAME, TONE, SPEECH_STYLE, VOICE_TYPE + 11/16 PERSONALITY, INTENSITY, AUTO_INTENSITY.
    intensity는 아침이면 그날 쓴 강도(세션 APPLIED_INTENSITY), 밤이면 설정 강도."""
    call_name: str
    tone: str = "FORMAL"
    speech_style: str = "KIND"
    voice_type: str = "FEMALE"
    personality: str = ""
    intensity: int = 2
    auto_intensity: bool = True


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
class SettingsView:
    """대화로 바꿀 수 있는 설정의 현재 값 (AI SettingsView, B22). AI가 바꾸기 전에 현재 상태를 알리고 "한 단계 낮춰 줘"를 계산한다."""
    music_genre: str = ""
    wake_start_level: int | None = None
    wake_state: str = ""                    # RECOMMENDED / EXPLORING / USER
    wake_max_level: int | None = None
    wake_raise_from: int | None = None      # 밤에만: 내일 시작 단계가 올라가는 날의 이전 단계


def settings_view(st: UserSetting, wake_row: dict | None = None, raise_from: int | None = None) -> SettingsView:
    """wake_row = 그 아침의 기상 볼륨 기록(RS_WAKE_LOG). 없으면 사용자가 정한 값만(다음 알람 한 번이면 그 단계, USER).
    raise_from = 밤에만, 내일 시작 단계가 올라가는 날의 이전 단계 (AI가 알리고 반대 여부를 묻는다)."""
    if wake_row and (wake_row.get("start_level") or wake_row.get("applied_level")):
        return SettingsView(st.music_genre, wake_row.get("start_level") or wake_row["applied_level"], wake_row.get("state") or "",
                            st.wake_max_start_level, raise_from)
    return SettingsView(st.music_genre, st.wake_next_level, "USER" if st.wake_next_level else "", st.wake_max_start_level)


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
    settings: SettingsView | None = None    # 대화로 바꿀 수 있는 설정 (update_setting, B22)


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
    one_liner_follows: bool = False         # 브리핑 뒤 앱이 어젯밤 한마디 녹음을 틀어 줌 → 마무리에서 순서 안내 (AI 11/10)
    plan: tuple[str, ...] = ()              # 모닝콜 순서표 (AI B15). 비어 있으면 AI가 예전 순서로 말한다
    already_done: bool = False              # 오늘 단계를 이미 모두 마친 날 (앱 재진입) → 짧게 칭찬만
    settings: SettingsView | None = None    # 대화로 바꿀 수 있는 설정 (update_setting, B22)
    snoozes_today: int = 0                  # 오늘 미룬 횟수 (버튼·말 합산). 하루 한도면 AI가 reschedule_alarm을 받지 않는다 (B21)


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
    content_type: str = ""                  # CONTENT 세션: 콘텐츠 종류·오늘 쓴 세부 (CONTRACTS B14, 비어 있으면 AI가 단계로 추정)
    content_option: str = ""
    plan: tuple[str, ...] = ()
    stretch_routines: tuple = ()            # ((부위, (CoachStep, ...)), ...) 있으면 AI가 부위를 묻고 select_stretch_area (AI B17)
    snoozes_today: int = 0                  # 오늘 미룬 횟수 (버튼·말 합산). 루틴·콘텐츠 통화에서도 말로 미룰 수 있어 한도 판단에 쓴다 (AI B25)


@dataclass(frozen=True)
class ContentStep:
    name: str
    planned_sec: int | None
    response_type: str
    script: str


@dataclass(frozen=True)
class ContentPlan:
    """콘텐츠 단계 구성 결과. option = 오늘 실제로 쓴 세부 (세부를 고르지 않은 퀴즈 알람이면 AI가 날짜별로 정한 값)."""
    steps: list[ContentStep]
    option: str = ""


@dataclass(frozen=True)
class StrategyDecision:
    strategy: dict              # 코칭 강도·브리핑 길이·응원 정도 (RS_STRATEGY_LOG.STRATEGY)
    briefing_block: str
    coaching_block: str
    log_row: dict               # DecisionLog.to_row() → rs_strategy_log


@dataclass(frozen=True)
class StrategyLearning:
    reward: float | None        # 단계 수행 비율 0~1 (AI coaching_reward). 코칭을 안 한 날·단계가 없는 날은 None(기록·학습 안 함)
    state: dict | None          # 갱신한 코칭 Bandit 상태. Bandit이 고른 날이 아니면 None(저장할 것 없음)


@dataclass(frozen=True)
class WakeInputs:
    """기상 알람 시작 단계를 정하는 입력 (AI decide_start_level). 같은 날 다시 정할 때 같은 값을 넣으려고 기록(prefs)에 함께 남긴다."""
    sleep_minutes: float | None = None
    important_day: bool = False
    max_start_level: int | None = None      # 사용자 상한 (RS_USER_SETTING.WAKE_MAX_START_LEVEL)
    next_level: int | None = None           # 다음 알람 한 번 (WAKE_NEXT_LEVEL)
    previous_level: int | None = None       # 직전 아침의 단계
    notified: bool = True                   # 올리는 날이면 밤 대화로 알렸는가 (False면 올리지 않고 직전 단계 유지)


@dataclass(frozen=True)
class WakeDecision:
    row: dict                   # WakeLog.to_row() → rs_wake_log 결정 칸 (recommended·applied_level, source, state, ...)
    raise_pending: bool         # 올리는 날이라 밤 대화에서 알려야 한다


def default_wake_decision(user_id: str, session_date: date, w: WakeInputs) -> WakeDecision:
    """AI를 쓸 수 없을 때: 사용자 값 → 직전 단계(상한 안) → 1단계. 올리지 않으므로 알릴 것도 없다."""
    level = w.next_level or min(w.previous_level or 1, w.max_start_level or 5)
    return WakeDecision({"user_id": user_id, "session_date": session_date.isoformat(), "context_key": "all",
                         "recommended_level": level, "applied_level": level,
                         "source": "user_today" if w.next_level else "default", "explored": False, "propensity": None,
                         "state": "USER" if w.next_level else "RECOMMENDED", "reason": "기본 단계",
                         "previous_level": w.previous_level,
                         "prefs": {"max_start_level": w.max_start_level, "next_alarm_level": w.next_level}}, False)


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
class Fortune:
    """오늘의 포춘 문장 (RS_MORNING_SESSION.FORTUNE_*). AI 쪽 compose_fortune은 실패해도 기본 문장을 준다."""
    text: str
    style: str                  # COOKIE / HOROSCOPE
    source: str                 # ai / fallback / safe


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


def persona_of(st: UserSetting, intensity: int | None = None) -> Persona:
    """설정 → AI Persona. intensity를 주면(아침: 그날 쓴 강도) 설정 강도 대신 쓴다."""
    return Persona(st.user_call_name, st.tone, st.speech_style, st.voice_type, st.personality,
                   intensity or st.intensity, st.auto_intensity)


def night_view(rec: NightRecord | None) -> NightView | None:
    if rec is None or rec.extract_status != "DONE":
        return None
    return NightView(rec.ai_summary or "", rec.tomorrow_goal or "", tuple(rec.tomorrow_notes), rec.condition_label or "",
                     rec.condition_text or "", rec.encouragement or "", rec.insight or "")


def _fit(steps, duration_sec: int) -> list[ContentStep]:
    """AI 단계(ai.live.inputs.Step) → ContentStep, 시간은 서버 설정 합계에 맞춘다."""
    secs = fit_seconds([s.planned_sec for s in steps], duration_sec)
    return [ContentStep(s.name, sec, s.response_type, s.script) for s, sec in zip(steps, secs)]


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
                              today: date, english_level: str = "", context: str = "") -> ContentPlan: ...  # 실패해도 AI 쪽 기본 단계를 돌려준다
    def stretch_routines(self, duration_sec: int) -> dict[str, list[ContentStep]]: ...   # 부위 12개 루틴 {부위: 단계} (스트레칭은 180초)
    def morning_plan(self, has_one_liner: bool, coaching: bool) -> tuple[str, ...]: ...   # 아침 순서표 (AI B15)
    def today_intensity(self, base: int, sessions: list, auto: bool) -> int: ...          # sessions = 지난 MorningSession(오래된 것부터)
    def decide_strategy(self, user_id: str, records: RecentRecords, today: date, condition: str | None,
                        speech_style: str | None, mode: str, bandit_state: dict | None) -> StrategyDecision: ...
    def strategy_blocks(self, strategy: dict | None) -> tuple[str, str]: ...   # 재연결: 로그의 strategy → (브리핑, 코칭) 문장
    def compose_fortune(self, one_liner: str, tone: str, today: date, diary_text: str) -> Fortune: ...
    def issue_fortune_token(self, persona: Persona, fortune: Fortune, based_on: str = "",
                            plan: tuple[str, ...] = ()) -> LiveToken: ...   # 문장을 그대로 읽는 Live
    # 기상 알람 시작 볼륨 (B23). seed가 같으면 같은 추천(같은 날 다시 정해도 밤에 알린 값이 바뀌지 않게)
    def decide_wake_level(self, user_id: str, session_date: date, personal: dict | None, global_: dict | None,
                          w: WakeInputs, seed: str) -> WakeDecision: ...
    def wake_raise_announced(self, ai_text: str, level: int) -> bool: ...    # 밤 대화에서 AI가 올린다고 실제로 말했는가
    def learn_strategy(self, bandit_state: dict | None, log: StrategyLog, session: MorningSession,
                       steps: list[SessionStep]) -> StrategyLearning: ...    # 코칭 강도: 그날 결과로 보상을 매기고 학습 (11/21)
    def learn_wake(self, state: dict | None, context_key: str, start_level: int, received_level: int | None,
                   confirmed: bool | None) -> dict | None: ...              # 반영한 새 학습 상태, 값이 잘못됐으면 None (confirmed는 AI가 무시, B28)
    def judge_wordchain(self, words: list[str], word: str) -> dict: ...
    # 끝말잇기 글자 판정 (AI B26): {"valid": true, "next": "정원"|None} / {"valid": false, "reason": "…"} / {"status": "FAILED"}
    # 이미 확인 성공으로 학습한 날을 실패로 고침(11/24 미루기): 받은 단계 칸의 성공 1 → 실패 1. 옮길 성공이 없으면 None


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
    def __init__(self, api_key: str, http: httpx.Client | None = None, llm=None, clock=_time.monotonic):
        self._key = api_key
        self._http = http or httpx.Client(timeout=15)
        self._llm = llm
        self._clock = clock

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
            persona=self._persona(ai, ctx.persona),
            events_today=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_today),
            now_hhmm=ctx.now_hhmm, tomorrow_wake_time=ctx.tomorrow_wake_time,
            events_tomorrow=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_tomorrow),
            routine_items=ctx.routine_items, routine_item_secs=ctx.routine_item_secs,
            allow_skip_coaching=ctx.allow_skip_coaching, settings=self._settings(ai, ctx.settings))
        return self._issue("night", build_setup("night", inp))

    @staticmethod
    def _settings(ai, v: SettingsView | None):
        return ai.SettingsView(**asdict(v)) if v else None

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
        return ai.Persona(p.call_name, p.tone, p.speech_style, p.voice_type, personality=p.personality,
                          intensity=p.intensity, auto_intensity=p.auto_intensity)

    @staticmethod
    def _night(ai, n: NightView | None):
        return ai.NightRecordView(**asdict(n)) if n else None

    def issue_briefing_token(self, ctx: BriefingContext) -> LiveToken:
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        inp = ai.BriefingInput(
            persona=self._persona(ai, ctx.persona), morning_call_type="CONTENT" if ctx.content_name else "ROUTINE",
            program_name=ctx.program_name, content_name=ctx.content_name, coaching_skipped=ctx.coaching_skipped,
            one_liner_follows=ctx.one_liner_follows,
            now_hhmm=ctx.now_hhmm, events_today=tuple(ai.CalendarEvent(e.title, e.start, e.end, e.all_day) for e in ctx.events_today),
            weather=ai.Weather(**asdict(ctx.weather)) if ctx.weather else None, night=self._night(ai, ctx.night),
            strategy_block=ctx.strategy_block, plan=ctx.plan, already_done=ctx.already_done,
            settings=self._settings(ai, ctx.settings), snoozes_today=ctx.snoozes_today)
        return self._issue("briefing", build_setup("briefing", inp))

    def issue_coaching_token(self, ctx: CoachingContext) -> LiveToken:
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        def step(s: CoachStep):
            return ai.Step(s.order, s.name, s.planned_sec, s.response_type, s.script)
        inp = ai.CoachingInput(
            persona=self._persona(ai, ctx.persona), morning_call_type=ctx.morning_call_type,
            steps=tuple(step(s) for s in ctx.steps),
            resumed=ctx.resumed, night=self._night(ai, ctx.night), strategy_block=ctx.strategy_block,
            content_type=ctx.content_type, content_option=ctx.content_option, plan=ctx.plan,
            stretch_routines=tuple((area, tuple(step(s) for s in steps)) for area, steps in ctx.stretch_routines),
            snoozes_today=ctx.snoozes_today)
        return self._issue("coaching", build_setup("coaching", inp))

    def compose_content_steps(self, content_type: str, content_option: str | None, duration_sec: int, today: date,
                              english_level: str = "", context: str = "") -> ContentPlan:
        """AI는 항상 5분(300초)으로 구성하므로, 서버 설정(CONTENT_DURATION_SEC)이 다르면 비율대로 늘이거나 줄인다.
        스트레칭·인물/상식 퀴즈·끝말잇기는 AI 없이 정해 둔 단계, 영어는 AI 구성(B14, 11/13 수준·오늘 이야기)."""
        from ai.tasks.content_steps import compose_content_steps
        res = compose_content_steps(content_type, content_option, self.llm, today=today,
                                    english_level=english_level or None, context=context)
        return ContentPlan(_fit(res.steps, duration_sec), getattr(res, "option", "") or content_option or "")

    def stretch_routines(self, duration_sec: int) -> dict[str, list[ContentStep]]:
        from ai.tasks.stretch_sets import all_area_steps
        return {area: _fit(steps, duration_sec) for area, steps in all_area_steps().items()}

    def morning_plan(self, has_one_liner: bool, coaching: bool) -> tuple[str, ...]:
        from ai.tasks.morning_plan import morning_plan
        return tuple(morning_plan(has_one_liner, coaching))

    def today_intensity(self, base: int, sessions: list, auto: bool) -> int:
        """AI 쪽 history_from_sessions는 wake_success·rewake_count·applied_intensity 속성만 읽어 MorningSession을 그대로 넘긴다."""
        from ai.personalization.intensity import history_from_sessions, today_intensity
        return today_intensity(base, history_from_sessions(sessions), auto)

    def decide_strategy(self, user_id, records, today, condition, speech_style, mode, bandit_state) -> StrategyDecision:
        from ai.personalization.agent import decide
        from ai.personalization.bandit import ContextualBandit
        from ai.personalization.records import build_sessions
        sessions = build_sessions([asdict(s) for s in records.sessions], [asdict(s) for s in records.steps],
                                  [asdict(n) for n in records.nights])
        # 저장된 상태가 없어도 빈 Bandit을 넘긴다 — None이면 AI가 규칙으로 돌아가 학습이 한 번도 시작되지 않는다
        d = decide(user_id, sessions, today, condition=condition, speech_style=speech_style, mode=mode,
                   bandit=ContextualBandit.from_dict(bandit_state) if bandit_state else ContextualBandit())
        return StrategyDecision(d.strategy.to_dict(), d.briefing_block, d.coaching_block, d.log.to_row())

    def compose_fortune(self, one_liner: str, tone: str, today: date, diary_text: str) -> Fortune:
        """스타일은 정하지 않는다(None = 날짜에 따라 포춘쿠키·오늘의 운세를 번갈아, CONTRACTS B13 기본)."""
        from ai.tasks.fortune import compose_fortune
        res = compose_fortune(one_liner or "", self.llm, None, tone, today, diary_text or "")
        return Fortune(res.text, res.style, res.source)

    def issue_fortune_token(self, persona: Persona, fortune: Fortune, based_on: str = "", plan: tuple[str, ...] = ()) -> LiveToken:
        """based_on: ONE_LINER / DIARY / "" — 소개 문장이 "어젯밤 한마디로 만든 …"처럼 재료를 밝힌다(AI 11/10)."""
        from ai.live import inputs as ai
        from ai.live.config import build_setup
        inp = ai.FortuneInput(persona=self._persona(ai, persona), fortune_text=fortune.text, style=fortune.style,
                              based_on=based_on, plan=plan)
        return self._issue("fortune", build_setup("fortune", inp))

    def strategy_blocks(self, strategy: dict | None) -> tuple[str, str]:
        from ai.personalization.strategy import Strategy, strategy_block
        s = Strategy.from_dict(strategy)
        return strategy_block(s, "briefing"), strategy_block(s, "coaching")

    def decide_wake_level(self, user_id, session_date, personal, global_, w: WakeInputs, seed: str) -> WakeDecision:
        from ai.personalization.wake_volume import UserWakePrefs, WakeLadder, decide_start_level, make_log
        prefs = UserWakePrefs(w.max_start_level, w.next_level)
        d = decide_start_level(WakeLadder.from_dict(personal) if personal else None,
                               WakeLadder.from_dict(global_) if global_ else None, w.sleep_minutes, prefs, w.previous_level,
                               w.notified, w.important_day, rng=random.Random(seed))
        return WakeDecision(make_log(d, user_id, session_date.isoformat(), prefs, w.previous_level).to_row(), d.raise_pending)

    def wake_raise_announced(self, ai_text: str, level: int) -> bool:
        from ai.personalization.wake_volume import raise_announced
        return raise_announced(ai_text, level)

    def learn_strategy(self, bandit_state, log: StrategyLog, session: MorningSession, steps: list[SessionStep]) -> StrategyLearning:
        from ai.personalization.agent import learn
        from ai.personalization.bandit import ContextualBandit
        from ai.personalization.logs import DecisionLog
        from ai.personalization.records import build_sessions
        from ai.personalization.reward import coaching_reward
        record = build_sessions([asdict(session)], [asdict(s) for s in steps])[0]
        reward = coaching_reward(record)
        if reward is None:
            return StrategyLearning(None, None)
        bandit = ContextualBandit.from_dict(bandit_state) if bandit_state else ContextualBandit()
        row = {**asdict(log), "session_date": log.session_date.isoformat()}
        return StrategyLearning(reward, bandit.to_dict() if learn(bandit, DecisionLog.from_row(row), reward) else None)

    def learn_wake(self, state, context_key, start_level, received_level, confirmed) -> dict | None:
        from ai.personalization.wake_volume import WakeLadder, learn_wake
        ladder = WakeLadder.from_dict(state)
        return ladder.to_dict() if learn_wake(ladder, context_key, start_level, received_level, confirmed) else None

    def judge_wordchain(self, words: list[str], word: str) -> dict:
        """AI는 규칙 위반이면 AI를 부르지 않고 거르고, 호출 실패(LLMError·응답 형식)는 FAILED로 준다.
        그 밖의 예외(네트워크 등)도 판정하지 못한 것이므로 FAILED로 맞춘다."""
        from ai.tasks.wordchain import judge_wordchain
        try:
            return judge_wordchain(list(words), word, _BudgetLLM(self._wordchain_llm, WORDCHAIN_BUDGET_SEC, self._clock)).to_dict()
        except Exception:
            log.exception("끝말잇기 판정 실패")
            return {"status": "FAILED"}


    def _wordchain_llm(self, remaining: float):
        """남은 시간에 맞춘 GeminiText: HTTP 한 번은 timeout, 다음 모델로 넘어가는 것은 deadline 안에서만(그 뒤 한 번 더 timeout).
        테스트처럼 llm을 넣어 만들었으면 그대로 쓴다."""
        if self._llm is not None:
            return self._llm
        from ai.tasks.llm import GeminiText
        timeout = min(WORDCHAIN_HTTP_TIMEOUT_SEC, remaining)
        return GeminiText(api_key=self._key, timeout=timeout, deadline=max(WORDCHAIN_DEADLINE_FLOOR_SEC, remaining - timeout))


class _BudgetLLM:
    """judge_wordchain에 넘기는 llm. 호출마다 남은 시간을 보고, 모자라면 LLMError를 던져 AI가 FAILED로 끝내게 한다
    (AI는 LLMError를 호출 실패로 세고 두 번이면 멈춘다)."""

    def __init__(self, make, budget: float, clock):
        self.make, self.budget, self.clock = make, budget, clock
        self.start = clock()

    def generate_json(self, system: str, user: str) -> dict:
        from ai.tasks.llm import LLMError
        remaining = self.budget - (self.clock() - self.start)
        if remaining < WORDCHAIN_MIN_CALL_SEC:
            raise LLMError(f"끝말잇기 판정 시간 예산 초과 ({self.budget:g}초)")
        return self.make(remaining).generate_json(system, user)


class UnavailableAIGateway:
    """GEMINI_API_KEY가 없거나 `ai` 패키지가 없을 때 (예: feat/backend-service 단독 실행)."""

    def __init__(self, reason: str):
        self.reason = reason

    def _fail(self, *_, **__):
        raise ServiceUnavailable(f"AI 기능을 쓸 수 없어요: {self.reason}", "AI_UNAVAILABLE")

    issue_night_token = extract_night_record = parse_routine_changes = _fail
    issue_briefing_token = issue_coaching_token = compose_content_steps = decide_strategy = _fail
    compose_fortune = issue_fortune_token = _fail

    def strategy_blocks(self, strategy):
        return "", ""           # 전략 문장이 없으면 대화는 기본 방식으로 진행된다

    # 알람 응답(세션 시작)을 막지 않도록, 없으면 예전 동작이 되는 값을 준다
    def morning_plan(self, has_one_liner, coaching):
        return ()               # 순서표 없음 = 앱·AI 모두 예전 순서

    def stretch_routines(self, duration_sec):
        return {}               # 부위 고르기 없이 전신 루틴

    def today_intensity(self, base, sessions, auto):
        return base

    def decide_wake_level(self, user_id, session_date, personal, global_, w, seed):
        return default_wake_decision(user_id, session_date, w)      # 알람은 울려야 하므로 기본 단계

    def wake_raise_announced(self, ai_text, level):
        return False

    def learn_wake(self, state, context_key, start_level, received_level, confirmed):
        return None             # 학습 없이 기록만 남는다

    judge_wordchain = _fail     # 앱은 오류면 자기 규칙·단어 목록으로 이어 간다

    def learn_strategy(self, bandit_state, log, session, steps):
        return StrategyLearning(None, None)


def build_ai_gateway(settings: Settings):
    if not settings.gemini_api_key:
        return UnavailableAIGateway("GEMINI_API_KEY가 없어요")
    if importlib.util.find_spec("ai") is None:
        return UnavailableAIGateway("ai 패키지가 없어요 (main에서 feat/ai와 통합 필요)")
    return RealAIGateway(settings.gemini_api_key)
