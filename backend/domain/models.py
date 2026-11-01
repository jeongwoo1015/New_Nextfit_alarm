"""서비스가 다루는 데이터. 필드 이름은 정의서(11/01 수정판) 컬럼명(소문자)과 같다.

Repository(Supabase 구현은 backend-db 담당)는 DB 행을 이 형식으로 돌려준다.
"""
from dataclasses import dataclass, field
from datetime import date, datetime, time


@dataclass(frozen=True)
class User:
    """RS_USER. USER_ID = auth.users(id). 온보딩 1단계 값이 NOT NULL이라 온보딩 완료 때 만든다."""
    user_id: str
    user_name: str
    birth_date: date
    gender: str = "NONE"
    onboarding_done: bool = False
    created_at: datetime | None = None


@dataclass(frozen=True)
class UserSetting:
    """RS_USER_SETTING (알람과 상관없는 사용자 전체 설정)"""
    user_id: str
    user_call_name: str
    music_genre: str
    tone: str = "FORMAL"
    speech_style: str = "KIND"
    voice_type: str = "FEMALE"
    sleep_alarm_enabled: bool = True
    sleep_time: time = time(23, 0)
    theme: str = "SYSTEM"
    app_language: str = "ko"
    updated_at: datetime | None = None


@dataclass(frozen=True)
class AppConfig:
    """RS_APP_CONFIG (1행). content_duration_sec는 팀 확정 전이라 비어 있을 수 있다."""
    routine_max_sec: int = 900
    content_duration_sec: int | None = None


@dataclass(frozen=True)
class RoutineTemplate:
    """RS_ROUTINE_TEMPLATE. 사용 중인 프리셋은 지우지 않고 IS_ACTIVE로 비활성화한다."""
    template_id: str
    template_name: str
    target_desc: str | None = None
    description: str | None = None
    is_active: bool = True
    created_at: datetime | None = None


@dataclass(frozen=True)
class Alarm:
    """RS_ALARM. 루틴 알람(template_id) 또는 콘텐츠 알람(content_type) 중 하나."""
    alarm_id: str
    user_id: str
    alarm_time: time
    morning_call_type: str                 # ROUTINE / CONTENT
    repeat_days: str = "1111111"           # 월~일, 1 = 울림
    label: str | None = None
    alarm_mode: str = "ON_TIME"
    template_id: str | None = None         # 루틴 알람일 때 필수
    content_type: str | None = None        # 콘텐츠 알람일 때 필수
    content_option: str | None = None
    enabled: bool = True
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass(frozen=True)
class TemplateItem:
    """RS_ROUTINE_TEMPLATE_ITEM. 기본 시간은 30초 단위, 프리셋마다 합계 600초."""
    template_item_id: str
    template_id: str
    item_name: str
    item_order: int
    default_duration_sec: int
    response_type: str = "PLAY"
    script: str | None = None


@dataclass(frozen=True)
class RoutineItem:
    """알람에서 실제로 쓰는 항목 = 프리셋 항목 + 알람별 설정(RS_ALARM_ROUTINE_ITEM). 0초면 뺀 항목.

    DB 함수 rs_alarm_routine_items(alarm_id)의 한 줄과 같다.
    """
    item: TemplateItem
    duration_sec: int

    @property
    def customized(self) -> bool:
        return self.duration_sec != self.item.default_duration_sec


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
    override_skip_coaching: bool = False       # 내일 하루 코칭 안 하기 (브리핑만)
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
    content_type: str | None
    content_option: str | None
    base_wake_time: time
    target_wake_time: time
    coaching_skipped: bool = False


@dataclass(frozen=True)
class StepPlan:
    """RS_SESSION_STEP에 PENDING으로 만들 한 줄 + Live 코칭에 넘길 응답 유형·안내문.

    루틴 단계의 응답 유형·안내문은 프리셋 항목에 있고, 콘텐츠 단계는 step_detail에 담아 재연결 때 다시 쓴다.
    """
    step_order: int
    step_name: str
    template_item_id: str | None = None    # 루틴이면 필수, 콘텐츠면 NULL
    planned_duration_sec: int | None = None
    response_type: str = "QA"
    script: str | None = None
    step_detail: dict | None = None        # 콘텐츠 단계 내용 (RS_SESSION_STEP.STEP_DETAIL)


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
    step_detail: dict | None = None
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
