"""메모리 저장소 — 테스트와 DB 없이 서버를 띄울 때 쓴다. 동작은 base.py의 규칙과 DB 트리거를 따른다."""
import uuid
from dataclasses import asdict, fields, replace
from datetime import date, timedelta

from backend.core.errors import ValidationFailed
from backend.core.timeutil import apply_date, now_kst, record_date
from backend.domain.models import (Alarm, AppConfig, DiaryPhoto, MorningSession, NightRaw, NightRecord, RecentRecords,
                                   RoutineTemplate, SessionStep, StrategyLog, TemplateItem, User, UserSetting)
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
        if self._own(user_id, alarm_id):
            del self.alarms[alarm_id]
            self.overrides.pop(alarm_id, None)                # ON DELETE CASCADE

    def get_alarm_routine(self, user_id, alarm_id):
        alarm = self._own(user_id, alarm_id)
        if not alarm:
            return None
        if alarm.morning_call_type != "ROUTINE":
            return []
        return build_routine(self._presets.list_template_items(alarm.template_id), self.overrides.get(alarm_id, {}))

    def replace_alarm_routine(self, user_id, alarm_id, overrides):
        if not self._own(user_id, alarm_id):
            return None
        defaults = {i.template_item_id: i.default_duration_sec for i in self._presets.list_template_items(self.alarms[alarm_id].template_id)}
        rows = {k: v for k, v in overrides.items() if defaults.get(k) != v}     # 기본 시간과 같은 값은 행 없음
        if rows:
            self.overrides[alarm_id] = rows
        else:
            self.overrides.pop(alarm_id, None)
        return self.get_alarm_routine(user_id, alarm_id)


class MemoryNightRepository:
    """rs_night_record + rs_night_raw. record_date·apply_date는 DB 트리거처럼 저장 시각(clock)으로 계산한다."""

    def __init__(self, clock=now_kst):
        self.records: dict[str, NightRecord] = {}
        self.raws: dict[str, NightRaw] = {}
        self.clock = clock

    def _own(self, user_id, night_record_id):
        r = self.records.get(night_record_id)
        return r if r and r.user_id == user_id else None

    def save_night_raw(self, user_id, transcript, started_at, ended_at):
        now = self.clock()
        rdate = record_date(now)
        existing = next((r for r in self.records.values() if r.user_id == user_id and r.record_date == rdate), None)
        if existing:   # 같은 밤에 다시 저장하면 덮어쓴다
            rec = replace(existing, extract_status="PENDING", updated_at=now)
        else:
            rec = NightRecord(str(uuid.uuid4()), user_id, rdate, apply_date(rdate), created_at=now, updated_at=now)
        self.records[rec.night_record_id] = rec
        self.raws[rec.night_record_id] = NightRaw(rec.night_record_id, list(transcript), None, started_at, ended_at,
                                                  now + timedelta(days=30))
        return rec.night_record_id

    def save_night_extract(self, user_id, night_record_id, fields, override_alarm_id):
        rec = self._own(user_id, night_record_id)
        if (fields.get("override_wake_time") or fields.get("override_skip_coaching")) and not override_alarm_id:
            raise ValidationFailed("하루 한정 변경에는 대상 알람이 필요해요")   # CHECK rs_night_override_alarm
        rec = replace(rec, **{**fields, "tomorrow_notes": tuple(fields.get("tomorrow_notes") or ())},
                      override_alarm_id=override_alarm_id, extract_status="DONE", updated_at=self.clock())
        self.records[night_record_id] = rec
        return rec

    def mark_extract_failed(self, user_id, night_record_id):
        rec = self._own(user_id, night_record_id)
        if rec:
            self.records[night_record_id] = replace(rec, extract_status="FAILED", updated_at=self.clock())

    def get_night(self, user_id, night_record_id):
        return self._own(user_id, night_record_id)

    def get_night_raw(self, night_record_id):
        return self.raws.get(night_record_id)

    def list_failed_extracts(self, limit):
        return [(r.user_id, r.night_record_id) for r in self.records.values() if r.extract_status == "FAILED"][:limit]

    def get_night_for_morning(self, user_id, apply_date):
        return next((r for r in self.records.values() if r.user_id == user_id and r.apply_date == apply_date), None)

    def get_night_by_date(self, user_id, record_date):
        return next((r for r in self.records.values() if r.user_id == user_id and r.record_date == record_date), None)

    def set_voice_path(self, user_id, night_record_id):
        rec = self._own(user_id, night_record_id)
        path = f"{user_id}/{rec.record_date.isoformat()}.m4a"
        self.records[night_record_id] = replace(rec, voice_audio_path=path)
        return path

    def purge_expired_raw(self, now):
        paths = []
        for k, raw in self.raws.items():
            if raw.expires_at and raw.expires_at < now and (raw.transcript is not None or raw.audio_path):
                if raw.audio_path:
                    paths.append(raw.audio_path)
                self.raws[k] = replace(raw, transcript=None, audio_path=None)
        return paths


class MemorySessionRepository:
    def __init__(self, nights: MemoryNightRepository):
        self.sessions: dict[str, MorningSession] = {}
        self.steps: dict[str, SessionStep] = {}
        self._nights = nights

    def _own(self, user_id, session_id):
        s = self.sessions.get(session_id)
        return s if s and s.user_id == user_id else None

    def get_or_create_session(self, snapshot):
        found = next((s for s in self.sessions.values()
                      if s.alarm_id == snapshot.alarm_id and s.session_date == snapshot.session_date), None)   # UNIQUE(alarm_id, session_date)
        if found:
            return found
        s = MorningSession(str(uuid.uuid4()), **asdict(snapshot))
        self.sessions[s.session_id] = s
        return s

    def get_session(self, user_id, session_id):
        return self._own(user_id, session_id)

    def update_session(self, user_id, session_id, changes):
        s = self._own(user_id, session_id)
        if not s:
            return None
        self.sessions[session_id] = replace(s, **changes)
        return self.sessions[session_id]

    def add_rewake(self, user_id, session_id):
        s = self._own(user_id, session_id)
        self.sessions[session_id] = replace(s, rewake_count=s.rewake_count + 1)
        return s.rewake_count + 1

    def create_steps(self, user_id, session_id, plans):
        if not self._own(user_id, session_id):
            return []
        existing = self.list_steps(user_id, session_id)
        if existing:
            return existing
        for p in plans:
            step = SessionStep(str(uuid.uuid4()), user_id, session_id, p.step_order, p.step_name, p.template_item_id,
                               p.planned_duration_sec, p.step_detail)
            self.steps[step.step_id] = step
        return self.list_steps(user_id, session_id)

    def list_steps(self, user_id, session_id):
        return sorted((s for s in self.steps.values() if s.session_id == session_id and s.user_id == user_id),
                      key=lambda s: s.step_order)

    def save_step(self, user_id, step):
        if step.user_id != user_id or step.step_id not in self.steps:
            return None
        self.steps[step.step_id] = step
        return step

    def recent_records(self, user_id, since, until):
        sessions = [s for s in self.sessions.values() if s.user_id == user_id and since <= s.session_date <= until]
        ids = {s.session_id for s in sessions}
        steps = [s for s in self.steps.values() if s.session_id in ids]
        nights = [n for n in self._nights.records.values() if n.user_id == user_id and since <= n.apply_date <= until]
        return RecentRecords(sorted(sessions, key=lambda s: s.session_date), sorted(steps, key=lambda s: (s.session_id, s.step_order)), nights)


class MemoryPersonalizationRepository:
    def __init__(self):
        self.logs: dict[str, StrategyLog] = {}
        self.bandit: dict[str, dict] = {}

    def save_strategy_log(self, user_id, session_id, row):
        keys = {f.name for f in fields(StrategyLog)} - {"log_id", "user_id", "session_id", "session_date", "created_at"}
        log = StrategyLog(str(uuid.uuid4()), user_id, date.fromisoformat(str(row["session_date"])), session_id=session_id,
                          created_at=now_kst(), **{k: v for k, v in row.items() if k in keys})
        self.logs[log.log_id] = log
        return log.log_id

    def get_strategy_log(self, user_id, session_date):
        found = [l for l in self.logs.values() if l.user_id == user_id and l.session_date == session_date]
        return min(found, key=lambda l: l.created_at) if found else None

    def set_reward(self, log_id, reward):
        self.logs[log_id] = replace(self.logs[log_id], reward=reward)

    def get_bandit_state(self, state_key):
        return self.bandit.get(state_key)

    def save_bandit_state(self, state_key, state, user_id=None):
        self.bandit[state_key] = state


class MemoryStorageClient:
    def __init__(self):
        self.files: dict[tuple[str, str], bytes] = {}
        self.photos: dict[tuple[str, object], DiaryPhoto] = {}

    def upload_voice(self, user_id, record_date, data):
        path = f"{user_id}/{record_date.isoformat()}.m4a"
        self.files[("voice", path)] = data
        return path

    def upload_photo(self, user_id, diary_date, data):
        path = f"{user_id}/{diary_date.isoformat()}.jpg"
        self.files[("photo", path)] = data
        old = self.photos.get((user_id, diary_date))
        photo = DiaryPhoto(old.diary_photo_id if old else str(uuid.uuid4()), user_id, diary_date, path, now_kst())
        self.photos[(user_id, diary_date)] = photo
        return photo

    def get_photo(self, user_id, diary_date):
        return self.photos.get((user_id, diary_date))

    def list_photos(self, user_id, since, until):
        return sorted((p for (u, d), p in self.photos.items() if u == user_id and since <= d <= until), key=lambda p: p.diary_date)

    def signed_url(self, bucket, path, expires_in=3600):
        return f"memory://{bucket}/{path}?expires_in={expires_in}"

    def delete_user_files(self, user_id):
        keys = [k for k in self.files if k[1].startswith(f"{user_id}/")]
        for k in keys:
            del self.files[k]
        return len(keys)


def memory_repositories(seed: bool = True, config: AppConfig | None = None, clock=now_kst):
    from .registry import Repositories
    templates, items = seed_templates() if seed else ([], [])
    presets = MemoryPresetRepository(templates, items, config)
    nights = MemoryNightRepository(clock)
    return Repositories(users=MemoryUserRepository(), presets=presets, alarms=MemoryAlarmRepository(presets),
                        nights=nights, sessions=MemorySessionRepository(nights),
                        personalization=MemoryPersonalizationRepository(), storage=MemoryStorageClient())
