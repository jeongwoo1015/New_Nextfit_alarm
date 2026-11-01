"""서비스가 다루는 데이터. 필드 이름은 정의서 컬럼명(소문자)과 같다.

Repository(Supabase 구현은 backend-db 담당)는 DB 행을 이 형식으로 돌려준다.
"""
from dataclasses import dataclass, field
from datetime import date, datetime, time


@dataclass(frozen=True)
class Alarm:
    """RS_ALARM"""
    alarm_id: str
    user_id: str
    alarm_time: time
    morning_call_type: str                 # ROUTINE / CONTENT
    repeat_days: str = "1111111"           # 월~일, 1 = 울림
    label: str | None = None
    alarm_mode: str = "ON_TIME"
    template_id: str | None = None         # 루틴 알람일 때 필수
    routine_mode: str | None = None        # 루틴 알람일 때 필수 (PRESET / CUSTOM)
    content_type: str | None = None        # 콘텐츠 알람일 때 필수
    content_option: str | None = None
    coaching_min: int = 20                 # 10 / 20, 브리핑 제외
    enabled: bool = True
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass(frozen=True)
class TemplateItem:
    """RS_ROUTINE_TEMPLATE_ITEM. 버전 시간이 NULL이면 그 버전에서 진행하지 않는다."""
    template_item_id: str
    template_id: str
    item_name: str
    item_order: int
    duration_10_sec: int | None = None
    duration_20_sec: int | None = None
    response_type: str = "PLAY"
    script: str | None = None


@dataclass(frozen=True)
class NightRecord:
    """RS_NIGHT_RECORD"""
    night_record_id: str
    user_id: str
    record_date: date
    apply_date: date
    ai_summary: str | None = None
    tomorrow_goal: str | None = None
    tomorrow_notes: tuple[str, ...] = ()
    condition_label: str | None = None
    condition_text: str | None = None
    encouragement: str | None = None
    insight: str | None = None
    voice_audio_path: str | None = None
    override_alarm_id: str | None = None
    override_wake_time: time | None = None
    override_coaching_min: int | None = None   # 10 / 0(수행 안 함)
    diary_text: str | None = None
    extract_status: str = "PENDING"
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass(frozen=True)
class SessionSnapshot:
    """RS_MORNING_SESSION을 만들 때 넣는 그날의 값(알람 설정이 나중에 바뀌어도 기록은 유지)."""
    user_id: str
    alarm_id: str
    session_date: date
    morning_call_type: str
    template_id: str | None
    routine_mode: str | None
    content_type: str | None
    content_option: str | None
    base_wake_time: time
    target_wake_time: time
    coaching_min: int                      # 20 / 10 / 0(수행 안 함)


@dataclass(frozen=True)
class StepPlan:
    """RS_SESSION_STEP에 PENDING으로 만들 한 줄 + Live 코칭에 넘길 응답 유형·안내문."""
    step_order: int
    step_name: str
    template_item_id: str | None = None    # 루틴이면 필수, 콘텐츠면 NULL
    planned_duration_sec: int | None = None
    response_type: str = "QA"
    script: str | None = None


@dataclass(frozen=True)
class SessionStep:
    """RS_SESSION_STEP"""
    step_id: str
    user_id: str
    session_id: str
    step_order: int
    step_name: str
    template_item_id: str | None = None
    planned_duration_sec: int | None = None
    step_status: str = "PENDING"
    checked_at: datetime | None = None
    ai_verdict: str | None = None


@dataclass(frozen=True)
class Progress:
    """진행 개수와 "완료!" 표시 (정의서: RS_SESSION_STEP 상태값으로 바로 계산)."""
    total: int
    done: int
    skipped: int
    pending: int = field(init=False)

    def __post_init__(self):
        object.__setattr__(self, "pending", self.total - self.done - self.skipped)

    @property
    def finished(self) -> bool:
        return self.total > 0 and self.pending == 0
