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
RoutineMode = Literal[codes.ROUTINE_MODES]
ContentType = Literal[codes.CONTENT_TYPES]
AlarmMode = Literal[codes.ALARM_MODES]
AlarmCoaching = Literal[codes.ALARM_COACHING_MIN]


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
    template_id: str
    coaching_min: AlarmCoaching = 20


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
    routine_mode: str | None
    content_type: str | None
    content_option: str | None
    coaching_min: int
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
    routine_mode: RoutineMode | None = None
    content_type: ContentType | None = None
    content_option: str | None = None
    coaching_min: AlarmCoaching = 20
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
    routine_mode: RoutineMode | None = None
    content_type: ContentType | None = None
    content_option: str | None = None
    coaching_min: AlarmCoaching | None = None
    enabled: bool | None = None


# ---------- 프리셋·개인화 ----------
class TemplateItemOut(Out):
    template_item_id: str
    item_name: str
    item_order: int
    duration_10_sec: int | None
    duration_20_sec: int | None
    response_type: str


class PresetOut(BaseModel):
    template_id: str
    template_name: str
    target_desc: str | None
    description: str | None
    total_10_sec: int
    total_20_sec: int
    items: list[TemplateItemOut]


class UserItemOut(TemplateItemOut):
    enabled: bool


class UserItemPatch(BaseModel):
    enabled: bool
