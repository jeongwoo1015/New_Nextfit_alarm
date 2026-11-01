"""메모리 저장소 — 테스트와 DB 없이 서버를 띄울 때 쓴다. 동작은 base.py의 규칙과 DB 트리거를 따른다."""
import uuid
from dataclasses import replace

from backend.core.timeutil import now_kst
from backend.domain.models import Alarm, AppConfig, RoutineTemplate, TemplateItem, User, UserSetting

from .seed import seed_templates


class MemoryUserRepository:
    def __init__(self):
        self.rows: dict[str, User] = {}

    def get(self, user_id):
        return self.rows.get(user_id)

    def create(self, user):
        user = replace(user, created_at=user.created_at or now_kst())
        self.rows[user.user_id] = user
        return user

    def update(self, user):
        self.rows[user.user_id] = user
        return user


class MemoryUserSettingRepository:
    def __init__(self):
        self.rows: dict[str, UserSetting] = {}

    def get(self, user_id):
        return self.rows.get(user_id)

    def upsert(self, setting):
        setting = replace(setting, updated_at=now_kst())
        self.rows[setting.user_id] = setting
        return setting


class MemoryAppConfigRepository:
    def __init__(self, config: AppConfig | None = None):
        self.config = config or AppConfig()

    def get(self):
        return self.config


class MemoryAlarmRoutineItemRepository:
    def __init__(self):
        self.rows: dict[str, dict[str, int]] = {}    # alarm_id → {template_item_id: duration_sec}

    def get_overrides(self, alarm_id):
        return dict(self.rows.get(alarm_id, {}))

    def replace(self, alarm_id, user_id, overrides):
        if overrides:
            self.rows[alarm_id] = dict(overrides)
        else:
            self.rows.pop(alarm_id, None)


class MemoryAlarmRepository:
    def __init__(self, routine_items: MemoryAlarmRoutineItemRepository):
        self.rows: dict[str, Alarm] = {}
        self._routine_items = routine_items

    def list_by_user(self, user_id):
        return sorted((a for a in self.rows.values() if a.user_id == user_id), key=lambda a: (a.alarm_time, a.alarm_id))

    def get(self, alarm_id):
        return self.rows.get(alarm_id)

    def create(self, alarm):
        now = now_kst()
        alarm = replace(alarm, alarm_id=str(uuid.uuid4()), created_at=now, updated_at=now)
        self.rows[alarm.alarm_id] = alarm
        return alarm

    def update(self, alarm):
        if self.rows[alarm.alarm_id].template_id != alarm.template_id:     # DB 트리거 rs_alarm_template_changed
            self._routine_items.rows.pop(alarm.alarm_id, None)
        alarm = replace(alarm, updated_at=now_kst())
        self.rows[alarm.alarm_id] = alarm
        return alarm

    def delete(self, alarm_id):
        self.rows.pop(alarm_id, None)
        self._routine_items.rows.pop(alarm_id, None)                     # ON DELETE CASCADE


class MemoryRoutineTemplateRepository:
    def __init__(self, templates: list[RoutineTemplate] = (), items: list[TemplateItem] = ()):
        self.templates = {t.template_id: t for t in templates}
        self.items = {i.template_item_id: i for i in items}

    def list_templates(self, active_only=True):
        return [t for t in self.templates.values() if t.is_active or not active_only]

    def get_template(self, template_id):
        return self.templates.get(template_id)

    def list_items(self, template_id):
        return sorted((i for i in self.items.values() if i.template_id == template_id), key=lambda i: i.item_order)


def memory_repositories(seed: bool = True, config: AppConfig | None = None):
    from .registry import Repositories
    templates, items = seed_templates() if seed else ([], [])
    routine_items = MemoryAlarmRoutineItemRepository()
    return Repositories(
        users=MemoryUserRepository(), settings=MemoryUserSettingRepository(), config=MemoryAppConfigRepository(config),
        alarms=MemoryAlarmRepository(routine_items), templates=MemoryRoutineTemplateRepository(templates, items),
        alarm_routine_items=routine_items,
    )
