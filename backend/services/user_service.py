"""사용자·설정·온보딩."""
from dataclasses import dataclass, replace
from datetime import date, time

from backend.core.errors import Conflict, NotFound, ValidationFailed
from backend.domain.alarm_rules import ALARM_FIELDS, validate_alarm
from backend.domain.models import Alarm, User, UserSetting
from backend.domain.user_rules import validate_setting, validate_user
from backend.jobs import maintenance
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
        return Profile(self.r.users.get_user(user_id), self.r.users.get_setting(user_id))

    def onboard(self, user_id: str, inp: OnboardingInput) -> tuple[Profile, Alarm | None]:
        """사용자·설정·첫 알람을 만들고 마지막에 ONBOARDING_DONE을 켠다.

        중간에 실패해 다시 호출되면 이어서 진행한다(이미 알람이 있으면 새로 만들지 않음). 완료된 사용자는 409.
        """
        existing = self.r.users.get_user(user_id)
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
            template = self.r.presets.get_template(alarm.template_id)
            if not template or not template.is_active:
                raise ValidationFailed("선택할 수 없는 루틴 프리셋이에요")

        user_values = {f: getattr(user, f) for f in USER_FIELDS}
        if existing:
            self.r.users.update_user(user_id, user_values)
        else:
            self.r.users.create_user(user_id, **user_values)
        setting = self.r.users.save_setting(user_id, {f: getattr(setting, f) for f in SETTING_FIELDS})
        created = None if self.r.alarms.list_alarms(user_id) else self.r.alarms.create_alarm(user_id, _alarm_values(alarm))
        self.r.users.set_onboarding_done(user_id)
        return Profile(self.r.users.get_user(user_id), setting), created

    def update_user(self, user_id: str, changes: dict) -> User:
        user = self.r.users.get_user(user_id)
        if not user:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "USER_NOT_FOUND")
        validate_user(replace(user, **_pick(changes, USER_FIELDS)))
        return self.r.users.update_user(user_id, changes)

    def delete_account(self, user_id: str) -> int:
        """탈퇴. 순서(Storage 파일 먼저 → Auth 계정, DB 행은 CASCADE)는 backend-db의 정리 작업과 같은 함수를 쓴다."""
        return maintenance.delete_account(self.r, user_id)

    def update_setting(self, user_id: str, changes: dict) -> UserSetting:
        setting = self.r.users.get_setting(user_id)
        if not setting:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        validate_setting(replace(setting, **_pick(changes, SETTING_FIELDS)))
        return self.r.users.save_setting(user_id, changes)


def _alarm_values(alarm: Alarm) -> dict:
    return {f: getattr(alarm, f) for f in ALARM_FIELDS}


def _pick(changes: dict, allowed: set) -> dict:
    unknown = set(changes) - allowed
    if unknown:
        raise ValidationFailed(f"바꿀 수 없는 항목이에요: {', '.join(sorted(unknown))}")
    return changes
