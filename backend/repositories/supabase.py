"""Supabase 저장소 — base.py 인터페이스의 실제 DB 구현 (REPOSITORY_BACKEND=supabase).

서버는 service_role 키로 접근하므로 RLS가 적용되지 않는다. 사용자 데이터는 모든 쿼리에 user_id 조건을 넣고,
본인 행이 아니면 없는 것과 같이 None을 돌려준다. DB 오류는 core.errors 형식으로 바꿔 던진다.
"""
import json
import random
import uuid
from contextlib import contextmanager
from dataclasses import fields
from datetime import date, datetime, time, timedelta
from types import UnionType
from typing import Union, get_args, get_origin, get_type_hints

import httpx
from postgrest.exceptions import APIError
from storage3.utils import StorageException
from supabase import Client, create_client

from backend.core.config import get_settings
from backend.core.errors import Conflict, NotFound, ServiceUnavailable, ValidationFailed
from backend.domain.models import (AiMusic, Alarm, AlarmRoutineItem, AppConfig, DiaryPhoto, MorningSession, NightRaw,
                                   NightRecord, RecentRecords, RoutineTemplate, SessionSnapshot, SessionStep, StepPlan,
                                   StrategyLog, TemplateItem, User, UserSetting)

from .registry import Repositories

USER_BUCKETS = ("voice", "night-raw", "photo")
PAGE = 1000   # Supabase API가 한 번에 돌려주는 최대 행 수. 넘으면 나눠 받는다


# ---------- 변환: DB 행(JSON) <-> 모델 ----------
def _is_uuid(value) -> bool:
    try:
        uuid.UUID(str(value))
        return True
    except ValueError:
        return False


def _conv(tp, value):
    if value is None:
        return None
    if get_origin(tp) in (Union, UnionType):
        tp = next(a for a in get_args(tp) if a is not type(None))
    if tp is date:
        return date.fromisoformat(value[:10])
    if tp is datetime:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    if tp is time:
        return time.fromisoformat(value)
    if tp is float:
        return float(value)
    if get_origin(tp) is tuple:
        return tuple(value)
    return value


def _model(cls, row: dict | None):
    if row is None:
        return None
    hints = get_type_hints(cls)
    return cls(**{f.name: _conv(hints[f.name], row[f.name]) for f in fields(cls) if f.init and f.name in row})


def _models(cls, rows: list[dict]) -> list:
    return [_model(cls, r) for r in rows]


def _plain(value):
    """모델 값 → JSON (날짜·시각은 ISO 문자열, 튜플은 목록)."""
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, (date, time, datetime)):
        return value.isoformat()
    return value


def _first(rows: list[dict]) -> dict | None:
    return rows[0] if rows else None


def _columns(cls, exclude=()) -> set[str]:
    return {f.name for f in fields(cls)} - set(exclude)


# ---------- DB 오류 → 서버 오류 ----------
@contextmanager
def _db():
    try:
        yield
    except APIError as e:
        message = e.message or "DB 오류"
        if e.code == "P0001":                         # 트리거 raise exception
            if "상한" in message:
                raise ValidationFailed(message, "ROUTINE_OVER_LIMIT") from None
            if "모두 뺄 수는" in message:
                raise ValidationFailed(message, "ROUTINE_ALL_REMOVED") from None
            raise ValidationFailed(message) from None
        if e.code == "P0002":
            raise NotFound(message) from None
        if e.code == "23505":
            raise Conflict(message) from None
        if e.code in ("23514", "23502", "23503", "22P02", "22007", "22008"):
            raise ValidationFailed(message) from None
        raise
    except StorageException as e:
        message = getattr(e, "message", None) or str(e)
        status = str(getattr(e, "status", ""))
        if status == "404" or getattr(e, "code", "") in ("not_found", "NoSuchKey") or "not found" in message.lower():
            raise NotFound(f"파일을 찾을 수 없어요: {message}") from None
        if status in ("400", "413", "415"):
            raise ValidationFailed(f"파일을 저장할 수 없어요: {message}") from None
        raise ServiceUnavailable(f"Storage 오류: {message}") from None
    except httpx.HTTPError as e:
        raise ServiceUnavailable(f"DB에 연결할 수 없어요: {e}") from None


class _Base:
    def __init__(self, client: Client):
        self.db = client

    def _select(self, table: str, columns: str = "*"):
        return self.db.table(table).select(columns)

    def _run(self, query) -> list[dict]:
        with _db():
            return query.execute().data or []

    def _run_all(self, make_query) -> list[dict]:
        """PAGE 행씩 나눠 끝까지 받는다. make_query는 정렬까지 붙인 새 쿼리를 만든다(정렬이 있어야 페이지가 겹치지 않음)."""
        rows, start = [], 0
        while True:
            page = self._run(make_query().range(start, start + PAGE - 1))
            rows += page
            if len(page) < PAGE:
                return rows
            start += PAGE


# ---------- 1. 사용자·설정 ----------
_USER_FIELDS = {"user_name", "birth_date", "gender"}
_SETTING_FIELDS = _columns(UserSetting, ("user_id", "updated_at"))


class SupabaseUserRepository(_Base):
    def get_user(self, user_id):
        if not _is_uuid(user_id):
            return None
        return _model(User, _first(self._run(self._select("rs_user").eq("user_id", user_id))))

    def create_user(self, user_id, user_name, birth_date, gender):
        row = {"user_id": user_id, "user_name": user_name, "birth_date": birth_date, "gender": gender}
        return _model(User, _first(self._run(self.db.table("rs_user").insert(_plain(row)))))

    def update_user(self, user_id, changes):
        changes = {k: v for k, v in changes.items() if k in _USER_FIELDS}
        if not changes:
            return self.get_user(user_id)
        q = self.db.table("rs_user").update(_plain(changes)).eq("user_id", user_id)
        return _model(User, _first(self._run(q)))

    def set_onboarding_done(self, user_id):
        self._run(self.db.table("rs_user").update({"onboarding_done": True}).eq("user_id", user_id))

    def get_setting(self, user_id):
        if not _is_uuid(user_id):
            return None
        return _model(UserSetting, _first(self._run(self._select("rs_user_setting").eq("user_id", user_id))))

    def save_setting(self, user_id, values):
        """없으면 만들고 있으면 넘긴 칸만 바꾼다. 처음 만들 때는 호칭·음악 장르가 꼭 있어야 한다(NOT NULL)."""
        values = {k: v for k, v in values.items() if k in _SETTING_FIELDS}
        if self.get_setting(user_id):
            q = self.db.table("rs_user_setting").update(_plain(values)).eq("user_id", user_id)
        else:
            q = self.db.table("rs_user_setting").insert(_plain({"user_id": user_id, **values}))
        return _model(UserSetting, _first(self._run(q)))

    def delete_account(self, user_id):
        """auth.users를 지우면 rs_user 아래 행은 ON DELETE CASCADE로 지워진다. Storage 파일은 먼저 지운다."""
        with _db():
            self.db.auth.admin.delete_user(user_id)


# ---------- 2. 공용 데이터 ----------
class SupabasePresetRepository(_Base):
    def list_templates(self, active_only=True):
        q = self._select("rs_routine_template").order("template_id")
        if active_only:
            q = q.eq("is_active", True)
        return _models(RoutineTemplate, self._run(q))

    def get_template(self, template_id):
        if not _is_uuid(template_id):
            return None
        return _model(RoutineTemplate, _first(self._run(self._select("rs_routine_template").eq("template_id", template_id))))

    def list_template_items(self, template_id):
        if not _is_uuid(template_id):
            return []
        q = self._select("rs_routine_template_item").eq("template_id", template_id).order("item_order")
        return _models(TemplateItem, self._run(q))

    def get_app_config(self):
        row = _first(self._run(self._select("rs_app_config", "routine_max_sec, content_duration_sec").limit(1)))
        return _model(AppConfig, row) if row else AppConfig()

    def pick_music(self, music_type, genre=None, mood=None):
        q = self._select("rs_ai_music").eq("music_type", music_type)
        if genre:
            q = q.eq("genre", genre)
        if mood:
            q = q.eq("mood", mood)
        rows = self._run(q)
        return _model(AiMusic, random.choice(rows)) if rows else None


# ---------- 3. 알람 ----------
_ALARM_FIELDS = _columns(Alarm, ("alarm_id", "user_id", "created_at", "updated_at"))


class SupabaseAlarmRepository(_Base):
    def list_alarms(self, user_id):
        q = self._select("rs_alarm").eq("user_id", user_id).order("alarm_time").order("alarm_id")
        return _models(Alarm, self._run(q))

    def get_alarm(self, user_id, alarm_id):
        if not _is_uuid(alarm_id):
            return None
        q = self._select("rs_alarm").eq("alarm_id", alarm_id).eq("user_id", user_id)
        return _model(Alarm, _first(self._run(q)))

    def create_alarm(self, user_id, values):
        row = {k: v for k, v in values.items() if k in _ALARM_FIELDS}
        return _model(Alarm, _first(self._run(self.db.table("rs_alarm").insert(_plain({"user_id": user_id, **row})))))

    def update_alarm(self, user_id, alarm_id, changes):
        changes = {k: v for k, v in changes.items() if k in _ALARM_FIELDS}
        if not changes or not _is_uuid(alarm_id):
            return self.get_alarm(user_id, alarm_id)
        q = self.db.table("rs_alarm").update(_plain(changes)).eq("alarm_id", alarm_id).eq("user_id", user_id)
        return _model(Alarm, _first(self._run(q)))

    def delete_alarm(self, user_id, alarm_id):
        if _is_uuid(alarm_id):
            self._run(self.db.table("rs_alarm").delete().eq("alarm_id", alarm_id).eq("user_id", user_id))

    def get_alarm_routine(self, user_id, alarm_id):
        alarm = self.get_alarm(user_id, alarm_id)    # DB 함수는 사용자를 확인하지 않으므로 먼저 본인 알람인지 본다
        if not alarm:
            return None
        if alarm.morning_call_type != "ROUTINE":
            return []
        return _models(AlarmRoutineItem, self._run(self.db.rpc("rs_alarm_routine_items", {"p_alarm_id": alarm_id})))

    def replace_alarm_routine(self, user_id, alarm_id, overrides):
        """DB 함수 하나로 교체한다(삭제와 저장이 한 트랜잭션, 합계 검사는 커밋 때)."""
        if not _is_uuid(alarm_id):
            return None
        items = [{"template_item_id": k, "duration_sec": v} for k, v in overrides.items()]
        params = {"p_user_id": user_id, "p_alarm_id": alarm_id, "p_items": items}
        try:
            rows = self._run(self.db.rpc("rs_replace_alarm_routine_items", params))
        except NotFound:                                  # '알람이 없습니다' = 없거나 남의 알람
            return None
        return _models(AlarmRoutineItem, rows)


# ---------- 4. 밤 기록 ----------
_NIGHT_FIELDS = _columns(NightRecord, ("night_record_id", "user_id", "record_date", "apply_date", "voice_audio_path",
                                       "override_alarm_id", "extract_status", "created_at", "updated_at"))


class SupabaseNightRepository(_Base):
    def _own(self, user_id, night_record_id):
        if not _is_uuid(night_record_id):
            return None
        q = self._select("rs_night_record").eq("night_record_id", night_record_id).eq("user_id", user_id)
        return _model(NightRecord, _first(self._run(q)))

    def save_night_raw(self, user_id, transcript, started_at, ended_at):
        """밤 기록 행은 (user_id, record_date)로 덮어쓴다. record_date는 트리거가 넣은 값으로 충돌을 판단한다."""
        q = self.db.table("rs_night_record").upsert({"user_id": user_id, "extract_status": "PENDING"},
                                                    on_conflict="user_id,record_date")
        night_record_id = _first(self._run(q))["night_record_id"]
        raw = {"night_record_id": night_record_id, "transcript": list(transcript),
               "started_at": started_at, "ended_at": ended_at,
               "expires_at": datetime.now().astimezone() + timedelta(days=30)}
        self._run(self.db.table("rs_night_raw").upsert(_plain(raw), on_conflict="night_record_id"))
        return night_record_id

    def save_night_extract(self, user_id, night_record_id, fields, override_alarm_id):
        values = {k: v for k, v in fields.items() if k in _NIGHT_FIELDS}
        values["tomorrow_notes"] = list(values.get("tomorrow_notes") or [])
        values.update(override_alarm_id=override_alarm_id, extract_status="DONE")
        q = (self.db.table("rs_night_record").update(_plain(values))
             .eq("night_record_id", night_record_id).eq("user_id", user_id))
        return _model(NightRecord, _first(self._run(q)))

    def mark_extract_failed(self, user_id, night_record_id):
        if _is_uuid(night_record_id):
            self._run(self.db.table("rs_night_record").update({"extract_status": "FAILED"})
                      .eq("night_record_id", night_record_id).eq("user_id", user_id))

    def get_night(self, user_id, night_record_id):
        return self._own(user_id, night_record_id)

    def get_night_raw(self, night_record_id):
        if not _is_uuid(night_record_id):
            return None
        return _model(NightRaw, _first(self._run(self._select("rs_night_raw").eq("night_record_id", night_record_id))))

    def list_failed_extracts(self, limit):
        q = self._select("rs_night_record", "user_id, night_record_id").eq("extract_status", "FAILED").order("updated_at").limit(limit)
        return [(r["user_id"], r["night_record_id"]) for r in self._run(q)]

    def get_night_for_morning(self, user_id, apply_date):
        q = self._select("rs_night_record").eq("user_id", user_id).eq("apply_date", apply_date.isoformat())
        return _model(NightRecord, _first(self._run(q)))

    def get_night_by_date(self, user_id, record_date):
        q = self._select("rs_night_record").eq("user_id", user_id).eq("record_date", record_date.isoformat())
        return _model(NightRecord, _first(self._run(q)))

    def set_voice_path(self, user_id, night_record_id):
        rec = self._own(user_id, night_record_id)
        if not rec:
            raise NotFound("밤 기록을 찾을 수 없어요")
        path = f"{user_id}/{rec.record_date.isoformat()}.m4a"
        self._run(self.db.table("rs_night_record").update({"voice_audio_path": path})
                  .eq("night_record_id", night_record_id).eq("user_id", user_id))
        return path

    def purge_expired_raw(self, now):
        q = (self._select("rs_night_raw", "night_record_id, audio_path").lt("expires_at", _plain(now))
             .or_("transcript.not.is.null,audio_path.not.is.null"))
        rows = self._run(q)
        if not rows:
            return []
        ids = [r["night_record_id"] for r in rows]
        self._run(self.db.table("rs_night_raw").update({"transcript": None, "audio_path": None}).in_("night_record_id", ids))
        return [r["audio_path"] for r in rows if r["audio_path"]]


# ---------- 5. 모닝 세션·단계 ----------
_SESSION_CHANGES = {"alarm_rang_at", "first_response_at", "planned_total_sec", "wake_success", "session_end_at"}


class SupabaseSessionRepository(_Base):
    def _find(self, snapshot):
        q = (self._select("rs_morning_session").eq("user_id", snapshot.user_id).eq("alarm_id", snapshot.alarm_id)
             .eq("session_date", snapshot.session_date.isoformat()))
        return _model(MorningSession, _first(self._run(q)))

    def get_or_create_session(self, snapshot: SessionSnapshot):
        existing = self._find(snapshot)
        if existing:
            return existing
        row = {f.name: getattr(snapshot, f.name) for f in fields(SessionSnapshot)}
        try:
            return _model(MorningSession, _first(self._run(self.db.table("rs_morning_session").insert(_plain(row)))))
        except Conflict:                                  # 같은 알람·날짜를 동시에 만든 경우
            return self._find(snapshot)

    def get_session(self, user_id, session_id):
        if not _is_uuid(session_id):
            return None
        q = self._select("rs_morning_session").eq("session_id", session_id).eq("user_id", user_id)
        return _model(MorningSession, _first(self._run(q)))

    def update_session(self, user_id, session_id, changes):
        changes = {k: v for k, v in changes.items() if k in _SESSION_CHANGES}
        if not changes or not _is_uuid(session_id):
            return self.get_session(user_id, session_id)
        q = self.db.table("rs_morning_session").update(_plain(changes)).eq("session_id", session_id).eq("user_id", user_id)
        return _model(MorningSession, _first(self._run(q)))

    def add_rewake(self, user_id, session_id):
        """읽은 횟수와 같을 때만 +1 하는 조건부 수정. 동시에 두 번 오면 늦은 쪽이 다시 읽어서 더하므로 빠지는 횟수가 없다."""
        for _ in range(5):
            s = self.get_session(user_id, session_id)
            if not s:
                raise NotFound("세션을 찾을 수 없어요")
            q = (self.db.table("rs_morning_session").update({"rewake_count": s.rewake_count + 1})
                 .eq("session_id", session_id).eq("user_id", user_id).eq("rewake_count", s.rewake_count))
            if self._run(q):
                return s.rewake_count + 1
        raise Conflict("재기상 횟수를 저장하지 못했어요")

    def create_steps(self, user_id, session_id, plans: list[StepPlan]):
        if not self.get_session(user_id, session_id):
            return []
        existing = self.list_steps(user_id, session_id)
        if existing or not plans:
            return existing
        rows = [{"user_id": user_id, "session_id": session_id, "step_order": p.step_order, "step_name": p.step_name,
                 "template_item_id": p.template_item_id, "planned_duration_sec": p.planned_duration_sec,
                 "step_detail": p.step_detail} for p in plans]
        try:
            self._run(self.db.table("rs_session_step").insert(rows))
        except Conflict:                                  # 재연결로 동시에 만든 경우 기존 단계를 쓴다
            pass
        return self.list_steps(user_id, session_id)

    def list_steps(self, user_id, session_id):
        if not _is_uuid(session_id):
            return []
        q = self._select("rs_session_step").eq("session_id", session_id).eq("user_id", user_id).order("step_order")
        return _models(SessionStep, self._run(q))

    def save_step(self, user_id, step: SessionStep):
        values = {"step_status": step.step_status, "checked_at": step.checked_at, "ai_verdict": step.ai_verdict}
        q = self.db.table("rs_session_step").update(_plain(values)).eq("step_id", step.step_id).eq("user_id", user_id)
        return _model(SessionStep, _first(self._run(q)))

    def recent_records(self, user_id, since, until):
        """세션은 session_date, 단계는 세션 날짜로 조인해서, 밤 기록은 apply_date로 거른다(양 끝 포함). 길면 나눠 받는다."""
        lo, hi = since.isoformat(), until.isoformat()
        sessions = _models(MorningSession, self._run_all(lambda: (
            self._select("rs_morning_session").eq("user_id", user_id)
            .gte("session_date", lo).lte("session_date", hi).order("session_date").order("session_id"))))
        steps = _models(SessionStep, self._run_all(lambda: (
            self._select("rs_session_step", "*, rs_morning_session!inner(session_date)").eq("user_id", user_id)
            .gte("rs_morning_session.session_date", lo).lte("rs_morning_session.session_date", hi)
            .order("session_id").order("step_order")))) if sessions else []
        nights = _models(NightRecord, self._run_all(lambda: (
            self._select("rs_night_record").eq("user_id", user_id)
            .gte("apply_date", lo).lte("apply_date", hi).order("apply_date"))))
        return RecentRecords(sessions, steps, nights)


# ---------- 6. 개인화 (서버만) ----------
_LOG_FIELDS = _columns(StrategyLog, ("log_id", "user_id", "session_id", "reward", "created_at"))
_LOG_JSON = {"strategy", "rule_ids", "allowed_arms", "stats"}


class SupabasePersonalizationRepository(_Base):
    def save_strategy_log(self, user_id, session_id, row):
        """DecisionLog.to_row()는 중첩 값을 JSON 문자열로 준다. jsonb 칸에 맞게 다시 풀어 저장한다."""
        values = {k: v for k, v in row.items() if k in _LOG_FIELDS}
        for k in _LOG_JSON & values.keys():
            if isinstance(values[k], str):
                values[k] = json.loads(values[k])
        values.update(user_id=user_id, session_id=session_id)
        return _first(self._run(self.db.table("rs_strategy_log").insert(_plain(values))))["log_id"]

    def get_strategy_log(self, user_id, session_date):
        q = (self._select("rs_strategy_log").eq("user_id", user_id).eq("session_date", session_date.isoformat())
             .order("created_at").limit(1))
        return _model(StrategyLog, _first(self._run(q)))

    def set_reward(self, log_id, reward):
        if _is_uuid(log_id):
            self._run(self.db.table("rs_strategy_log").update({"reward": reward}).eq("log_id", log_id))

    def get_bandit_state(self, state_key):
        row = _first(self._run(self._select("rs_bandit_state", "state").eq("state_key", state_key)))
        return row["state"] if row else None

    def save_bandit_state(self, state_key, state, user_id=None):
        row = {"state_key": state_key, "user_id": user_id, "state": state}
        self._run(self.db.table("rs_bandit_state").upsert(row, on_conflict="state_key"))


# ---------- 7. Storage ----------
class SupabaseStorageClient(_Base):
    def _upload(self, bucket, path, data, content_type):
        with _db():
            self.db.storage.from_(bucket).upload(path, data, {"content-type": content_type, "upsert": "true"})

    def upload_voice(self, user_id, record_date, data):
        path = f"{user_id}/{record_date.isoformat()}.m4a"
        self._upload("voice", path, data, "audio/mp4")
        return path

    def upload_photo(self, user_id, diary_date, data):
        path = f"{user_id}/{diary_date.isoformat()}.jpg"
        self._upload("photo", path, data, "image/jpeg")
        row = {"user_id": user_id, "diary_date": diary_date.isoformat(), "photo_path": path}
        q = self.db.table("rs_diary_photo").upsert(row, on_conflict="user_id,diary_date")
        return _model(DiaryPhoto, _first(self._run(q)))

    def get_photo(self, user_id, diary_date):
        q = self._select("rs_diary_photo").eq("user_id", user_id).eq("diary_date", diary_date.isoformat())
        return _model(DiaryPhoto, _first(self._run(q)))

    def list_photos(self, user_id, since, until):
        q = (self._select("rs_diary_photo").eq("user_id", user_id)
             .gte("diary_date", since.isoformat()).lte("diary_date", until.isoformat()).order("diary_date"))
        return _models(DiaryPhoto, self._run(q))

    def signed_url(self, bucket, path, expires_in=3600):
        """파일이 없으면 None (서버는 URL 없이 정상 응답)."""
        try:
            with _db():
                res = self.db.storage.from_(bucket).create_signed_url(path, expires_in)
        except NotFound:
            return None
        return res.get("signedUrl") or res.get("signedURL")

    def delete_files(self, bucket, paths):
        """보관 기간이 지난 파일 등 경로를 아는 파일 지우기. 지운 개수."""
        if not paths:
            return 0
        with _db():
            return len(self.db.storage.from_(bucket).remove(list(paths)))

    def delete_user_files(self, user_id):
        count = 0
        with _db():
            for bucket in USER_BUCKETS:
                store = self.db.storage.from_(bucket)
                names = [f["name"] for f in store.list(user_id, {"limit": 1000})]
                if names:
                    store.remove([f"{user_id}/{n}" for n in names])
                    count += len(names)
        return count


def build_repositories() -> Repositories:
    s = get_settings()
    if not s.supabase_url or not s.supabase_service_role_key:
        raise ValueError("REPOSITORY_BACKEND=supabase에는 SUPABASE_URL과 SUPABASE_SERVICE_ROLE_KEY가 필요해요")
    client = create_client(s.supabase_url, s.supabase_service_role_key)
    return Repositories(users=SupabaseUserRepository(client), presets=SupabasePresetRepository(client),
                        alarms=SupabaseAlarmRepository(client), nights=SupabaseNightRepository(client),
                        sessions=SupabaseSessionRepository(client),
                        personalization=SupabasePersonalizationRepository(client),
                        storage=SupabaseStorageClient(client))
