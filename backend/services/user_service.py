"""사용자·설정·온보딩."""
from dataclasses import dataclass, replace
from datetime import date, time

from backend.core.errors import Conflict, NotFound, ValidationFailed
from backend.domain.alarm_rules import validate_alarm
from backend.domain.models import Alarm, User, UserSetting
from backend.domain.user_rules import validate_setting, validate_user
from backend.repositories.registry import Repositories

# 정의서 팀 확인 사항 기본안: 온보딩에서 고른 유형(루틴 프리셋 또는 콘텐츠 종류)으로 07:00 알람 1개를 만든다
FIRST_ALARM_TIME = time(7, 0)

USER_FIELDS = {"user_name", "birth_date", "gender"}
SETTING_FIELDS = {"user_call_name", "tone", "speech_style", "voice_type", "sleep_alarm_enabled", "sleep_time",
                  "music_genre", "theme", "app_language"}


@dataclass(frozen=True)
class Profile:
    user: User | None
    setting: UserSetting | None

    @property
    def onboarding_done(self) -> bool:
        return bool(self.user and self.user.onboarding_done)


@dataclass(frozen=True)
class OnboardingInput:
    # 1단계 기본 정보
    user_name: str
    birth_date: date
    gender: str
    # 2단계 페르소나
    user_call_name: str
    tone: str
    speech_style: str
    voice_type: str
    music_genre: str
    # 3단계 루틴/콘텐츠 선택 → 4단계 프리셋(루틴) 또는 콘텐츠 종류. 콘텐츠 세부는 알람 세부 설정에서 고른다
    morning_call_type: str
    template_id: str | None = None
    content_type: str | None = None


class UserService:
    def __init__(self, repos: Repositories):
        self.r = repos

    def profile(self, user_id: str) -> Profile:
        return Profile(self.r.users.get(user_id), self.r.settings.get(user_id))

    def onboard(self, user_id: str, inp: OnboardingInput) -> tuple[Profile, Alarm | None]:
        """사용자·설정·첫 알람을 만들고 마지막에 ONBOARDING_DONE을 켠다.

        중간에 실패해 다시 호출되면 이어서 진행한다(이미 알람이 있으면 새로 만들지 않음). 완료된 사용자는 409.
        """
        existing = self.r.users.get(user_id)
        if existing and existing.onboarding_done:
            raise Conflict("이미 온보딩을 마쳤어요", "ONBOARDING_DONE")
        user = User(user_id, inp.user_name.strip(), inp.birth_date, inp.gender, onboarding_done=False)
        setting = UserSetting(user_id, inp.user_call_name.strip(), inp.music_genre, inp.tone, inp.speech_style, inp.voice_type)
        alarm = Alarm("", user_id, FIRST_ALARM_TIME, inp.morning_call_type, template_id=inp.template_id,
                      content_type=inp.content_type)
        validate_user(user)
        validate_setting(setting)
        validate_alarm(alarm)
        if alarm.morning_call_type == "ROUTINE":
            template = self.r.templates.get_template(alarm.template_id)
            if not template or not template.is_active:
                raise ValidationFailed("선택할 수 없는 루틴 프리셋이에요")

        user = self.r.users.update(replace(existing, **{f: getattr(user, f) for f in USER_FIELDS})) if existing \
            else self.r.users.create(user)
        setting = self.r.settings.upsert(setting)
        created = None if self.r.alarms.list_by_user(user_id) else self.r.alarms.create(alarm)
        user = self.r.users.update(replace(user, onboarding_done=True))
        return Profile(user, setting), created

    def update_user(self, user_id: str, changes: dict) -> User:
        user = self.r.users.get(user_id)
        if not user:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "USER_NOT_FOUND")
        user = replace(user, **_pick(changes, USER_FIELDS))
        validate_user(user)
        return self.r.users.update(user)

    def update_setting(self, user_id: str, changes: dict) -> UserSetting:
        setting = self.r.settings.get(user_id)
        if not setting:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        setting = replace(setting, **_pick(changes, SETTING_FIELDS))
        validate_setting(setting)
        return self.r.settings.upsert(setting)


def _pick(changes: dict, allowed: set) -> dict:
    unknown = set(changes) - allowed
    if unknown:
        raise ValidationFailed(f"바꿀 수 없는 항목이에요: {', '.join(sorted(unknown))}")
    return changes
