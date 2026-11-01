"""요청·응답 형식. 필드 이름은 정의서 컬럼명(소문자), 시각은 "HH:MM"(KST)."""
from datetime import date, datetime, time
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer

from backend.domain import codes

HHMM = Annotated[time, PlainSerializer(lambda t: t.strftime("%H:%M"), return_type=str)]

Gender = Literal[codes.GENDERS]
Tone = Literal[codes.TONES]
SpeechStyle = Literal[codes.SPEECH_STYLES]
VoiceType = Literal[codes.VOICE_TYPES]
MusicGenre = Literal[codes.MUSIC_GENRES]
Theme = Literal[codes.THEMES]
CallType = Literal[codes.MORNING_CALL_TYPES]
ContentType = Literal[codes.CONTENT_TYPES]
AlarmMode = Literal[codes.ALARM_MODES]


class Out(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------- 사용자·설정 ----------
class UserOut(Out):
    user_id: str
    user_name: str
    birth_date: date
    gender: str
    onboarding_done: bool
    created_at: datetime | None = None


class SettingOut(Out):
    user_call_name: str
    tone: str
    speech_style: str
    voice_type: str
    sleep_alarm_enabled: bool
    sleep_time: HHMM
    music_genre: str
    theme: str
    app_language: str
    updated_at: datetime | None = None


class ProfileOut(BaseModel):
    onboarding_done: bool
    user: UserOut | None
    setting: SettingOut | None


class OnboardingIn(BaseModel):
    user_name: str = Field(min_length=1, max_length=50)
    birth_date: date
    gender: Gender = "NONE"
    user_call_name: str = Field(min_length=1, max_length=30)
    tone: Tone = "FORMAL"
    speech_style: SpeechStyle = "KIND"
    voice_type: VoiceType = "FEMALE"
    music_genre: MusicGenre = "POP"     # 정의서에 기본값이 없어 정한 기본안
    morning_call_type: CallType         # 온보딩: 루틴 / 콘텐츠 선택
    template_id: str | None = None      # 루틴이면 프리셋 5종 중 하나
    content_type: ContentType | None = None   # 콘텐츠면 종류 (세부는 알람 세부 설정에서)


class UserPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_name: str | None = Field(default=None, min_length=1, max_length=50)
    birth_date: date | None = None
    gender: Gender | None = None


class SettingPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_call_name: str | None = Field(default=None, min_length=1, max_length=30)
    tone: Tone | None = None
    speech_style: SpeechStyle | None = None
    voice_type: VoiceType | None = None
    sleep_alarm_enabled: bool | None = None
    sleep_time: time | None = None
    music_genre: MusicGenre | None = None
    theme: Theme | None = None
    app_language: str | None = Field(default=None, min_length=1, max_length=10)


# ---------- 알람 ----------
class AlarmOut(Out):
    alarm_id: str
    alarm_time: HHMM
    repeat_days: str
    label: str | None
    alarm_mode: str
    morning_call_type: str
    template_id: str | None
    content_type: str | None
    content_option: str | None
    enabled: bool
    created_at: datetime | None = None
    updated_at: datetime | None = None


class AlarmIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    alarm_time: time
    morning_call_type: CallType
    repeat_days: str = Field(default="1111111", pattern=r"^[01]{7}$")
    label: str | None = Field(default=None, max_length=20)
    alarm_mode: AlarmMode = "ON_TIME"
    template_id: str | None = None
    content_type: ContentType | None = None
    content_option: str | None = None
    enabled: bool = True


class AlarmPatch(BaseModel):
    """보낸 항목만 바꾼다. 유형을 바꿀 때는 반대쪽 항목을 null로 함께 보낸다(예: CONTENT로 → template_id: null)."""
    model_config = ConfigDict(extra="forbid")
    alarm_time: time | None = None
    morning_call_type: CallType | None = None
    repeat_days: str | None = Field(default=None, pattern=r"^[01]{7}$")
    label: str | None = Field(default=None, max_length=20)
    alarm_mode: AlarmMode | None = None
    template_id: str | None = None
    content_type: ContentType | None = None
    content_option: str | None = None
    enabled: bool | None = None


# ---------- 프리셋·알람별 루틴 시간 ----------
class TemplateItemOut(Out):
    template_item_id: str
    item_name: str
    item_order: int
    default_duration_sec: int
    response_type: str


class PresetOut(BaseModel):
    template_id: str
    template_name: str
    target_desc: str | None
    description: str | None
    total_sec: int                      # 기본 시간 합계 (시드 규칙상 600초)
    items: list[TemplateItemOut]


class LimitsOut(BaseModel):
    routine_max_sec: int                # 알람 루틴 합계 상한 (RS_APP_CONFIG)
    duration_step_sec: int              # 항목 시간 단위 (30초)
    content_duration_sec: int           # 콘텐츠 알람 고정 시간


class RoutineItemOut(TemplateItemOut):
    duration_sec: int                   # 이 알람에서 쓰는 시간 (0 = 뺀 항목)
    customized: bool                    # 기본 시간과 다른가


class AlarmRoutineOut(BaseModel):
    alarm_id: str
    template_id: str
    total_sec: int
    max_sec: int
    items: list[RoutineItemOut]


class RoutineItemIn(BaseModel):
    template_item_id: str
    duration_sec: int = Field(ge=0, multiple_of=30)


class AlarmRoutineIn(BaseModel):
    """알람의 항목 시간 전체. 보내지 않은 항목은 프리셋 기본 시간, 빈 목록이면 전부 기본값으로 되돌리기."""
    items: list[RoutineItemIn]


# ---------- 밤 흐름 ----------
class CalendarEventIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    start: str = Field(default="", pattern=r"^$|^([01]\d|2[0-3]):[0-5]\d$")
    end: str = Field(default="", pattern=r"^$|^([01]\d|2[0-3]):[0-5]\d$")
    all_day: bool = False


class NightTokenIn(BaseModel):
    """앱이 폰 캘린더에서 읽은 오늘·내일 일정 (DB에 저장하지 않음)."""
    events_today: list[CalendarEventIn] = Field(default=[], max_length=30)
    events_tomorrow: list[CalendarEventIn] = Field(default=[], max_length=30)


class LiveTokenOut(BaseModel):
    """ws_url?access_token=<token>으로 연결 → 첫 메시지 {"setup": {"model": model}} → start_signal을 realtimeInput.text로.
    토큰은 한 번만 쓸 수 있다. 재연결은 새 토큰을 받는다."""
    token: str
    ws_url: str
    model: str
    start_signal: str
    expires_at: str


class TranscriptTurn(BaseModel):
    role: Literal["user", "assistant"]
    text: str = Field(max_length=4000)
    at: str | None = None


class TranscriptIn(BaseModel):
    """Gemini Live 받아쓰기(inputTranscription=user, outputTranscription=assistant). 시작 신호 글자는 넣지 않는다."""
    transcript: list[TranscriptTurn] = Field(min_length=1, max_length=2000)
    started_at: datetime | None = None
    ended_at: datetime | None = None


class NightRecordOut(Out):
    night_record_id: str
    record_date: date
    apply_date: date
    extract_status: str                 # PENDING이면 추출 중, FAILED면 서버가 나중에 다시 추출
    ai_summary: str | None = None
    tomorrow_goal: str | None = None
    tomorrow_notes: list[str] = []
    condition_label: str | None = None
    condition_text: str | None = None
    encouragement: str | None = None
    insight: str | None = None
    diary_text: str | None = None
    override_alarm_id: str | None = None
    override_wake_time: HHMM | None = None
    override_skip_coaching: bool = False
    voice_url: str | None = None        # 서명된 임시 URL


# ---------- 아침 흐름 ----------
class SessionStartIn(BaseModel):
    alarm_id: str


class StepOut(Out):
    step_order: int
    step_name: str
    template_item_id: str | None
    planned_duration_sec: int | None
    step_status: str
    checked_at: datetime | None
    ai_verdict: str | None


class ProgressOut(Out):
    total: int
    done: int
    skipped: int
    pending: int
    finished: bool                      # "완료!" 표시


class SessionOut(Out):
    session_id: str
    alarm_id: str | None
    session_date: date
    morning_call_type: str
    template_id: str | None
    content_type: str | None
    content_option: str | None
    base_wake_time: HHMM
    target_wake_time: HHMM
    coaching_skipped: bool              # TRUE면 브리핑만 (코칭 토큰 요청 불가)
    planned_total_sec: int | None
    alarm_rang_at: datetime | None
    first_response_at: datetime | None
    rewake_count: int
    snooze_count: int
    wake_checked_at: datetime | None    # 확인 멘트를 마친 시각 (이때 기상 성공을 정함)
    wake_success: bool
    session_end_at: datetime | None


class SessionViewOut(BaseModel):
    session: SessionOut
    steps: list[StepOut]
    progress: ProgressOut


class BriefingTokenIn(BaseModel):
    events_today: list[CalendarEventIn] = Field(default=[], max_length=30)


class CoachingTokenOut(LiveTokenOut):
    steps: list[StepOut]                # 오늘 단계 전체 (체크리스트 표시용). 토큰에는 PENDING 단계만 들어 있다


class VerdictIn(BaseModel):
    verdict: Literal[codes.AI_VERDICTS]


class StepStatusIn(BaseModel):
    step_status: Literal[codes.STEP_STATUSES]


class StepDurationIn(BaseModel):
    duration_sec: int = Field(ge=0, multiple_of=30)   # 0 = 오늘만 빼기


class RewakeOut(BaseModel):
    rewake_count: int


class SnoozeOut(BaseModel):
    snooze_count: int


# ---------- 리포트·일기 ----------
class TotalsOut(Out):
    n_sessions: int
    wake_success_rate: float | None      # 0~1, 기록이 없으면 null
    avg_wake_clock: str | None           # 평균 기상 시각 "HH:MM" (첫 응답 기준)
    routine_done: int
    routine_total: int
    routine_completion_rate: float | None


class BucketOut(TotalsOut):
    label: str                           # 주: 월~일, 월: 1주~5주, 연: 1월~12월
    start: date
    end: date                            # 포함하지 않는 끝 날짜


class ReportOut(Out):
    period: str
    start: date
    end: date
    totals: TotalsOut
    series: list[BucketOut]
    current_streak: int
    best_streak: int


class CalendarDayOut(BaseModel):
    date: date
    photo_url: str | None                # 사진을 올린 날 (없으면 루틴 성공률을 보여 준다)
    routine_completion_rate: float | None
    routine_done: int
    routine_total: int
    has_session: bool
    has_night_record: bool


class DiarySessionOut(BaseModel):
    session: SessionOut
    steps: list[StepOut]


class DiaryDayOut(BaseModel):
    date: date
    summary: TotalsOut
    night: NightRecordOut | None         # RECORD_DATE = 이 날짜인 밤 기록 (한 줄 일기, 요약, 컨디션)
    sessions: list[DiarySessionOut]      # SESSION_DATE = 이 날짜인 아침 세션
    photo_url: str | None


class PhotoOut(BaseModel):
    date: date
    photo_url: str | None


# ---------- AI 음악 ----------
class MusicOut(BaseModel):
    music_id: str
    music_type: str                     # REWAKE / SLEEP
    genre: str | None                   # REWAKE일 때
    mood: str | None                    # SLEEP일 때
    url: str                            # 서명된 임시 URL (music 버킷, mp3)
    expires_in: int                     # 초. 재기상 음악은 밤에 미리 받아 둘 수 있게 12시간
