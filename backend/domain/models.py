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
    theme: str = "AUTO"                    # 11/18 LIGHT / DARK / AUTO
    app_language: str = "ko"
    personality: str = "KIND"              # 11/16 KIND / FRIENDLY / STRICT / CHEERFUL (speech_style을 대신함)
    intensity: int = 2                     # 깨우는 말의 강도 1~3
    auto_intensity: bool = True            # 잘 못 일어나는 날이 이어지면 강도를 자동으로 올림
    english_level: str = "BEGINNER"        # BEGINNER / INTERMEDIATE
    wake_max_start_level: int | None = None  # 11/22 기상 볼륨 시작 단계 상한 1~5 (앞으로 계속, 비우면 제한 없음)
    wake_next_level: int | None = None       # 11/22 다음 알람 한 번만 이 단계로 시작 1~5 (쓰고 나면 비움)
    updated_at: datetime | None = None


@dataclass(frozen=True)
class AppConfig:
    """RS_APP_CONFIG (1행). 비어 있으면 codes의 기본값을 쓴다."""
    routine_max_sec: int = 900
    content_duration_sec: int | None = None   # 영어·게임 등 콘텐츠 시간
    stretch_duration_sec: int | None = None   # 스트레칭만 (11/18, DB 기본 180)


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
    """RS_ALARM. 모닝콜(WAKE)은 루틴 알람(template_id) 또는 콘텐츠 알람(content_type) 중 하나,
    굿나잇콜(BEDTIME, 11/18)은 모닝콜 칸을 모두 비운다."""
    alarm_id: str
    user_id: str
    alarm_time: time
    morning_call_type: str | None          # ROUTINE / CONTENT (굿나잇콜이면 None)
    repeat_days: str = "1111111"           # 월~일, 1 = 울림
    label: str | None = None
    alarm_mode: str = "ON_TIME"
    template_id: str | None = None         # 루틴 알람일 때 필수
    content_type: str | None = None        # 콘텐츠 알람일 때 필수
    content_option: str | None = None
    enabled: bool = True
    alarm_kind: str = "WAKE"               # WAKE 모닝콜 / BEDTIME 굿나잇콜
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
class AlarmRoutineItem:
    """알람에서 실제로 쓰는 항목 = 프리셋 항목 + 알람별 설정(RS_ALARM_ROUTINE_ITEM). 0초면 뺀 항목.

    DB 함수 rs_alarm_routine_items(alarm_id)의 한 줄 + default_duration_sec.
    내일만 바꾼 값을 덮어쓴 목록(rs_night_routine_items)이면 alarm_duration_sec에 알람 설정 시간이 들어 있다.
    """
    template_item_id: str
    item_order: int
    item_name: str
    default_duration_sec: int
    duration_sec: int
    response_type: str = "PLAY"
    script: str | None = None
    alarm_duration_sec: int | None = None      # 내일만 목록에서: 알람 설정 시간 (내일만 저장은 이 값과 다른 항목만)

    @property
    def customized(self) -> bool:
        return self.duration_sec != self.default_duration_sec


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
    voice_text: str | None = None              # 내일의 한마디를 기기에서 받아쓴 글 (음성이 있을 때만, 11/06)
    override_alarm_id: str | None = None
    override_wake_time: time | None = None
    override_skip_coaching: bool = False       # 내일 하루 코칭 안 하기 (브리핑만)
    diary_text: str | None = None
    extract_status: str = "PENDING"
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass(frozen=True)
class NightRaw:
    """RS_NIGHT_RAW (서버만). transcript = [{role, text, at}]"""
    night_record_id: str
    transcript: list | None = None
    audio_path: str | None = None
    started_at: datetime | None = None
    ended_at: datetime | None = None
    expires_at: datetime | None = None


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
class MorningSession:
    """RS_MORNING_SESSION. 만들 때는 SessionSnapshot 값, 진행하며 나머지 칸을 채운다."""
    session_id: str
    user_id: str
    alarm_id: str | None
    session_date: date
    morning_call_type: str
    template_id: str | None
    content_type: str | None
    content_option: str | None
    base_wake_time: time
    target_wake_time: time
    coaching_skipped: bool = False
    planned_total_sec: int | None = None   # 단계를 만든 뒤 채운다
    alarm_rang_at: datetime | None = None
    first_response_at: datetime | None = None
    rewake_count: int = 0
    snooze_count: int = 0                  # "5분 뒤 다시 울리기" 횟수 (11/01 기상 성공 기준, DB 컬럼 추가 요청)
    wake_checked_at: datetime | None = None  # 브리핑 전 확인 멘트를 모두 마친 시각 = 기상 성공 판정 시점 (DB 컬럼 추가 요청)
    wake_success: bool = False
    session_end_at: datetime | None = None
    fortune_text: str | None = None        # 오늘의 포춘 문장 (11/06). 세 칸은 모두 있거나 모두 없다
    fortune_style: str | None = None       # COOKIE / HOROSCOPE
    fortune_source: str | None = None      # ai / fallback / safe
    applied_intensity: int | None = None   # 그날 실제로 쓴 강도 (자동 강도 반영 후, 11/16)
    stretch_area: str | None = None        # 그날 고른 스트레칭 부위 (11/16)
    snoozed_until: datetime | None = None  # 미루기(버튼·말)로 다시 울릴 시각 (11/22). 횟수는 snooze_count
    reported_bedtime: datetime | None = None   # 모닝콜에서 말한 어젯밤 잠든 시각 (11/27 DB, 리포트 취침 시각을 덮어씀)
    auto_connected: bool = False           # 이 알람을 3번 미룬 뒤 자동 연결로 시작 (11/27 DB, 기상 볼륨 학습 제외)
    flow_completed_at: datetime | None = None  # 포춘까지 모닝콜을 끝까지 마친 시각 (11/27 DB, 리포트 기상 성공 기준)


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
    score: int | None = None               # 게임 기록: 제한 시간 안에 주고받은 횟수 (11/22, 0 이상)


@dataclass(frozen=True)
class RecentRecords:
    """개인화 build_sessions()와 리포트 입력: 기간 안의 세션·단계·밤 기록."""
    sessions: list[MorningSession]
    steps: list[SessionStep]
    nights: list[NightRecord]


@dataclass(frozen=True)
class StrategyLog:
    """RS_STRATEGY_LOG (서버만). 컬럼은 AI 파트 DecisionLog.to_row()와 같다."""
    log_id: str
    user_id: str
    session_date: date
    source: str
    mode: str
    session_id: str | None = None
    strategy_id: str | None = None
    strategy: dict | None = None           # 재연결 때 strategy_block(Strategy.from_dict(strategy))로 같은 문장을 다시 만든다
    rule_ids: list | None = None
    context_key: str | None = None
    propensity: float | None = None
    allowed_arms: list | None = None
    explored: bool | None = None
    ab_group: str | None = None
    ab_propensity: float | None = None
    stats: dict | None = None
    prompt_version: str | None = None
    reward: float | None = None
    error: str | None = None
    created_at: datetime | None = None


@dataclass(frozen=True)
class DiaryPhoto:
    """RS_DIARY_PHOTO. photo_path = {user_id}/{diary_date}.jpg (photo 버킷)"""
    diary_photo_id: str
    user_id: str
    diary_date: date
    photo_path: str
    created_at: datetime | None = None


@dataclass(frozen=True)
class AiMusic:
    """RS_AI_MUSIC (공용 캐시). REWAKE는 genre, SLEEP은 mood."""
    music_id: str
    music_type: str
    music_prompt: str
    music_path: str
    genre: str | None = None
    mood: str | None = None
    created_at: datetime | None = None


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
