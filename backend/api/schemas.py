"""요청·응답 형식. 필드 이름은 정의서 컬럼명(소문자), 시각은 "HH:MM"(KST)."""
from datetime import date, datetime, time
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer, model_validator

from backend.domain import codes

HHMM = Annotated[time, PlainSerializer(lambda t: t.strftime("%H:%M"), return_type=str)]

Gender = Literal[codes.GENDERS]
Tone = Literal[codes.TONES]
SpeechStyle = Literal[codes.SPEECH_STYLES]
Personality = Literal[codes.PERSONALITIES]
Intensity = Literal[codes.INTENSITIES]
EnglishLevel = Literal[codes.ENGLISH_LEVELS]
StretchArea = Literal[codes.STRETCH_AREA_INPUTS]   # 새 12개 + 옛 5개(서비스가 새 값으로 바꿈)
VoiceType = Literal[codes.VOICE_TYPES]
MusicGenre = Literal[codes.MUSIC_GENRES]
WakeLevel = Literal[codes.WAKE_LEVELS]
Theme = Literal[codes.THEME_INPUTS]               # LIGHT/DARK/AUTO + 예전 SYSTEM(서비스가 AUTO로 바꿈)
CallType = Literal[codes.MORNING_CALL_TYPES]
AlarmKind = Literal[codes.ALARM_KINDS]
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
    speech_style: str                   # 11/13부터 personality로 대체 (DB가 칸을 지울 때까지 함께 준다)
    personality: str
    intensity: int
    auto_intensity: bool
    english_level: str
    voice_type: str
    sleep_alarm_enabled: bool
    sleep_time: HHMM
    music_genre: str
    theme: str
    app_language: str
    wake_max_start_level: int | None    # 기상 알람 시작 볼륨 상한 1~5 (null = 제한 없음, 11/22)
    wake_next_level: int | None         # 다음 알람 한 번만 이 단계로 시작 1~5 (울리고 나면 서버가 비움)
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
    speech_style: SpeechStyle = "KIND"  # 예전 앱용. personality가 없으면 이 값으로 정한다(FIRM → STRICT)
    personality: Personality | None = None
    intensity: Intensity = 2
    auto_intensity: bool = True
    english_level: EnglishLevel = "BEGINNER"
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
    personality: Personality | None = None
    intensity: Intensity | None = None
    auto_intensity: bool | None = None
    english_level: EnglishLevel | None = None
    voice_type: VoiceType | None = None
    sleep_alarm_enabled: bool | None = None
    sleep_time: time | None = None
    music_genre: MusicGenre | None = None
    theme: Theme | None = None
    app_language: str | None = Field(default=None, min_length=1, max_length=10)
    wake_max_start_level: WakeLevel | None = None   # null을 보내면 비운다 (아래 둘만, 나머지는 null이면 무시)
    wake_next_level: WakeLevel | None = None


SETTING_NULLABLE = {"wake_max_start_level", "wake_next_level"}


class AiSettingChangeIn(BaseModel):
    """AI update_setting 함수의 인자를 앱이 그대로 보낸다 (11/20 회의 4, AI CONTRACTS B22)."""
    field: Literal[codes.AI_SETTING_FIELDS]
    value: str = Field(min_length=1, max_length=20)
    scope: Literal[codes.AI_SETTING_SCOPES] | None = None


# ---------- 알람 ----------
class AlarmOut(Out):
    alarm_id: str
    alarm_time: HHMM
    repeat_days: str
    label: str | None
    alarm_mode: str
    alarm_kind: str                     # WAKE 모닝콜 / BEDTIME 굿나잇콜 (11/18)
    morning_call_type: str | None       # 굿나잇콜이면 null
    template_id: str | None
    content_type: str | None
    content_option: str | None
    enabled: bool
    created_at: datetime | None = None
    updated_at: datetime | None = None


class AlarmIn(BaseModel):
    """굿나잇콜은 {"alarm_kind": "BEDTIME", "alarm_time": ...}만 보낸다 (모닝콜 칸은 비우고 alarm_mode는 ON_TIME)."""
    model_config = ConfigDict(extra="forbid")
    alarm_time: time
    alarm_kind: AlarmKind = "WAKE"
    morning_call_type: CallType | None = None   # 모닝콜이면 필수
    repeat_days: str = Field(default="1111111", pattern=r"^[01]{7}$")
    label: str | None = Field(default=None, max_length=20)
    alarm_mode: AlarmMode = "ON_TIME"
    template_id: str | None = None
    content_type: ContentType | None = None
    content_option: str | None = None
    enabled: bool = True


class AlarmPatch(BaseModel):
    """보낸 항목만 바꾼다. 유형을 바꿀 때는 반대쪽 항목을 null로 함께 보낸다(예: CONTENT로 → template_id: null).
    굿나잇콜로 바꿀 때는 {"alarm_kind": "BEDTIME"}만 보내면 서버가 모닝콜 칸을 비운다.
    모닝콜로 바꿀 때는 alarm_kind와 함께 morning_call_type과 template_id 또는 content_type을 보낸다."""
    model_config = ConfigDict(extra="forbid")
    alarm_time: time | None = None
    alarm_kind: AlarmKind | None = None
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
    content_duration_sec: int           # 콘텐츠 알람 고정 시간 (영어·게임 등)
    stretch_duration_sec: int           # 스트레칭 콘텐츠만 (11/18, 3분)


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
    voice_text: str | None = None       # 내일의 한마디를 기기에서 받아쓴 글 (음성이 있을 때만)


class VoiceTextIn(BaseModel):
    text: str = Field(default="", max_length=500)   # 빈 글이면 비운다


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
    snoozed_until: datetime | None      # 마지막 미루기로 다시 울릴 시각 (11/22)
    wake_checked_at: datetime | None    # 확인 멘트를 마친 시각 (이때 기상 성공을 정함)
    wake_success: bool
    session_end_at: datetime | None
    fortune_text: str | None = None     # 오늘의 포춘 문장 (아직 만들지 않았으면 null)
    fortune_style: str | None = None    # COOKIE 포춘쿠키 / HOROSCOPE 오늘의 운세
    fortune_source: str | None = None   # ai / fallback 기본 문장 / safe 위기 표현이 있어 담담한 문장
    applied_intensity: int | None = None  # 그날 쓴 깨우는 말 강도 1~3 (자동 강도 반영 후)
    stretch_area: str | None = None     # 그날 고른 스트레칭 부위 (아직 안 골랐으면 null)


class StretchStepOut(Out):
    """부위 루틴의 한 단계. 부위를 고르면 이 순서대로 세션 단계가 바뀐다(번호는 1부터)."""
    step_order: int
    step_name: str
    planned_duration_sec: int | None
    response_type: str
    script: str | None


class SessionViewOut(BaseModel):
    session: SessionOut
    steps: list[StepOut]
    progress: ProgressOut
    morning_plan: list[str] | None = None   # 세션 시작 응답에만: 아침 순서표 (예: BRIEFING, ONE_LINER, COACHING, FORTUNE). 비어 있으면 예전 순서
    stretch_routines: dict[str, list[StretchStepOut]] | None = None   # 세션 시작 응답에만: 스트레칭 알람에서 부위를 아직 고를 수 있을 때 5개 루틴


class StretchAreaIn(BaseModel):
    area: StretchArea


class LocationIn(BaseModel):
    """브리핑 날씨용 현재 위치. 앱이 매번 보내고 DB에 저장하지 않는다."""
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    name: str = Field(default="", max_length=30, description='기기에서 만든 한국어 지역 이름(예: "광진구"). 비우면 날씨 API의 이름')


class BriefingTokenIn(BaseModel):
    events_today: list[CalendarEventIn] = Field(default=[], max_length=30)
    location: LocationIn | None = None   # 없으면 날씨 없이 브리핑


class FortuneTokenOut(LiveTokenOut):
    fortune_text: str                   # AI가 읽을 문장 (자막 표시용)
    fortune_style: str                  # COOKIE 포춘쿠키 / HOROSCOPE 오늘의 운세
    fortune_source: str                 # ai / fallback 기본 문장 / safe 위기 표현이 있어 담담한 문장


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


class SnoozeIn(BaseModel):
    """미루기. 둘 다 없으면 버튼 기본 5분. 말로 미루기(AI reschedule_alarm)는 받은 인자 하나를 그대로 보낸다."""
    minutes_later: int | None = Field(None, ge=1, le=codes.SNOOZE_MAX_MIN)   # 지금부터 몇 분 뒤
    at_time: HHMM | None = None                                             # 오늘 "HH:MM" (지금보다 뒤, 60분 안)

    @model_validator(mode="after")
    def _one_of(self):
        if self.minutes_later is not None and self.at_time is not None:
            raise ValueError("minutes_later와 at_time 중 하나만 보내 주세요")
        return self


class WakeVolumeIn(BaseModel):
    """아침 기상 볼륨 결과 (앱 보고). 재기상(8초 무응답 음악)은 서버가 세션 기록으로 따로 본다."""
    start_level: WakeLevel                 # 알람이 울리기 시작한 단계
    received_level: WakeLevel | None       # 전화를 받은 순간의 단계, 받지 않고 껐으면 null

    @model_validator(mode="after")
    def _not_below_start(self):
        if self.received_level is not None and self.received_level < self.start_level:
            raise ValueError("받은 단계는 시작 단계보다 낮을 수 없어요")
        return self


class WakeLogOut(BaseModel):
    """그 아침의 기상 볼륨 기록 (RS_WAKE_LOG 중 앱이 보는 칸)."""
    session_date: date
    applied_level: int | None              # 정한 시작 단계
    start_level: int | None                # 실제로 울리기 시작한 단계 (앱 보고)
    received_level: int | None             # 전화를 받은 단계 (못 받았으면 null)
    confirmed: bool | None                 # 받은 뒤 확인 질문에 답하고 재기상이 없었는가 (학습 전이면 null)
    source: str | None                     # bandit 개인화 / user_today·user_max 사용자 / hold_no_notice 알리지 못해 유지 / important_day / default
    state: str | None                      # RECOMMENDED / EXPLORING(찾는 중) / USER
    user_set: bool                         # 사용자가 정한 값으로 울렸는가


class WakeNextOut(BaseModel):
    session_date: date                     # 다음에 울릴 아침
    alarm_id: str                          # 그날 첫 모닝콜 (학습은 이 알람으로, 그날 다른 모닝콜도 같은 단계로 울린다)
    alarm_time: HHMM
    start_level: int                       # 알람 시작 볼륨 1~5
    rewake_level: int                      # 재기상 음악 볼륨 = 시작 + 1 (2~6, AI B23)
    source: str | None
    state: str | None


class SnoozeOut(BaseModel):
    snooze_count: int                   # 이 세션의 미루기 횟수
    snoozes_today: int                  # 오늘 모든 모닝 세션의 미루기 합계 (하루 3번까지)
    snoozed_until: datetime             # 다시 울릴 시각 (KST). 앱은 이 시각으로 알람을 한 번 다시 예약한다


# ---------- 리포트·일기 ----------
class TotalsOut(Out):
    n_sessions: int
    wake_success_rate: float | None      # 0~1, 기록이 없으면 null
    avg_wake_clock: str | None           # 평균 기상 시각 "HH:MM" (첫 응답 기준)
    routine_done: int
    routine_total: int
    routine_completion_rate: float | None
    bed_days: int = 0                    # 밤 대화를 마친 날 수
    bed_record_days: int = 0             # 취침 성공률 분모: 기록 기간(첫 기록일~어제, 오늘 밤 대화를 마쳤으면 오늘까지) 중 이 범위의 날 수
    bedtime_success_rate: float | None = None   # bed_days / bed_record_days (0~1), 분모가 0이면 null
    avg_bed_clock: str | None = None     # 평균 취침 시각 "HH:MM" (밤 대화를 마친 시각 기준)


class BucketOut(TotalsOut):
    label: str                           # 주: 월~일, 월: 1주~5주, 연: 1월~12월
    start: date
    end: date                            # 포함하지 않는 끝 날짜


class ReportOut(Out):
    period: str
    start: date
    end: date
    overall: TotalsOut                   # 화면 상단 도넛: 전체 기간 누적 기상 성공률·루틴 성공률
    totals: TotalsOut                    # 선택한 기간: 그래프 영역의 평균 기상 시각·루틴 완료율(52/70)
    series: list[BucketOut]
    current_streak: int
    best_streak: int
    wake_levels: list[WakeLogOut] = []   # 나의 기상 단계 변화: 이 기간(오늘까지) 날짜별 시작·받은 단계 (11/22)


class CalendarDayOut(BaseModel):
    date: date
    photo_url: str | None                # 사진을 올린 날 (없으면 루틴 성공률을 보여 준다)
    routine_completion_rate: float | None
    routine_done: int
    routine_total: int
    has_session: bool
    has_night_record: bool
    condition_label: str | None = None   # 그날 밤 기록의 컨디션 GOOD/NORMAL/TIRED/STRESSED (밤 기록·컨디션이 없으면 null)


class DiarySessionOut(BaseModel):
    session: SessionOut
    steps: list[StepOut]


class DiaryDayOut(BaseModel):
    date: date
    summary: TotalsOut
    night: NightRecordOut | None         # RECORD_DATE = 이 날짜인 밤 기록 (한 줄 일기, 요약, 컨디션)
    sessions: list[DiarySessionOut]      # SESSION_DATE = 이 날짜인 아침 세션, 알람이 먼저 울린 순 (울리지 않은 세션은 뒤)
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
