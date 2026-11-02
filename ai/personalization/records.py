"""행동 기록 형식. DB 정의서(11/01수정)의 컬럼 이름을 그대로 따른다 (실제 테이블은 소문자).

서버가 할 일은 RS_MORNING_SESSION, RS_SESSION_STEP, RS_NIGHT_RECORD에서 읽은 행(dict)을 `build_sessions()`에 넣어 주는 것뿐이다.
컬럼이 없거나 NULL이어도 오류 없이 빈 값으로 처리한다. 시각은 한국 시간(KST) 기준으로 읽는다(시간대가 없는 값은 KST로 본다).
"""
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone

KST = timezone(timedelta(hours=9))

DONE, SKIPPED, PENDING = "DONE", "SKIPPED", "PENDING"   # RS_SESSION_STEP.STEP_STATUS
STEP_STATUSES = frozenset({DONE, SKIPPED, PENDING})
CONDITIONS = frozenset({"GOOD", "NORMAL", "TIRED", "STRESSED"})   # RS_NIGHT_RECORD.CONDITION_LABEL (임시 값 목록)
TIRED_CODES = frozenset({"TIRED", "STRESSED"})                    # '피곤한 날'로 보는 컨디션


def to_dt(value) -> datetime | None:
    if value is None or value == "":
        return None
    if not isinstance(value, datetime):
        try:
            value = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except ValueError:
            return None
    return value if value.tzinfo else value.replace(tzinfo=KST)


def to_date(value) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.astimezone(KST).date() if value.tzinfo else value.date()
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


def to_time(value) -> time | None:
    if value is None or value == "":
        return None
    if isinstance(value, time):
        return value
    try:
        return time.fromisoformat(str(value)[:8])
    except ValueError:
        return None


def to_int(value, default=None):
    if value is None or isinstance(value, bool):
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


@dataclass
class StepRecord:
    """RS_SESSION_STEP 한 행."""
    step_name: str = ""
    status: str = PENDING
    step_order: int | None = None
    template_item_id: str | None = None      # 루틴 단계만 값이 있다(콘텐츠 단계는 NULL)
    planned_sec: int | None = None
    checked_at: datetime | None = None
    ai_verdict: str | None = None            # done / not_done / skipped / ambiguous / other

    @property
    def item_key(self) -> str | None:
        """항목별 수행률의 키. 콘텐츠 단계는 이름이 매일 달라 키가 없다."""
        return self.template_item_id

    @classmethod
    def from_row(cls, row: dict) -> "StepRecord":
        row = row or {}
        status = str(row.get("step_status") or PENDING).strip().upper()
        tid = row.get("template_item_id")
        return cls(step_name=str(row.get("step_name") or ""), status=status if status in STEP_STATUSES else PENDING,
                   step_order=to_int(row.get("step_order")), template_item_id=str(tid) if tid else None,
                   planned_sec=to_int(row.get("planned_duration_sec")), checked_at=to_dt(row.get("checked_at")),
                   ai_verdict=(str(row["ai_verdict"]).lower() if row.get("ai_verdict") else None))


@dataclass
class SessionRecord:
    """RS_MORNING_SESSION 한 행 + 그 세션의 단계들 + (그 아침에 적용된) 어젯밤 컨디션."""
    session_date: date | None = None
    session_id: str | None = None
    call_type: str | None = None             # MORNING_CALL_TYPE: ROUTINE / CONTENT
    coaching_skipped: bool = False           # 밤 대화에서 '내일 코칭 안 하기'에 동의한 날(브리핑만 진행). 이 날의 단계는 수행률·보상에서 뺀다
    planned_total_sec: int | None = None     # 그날 계획한 단계 시간 합계(초)
    target_wake_time: time | None = None
    first_response_at: datetime | None = None
    session_end_at: datetime | None = None
    rewake_count: int = 0                    # 8초 무응답으로 재기상 음악이 나온 횟수
    snooze_count: int = 0                    # "5분 뒤 다시 울리기" 횟수
    wake_checked_at: datetime | None = None  # 브리핑 전 확인 질문에 첫 대답을 한 시각 = 기상 성공 판정 시점
    wake_success: bool = False
    condition_code: str | None = None        # RS_NIGHT_RECORD.CONDITION_LABEL
    alarm_rang_at: datetime | None = None    # 알람이 울린 시각. 있으면 반응 시간(알람 → 첫 응답)을 정확히 계산한다
    alarm_mode: str | None = None            # RS_ALARM.ALARM_MODE. ON_TIME일 때만 목표 시각을 알람 시각으로 본다
    steps: list = field(default_factory=list)

    @property
    def wake_clock(self) -> time | None:
        """기상 시각(가정: 알람 후 첫 응답 시각 FIRST_RESPONSE_AT, KST)."""
        return self.first_response_at.astimezone(KST).time().replace(microsecond=0) if self.first_response_at else None

    @property
    def wake_minutes(self) -> int | None:
        c = self.wake_clock
        return c.hour * 60 + c.minute if c else None

    @property
    def reaction_sec(self) -> float | None:
        start = self.alarm_rang_at
        if start is None and self.alarm_mode == "ON_TIME" and self.session_date and self.target_wake_time:
            start = datetime.combine(self.session_date, self.target_wake_time, tzinfo=KST)
        if start and self.first_response_at:
            sec = (self.first_response_at - start).total_seconds()
            return sec if sec >= 0 else None
        return None

    @property
    def talk_minutes(self) -> float | None:
        if self.first_response_at and self.session_end_at:
            sec = (self.session_end_at - self.first_response_at).total_seconds()
            return sec / 60 if sec >= 0 else None
        return None

    @property
    def active_steps(self) -> list:
        """수행률·보상에 쓰는 단계. 코칭을 안 한 날은 단계가 있어도 세지 않는다."""
        return [] if self.coaching_skipped else self.steps

    @property
    def steps_done(self) -> int:
        return sum(s.status == DONE for s in self.active_steps)

    @classmethod
    def from_rows(cls, session_row: dict, step_rows=(), night_row: dict | None = None) -> "SessionRecord":
        s = session_row or {}
        label = str((night_row or {}).get("condition_label") or "").strip().upper()
        return cls(session_date=to_date(s.get("session_date")), session_id=str(s["session_id"]) if s.get("session_id") else None,
                   call_type=(str(s["morning_call_type"]).upper() if s.get("morning_call_type") else None),
                   coaching_skipped=bool(s.get("coaching_skipped")), planned_total_sec=to_int(s.get("planned_total_sec")),
                   target_wake_time=to_time(s.get("target_wake_time")),
                   first_response_at=to_dt(s.get("first_response_at")), session_end_at=to_dt(s.get("session_end_at")),
                   rewake_count=to_int(s.get("rewake_count"), 0), snooze_count=to_int(s.get("snooze_count"), 0),
                   wake_checked_at=to_dt(s.get("wake_checked_at")), wake_success=bool(s.get("wake_success")),
                   condition_code=label if label in CONDITIONS else None, alarm_rang_at=to_dt(s.get("alarm_rang_at")),
                   alarm_mode=(str(s["alarm_mode"]).upper() if s.get("alarm_mode") else None),
                   steps=sorted((StepRecord.from_row(r) for r in (step_rows or [])), key=lambda x: x.step_order or 0))


def build_sessions(session_rows, step_rows=(), night_rows=()) -> list[SessionRecord]:
    """DB에서 읽은 세 목록을 묶는다. 단계는 session_id로, 밤 기록은 (user_id, apply_date = session_date)로 연결한다.

    session_id나 user_id 칸이 없는 행은 그 기준으로 묶지 않는다(단계는 못 붙고, 밤 기록은 날짜만으로 연결한다).
    """
    steps_by_session: dict[str, list] = {}
    for r in step_rows or []:
        if r and r.get("session_id"):
            steps_by_session.setdefault(str(r["session_id"]), []).append(r)
    nights: dict[tuple, dict] = {}
    for r in night_rows or []:
        d = to_date((r or {}).get("apply_date"))
        if d:
            nights[(str(r.get("user_id") or ""), d)] = r
    out = []
    for s in session_rows or []:
        if not s:
            continue
        d = to_date(s.get("session_date"))
        night = nights.get((str(s.get("user_id") or ""), d)) or nights.get(("", d))   # user_id 칸이 없는 밤 기록은 날짜만으로 연결
        out.append(SessionRecord.from_rows(s, steps_by_session.get(str(s.get("session_id") or ""), []), night))
    return out
