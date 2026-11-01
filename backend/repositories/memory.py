"""메모리 저장소 — 테스트와 DB 없이 서버를 띄울 때 쓴다. 동작은 base.py의 규칙과 DB 트리거를 따른다."""
import uuid
from dataclasses import fields, replace

from backend.core.timeutil import now_kst
from backend.domain.models import Alarm, AppConfig, RoutineTemplate, TemplateItem, User, UserSetting
from backend.domain.routine_rules import build_routine

from .seed import seed_templates

_SETTING_FIELDS = {f.name for f in fields(UserSetting)} - {"user_id", "updated_at"}


class MemoryUserRepository:
    def __init__(self):
        self.users: dict[str, User] = {}
        self.settings: dict[str, UserSetting] = {}

    def get_user(self, user_id):
        return self.users.get(user_id)

    def create_user(self, user_id, user_name, birth_date, gender):
        self.users[user_id] = User(user_id, user_name, birth_date, gender, created_at=now_kst())
        return self.users[user_id]

    def update_user(self, user_id, changes):
        if user_id not in self.users:
            return None
        self.users[user_id] = replace(self.users[user_id], **changes)
        return self.users[user_id]

    def set_onboarding_done(self, user_id):
        self.users[user_id] = replace(self.users[user_id], onboarding_done=True)

    def get_setting(self, user_id):
        return self.settings.get(user_id)

    def save_setting(self, user_id, values):
        values = {k: v for k, v in values.items() if k in _SETTING_FIELDS}
        current = self.settings.get(user_id)
        setting = replace(current, **values) if current else UserSetting(user_id=user_id, **values)
        self.settings[user_id] = replace(setting, updated_at=now_kst())
        return self.settings[user_id]

    def delete_account(self, user_id):
        self.users.pop(user_id, None)
        self.settings.pop(user_id, None)


class MemoryPresetRepository:
    def __init__(self, templates: list[RoutineTemplate] = (), items: list[TemplateItem] = (), config: AppConfig | None = None):
        self.templates = {t.template_id: t for t in templates}
        self.items = {i.template_item_id: i for i in items}
        self.config = config or AppConfig()

    def list_templates(self, active_only=True):
        return [t for t in self.templates.values() if t.is_active or not active_only]

    def get_template(self, template_id):
        return self.templates.get(template_id)

    def list_template_items(self, template_id):
        return sorted((i for i in self.items.values() if i.template_id == template_id), key=lambda i: i.item_order)

    def get_app_config(self):
        return self.config

    def pick_music(self, music_type, genre=None, mood=None):
        return None


class MemoryAlarmRepository:
    def __init__(self, presets: MemoryPresetRepository):
        self.alarms: dict[str, Alarm] = {}
        self.overrides: dict[str, dict[str, int]] = {}    # alarm_id → {template_item_id: duration_sec} (rs_alarm_routine_item)
        self._presets = presets

    def _own(self, user_id, alarm_id):
        a = self.alarms.get(alarm_id)
        return a if a and a.user_id == user_id else None

    def list_alarms(self, user_id):
        return sorted((a for a in self.alarms.values() if a.user_id == user_id), key=lambda a: (a.alarm_time, a.alarm_id))

    def get_alarm(self, user_id, alarm_id):
        return self._own(user_id, alarm_id)

    def create_alarm(self, user_id, values):
        now = now_kst()
        alarm = Alarm(alarm_id=str(uuid.uuid4()), user_id=user_id, created_at=now, updated_at=now, **values)
        self.alarms[alarm.alarm_id] = alarm
        return alarm

    def update_alarm(self, user_id, alarm_id, changes):
        current = self._own(user_id, alarm_id)
        if not current:
            return None
        alarm = replace(current, updated_at=now_kst(), **changes)
        if alarm.template_id != current.template_id:          # DB 트리거 rs_alarm_template_changed
            self.overrides.pop(alarm_id, None)
        self.alarms[alarm_id] = alarm
        return alarm

    def delete_alarm(self, user_id, alarm_id):
        if not self._own(user_id, alarm_id):
            return False
        del self.alarms[alarm_id]
        self.overrides.pop(alarm_id, None)                    # ON DELETE CASCADE
        return True

    def get_alarm_routine(self, user_id, alarm_id):
        alarm = self._own(user_id, alarm_id)
        if not alarm or alarm.morning_call_type != "ROUTINE":
            return []
        return build_routine(self._presets.list_template_items(alarm.template_id), self.overrides.get(alarm_id, {}))

    def set_alarm_routine(self, user_id, alarm_id, overrides):
        if not self._own(user_id, alarm_id):
            return []
        if overrides:
            self.overrides[alarm_id] = dict(overrides)
        else:
            self.overrides.pop(alarm_id, None)
        return self.get_alarm_routine(user_id, alarm_id)


def memory_repositories(seed: bool = True, config: AppConfig | None = None):
    from .registry import Repositories
    templates, items = seed_templates() if seed else ([], [])
    presets = MemoryPresetRepository(templates, items, config)
    return Repositories(users=MemoryUserRepository(), presets=presets, alarms=MemoryAlarmRepository(presets))
