"""Supabase 저장소 통합 테스트 — 실제 DB에 쓰고 마지막에 지운다.

실행: backend/.env에 SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY를 넣고
      RUN_SUPABASE_TESTS=1 .venv/Scripts/python -m pytest tests/test_supabase_repos.py
테스트용 Auth 계정을 만들어 쓰고, 끝나면 Storage 파일과 계정(→ DB 행 CASCADE)을 지운다.
"""
import json
import os
import uuid
from datetime import date, time, timedelta

import pytest

from backend.core.config import get_settings
from backend.core.errors import ValidationFailed
from backend.core.timeutil import now_kst, record_date
from backend.domain.models import SessionSnapshot, StepPlan

pytestmark = pytest.mark.skipif(os.getenv("RUN_SUPABASE_TESTS") != "1", reason="RUN_SUPABASE_TESTS=1일 때만 실제 DB 사용")

N = "00000000-0000-4000-8000-0000000000a2"   # 프리셋 N (6항목, 합계 600)
M = "00000000-0000-4000-8000-0000000000a1"


@pytest.fixture(scope="module")
def repos():
    from backend.repositories.supabase import build_repositories
    get_settings.cache_clear()
    return build_repositories()


def _make_auth_user(repos, tag):
    db = repos.users.db
    res = db.auth.admin.create_user({"email": f"respec.{tag}.{uuid.uuid4().hex[:8]}@example.com",
                                     "password": uuid.uuid4().hex, "email_confirm": True})
    return res.user.id


@pytest.fixture(scope="module")
def uid(repos):
    user_id = _make_auth_user(repos, "repo-test")
    repos.users.create_user(user_id, "테스트", date(2003, 1, 1), "FEMALE")
    repos.users.save_setting(user_id, {"user_call_name": "찬미", "music_genre": "POP"})
    yield user_id
    repos.storage.delete_user_files(user_id)
    repos.users.delete_account(user_id)


@pytest.fixture(scope="module")
def other(repos):
    user_id = _make_auth_user(repos, "repo-other")
    repos.users.create_user(user_id, "다른사람", date(2000, 1, 1), "NONE")
    yield user_id
    repos.users.delete_account(user_id)


@pytest.fixture()
def alarm(repos, uid):
    a = repos.alarms.create_alarm(uid, {"alarm_time": time(7, 0), "repeat_days": "1111111",
                                        "morning_call_type": "ROUTINE", "template_id": N})
    yield a
    repos.alarms.delete_alarm(uid, a.alarm_id)


def test_user_and_setting(repos, uid, other):
    assert repos.users.get_user(uid).user_name == "테스트"
    assert repos.users.update_user(uid, {"user_name": "바뀐이름"}).user_name == "바뀐이름"
    repos.users.set_onboarding_done(uid)
    assert repos.users.get_user(uid).onboarding_done
    s = repos.users.save_setting(uid, {"tone": "CASUAL"})
    assert (s.tone, s.user_call_name) == ("CASUAL", "찬미")
    assert repos.users.get_setting(other) is None
    assert repos.users.get_user("not-a-uuid") is None


def test_presets(repos):
    assert len(repos.presets.list_templates()) == 5
    items = repos.presets.list_template_items(N)
    assert [i.item_order for i in items] == [1, 2, 3, 4, 5, 6]
    assert sum(i.default_duration_sec for i in items) == 600
    assert repos.presets.get_template(N).template_name
    assert repos.presets.get_app_config().routine_max_sec == 900


def test_alarm_routine(repos, uid, other, alarm):
    items = repos.alarms.get_alarm_routine(uid, alarm.alarm_id)
    assert sum(i.duration_sec for i in items) == 600
    stretch, bed = items[0].template_item_id, items[3].template_item_id

    items = repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {stretch: 300, bed: 0})
    assert sum(i.duration_sec for i in items) == 660
    assert [i.customized for i in items].count(True) == 2

    items = repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {stretch: 120})   # 이불 정리는 기본으로
    assert sum(i.duration_sec for i in items) == 540

    with pytest.raises(ValidationFailed) as e:
        repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {i.template_item_id: 180 for i in items})
    assert e.value.code == "ROUTINE_OVER_LIMIT"
    with pytest.raises(ValidationFailed) as e:
        repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {i.template_item_id: 0 for i in items})
    assert e.value.code == "ROUTINE_ALL_REMOVED"
    assert sum(i.duration_sec for i in repos.alarms.get_alarm_routine(uid, alarm.alarm_id)) == 540   # 실패하면 그대로

    assert repos.alarms.get_alarm(other, alarm.alarm_id) is None
    assert repos.alarms.get_alarm_routine(other, alarm.alarm_id) is None
    assert repos.alarms.replace_alarm_routine(other, alarm.alarm_id, {stretch: 60}) is None
    assert repos.alarms.replace_alarm_routine(uid, "not-a-uuid", {}) is None
    repos.alarms.delete_alarm(other, alarm.alarm_id)
    assert repos.alarms.get_alarm(uid, alarm.alarm_id)                               # 남이 지우려 해도 그대로

    changed = repos.alarms.update_alarm(uid, alarm.alarm_id, {"template_id": M})
    assert changed.template_id == M
    assert not any(i.customized for i in repos.alarms.get_alarm_routine(uid, alarm.alarm_id))

    to_content = repos.alarms.update_alarm(uid, alarm.alarm_id, {"morning_call_type": "CONTENT", "template_id": None,
                                                                 "content_type": "QUIZ"})
    assert to_content.morning_call_type == "CONTENT"
    assert repos.alarms.get_alarm_routine(uid, alarm.alarm_id) == []


def test_night(repos, uid, other, alarm):
    transcript = [{"role": "assistant", "text": "오늘 어땠어요?", "at": "2026-11-01T23:00:00+09:00"},
                  {"role": "user", "text": "바빴어요.", "at": "2026-11-01T23:00:05+09:00"}]
    nid = repos.nights.save_night_raw(uid, transcript, now_kst() - timedelta(minutes=3), now_kst())
    assert repos.nights.save_night_raw(uid, transcript, None, None) == nid          # 같은 밤은 덮어쓴다
    rec = repos.nights.get_night(uid, nid)
    assert rec.extract_status == "PENDING" and rec.record_date == record_date(now_kst())
    assert repos.nights.get_night(other, nid) is None
    assert repos.nights.get_night_raw(nid).transcript[1]["text"] == "바빴어요."

    with pytest.raises(ValidationFailed):
        repos.nights.save_night_extract(uid, nid, {"override_skip_coaching": True}, None)
    rec = repos.nights.save_night_extract(uid, nid, {"tomorrow_goal": "발표", "tomorrow_notes": ["노트북"],
                                                     "condition_label": "TIRED", "override_wake_time": time(6, 30),
                                                     "unknown_key": 1}, alarm.alarm_id)
    assert (rec.extract_status, rec.tomorrow_notes, rec.override_wake_time) == ("DONE", ("노트북",), time(6, 30))
    assert repos.nights.get_night_for_morning(uid, rec.apply_date).night_record_id == nid
    assert repos.nights.save_night_extract(other, nid, {"tomorrow_goal": "x"}, None) is None
    assert repos.nights.set_voice_path(uid, nid) == f"{uid}/{rec.record_date.isoformat()}.m4a"

    db = repos.users.db                                                              # 30일 정리: 만료일을 과거로
    db.table("rs_night_raw").update({"expires_at": "2026-01-01T00:00:00+09:00", "audio_path": f"{uid}/x.m4a"}) \
      .eq("night_record_id", nid).execute()
    assert f"{uid}/x.m4a" in repos.nights.purge_expired_raw(now_kst())
    raw = repos.nights.get_night_raw(nid)
    assert raw.transcript is None and raw.audio_path is None
    assert repos.nights.get_night(uid, nid).tomorrow_goal == "발표"                 # 요약은 남는다


def test_session_and_steps(repos, uid, other, alarm, monkeypatch):
    today = now_kst().date()
    snap = SessionSnapshot(uid, alarm.alarm_id, today, "ROUTINE", N, None, None, time(7, 0), time(7, 0))
    s = repos.sessions.get_or_create_session(snap)
    assert repos.sessions.get_or_create_session(snap).session_id == s.session_id
    assert repos.sessions.get_session(other, s.session_id) is None

    items = repos.alarms.get_alarm_routine(uid, alarm.alarm_id)
    plans = [StepPlan(n, i.item_name, i.template_item_id, i.duration_sec) for n, i in enumerate(items[:2], 1)]
    steps = repos.sessions.create_steps(uid, s.session_id, plans)
    assert [p.step_order for p in steps] == [1, 2]
    assert len(repos.sessions.create_steps(uid, s.session_id, plans)) == 2          # 이미 있으면 그대로
    assert repos.sessions.create_steps(other, s.session_id, plans) == []

    from dataclasses import replace
    done = repos.sessions.save_step(uid, replace(steps[0], step_status="DONE", checked_at=now_kst(), ai_verdict="done"))
    assert done.step_status == "DONE" and done.checked_at
    assert repos.sessions.save_step(other, steps[1]) is None

    assert repos.sessions.add_rewake(uid, s.session_id) == 1
    assert repos.sessions.add_rewake(uid, s.session_id) == 2
    s2 = repos.sessions.update_session(uid, s.session_id, {"planned_total_sec": 300, "wake_success": True})
    assert (s2.planned_total_sec, s2.wake_success) == (300, True)

    rec = repos.sessions.recent_records(uid, today - timedelta(days=7), today + timedelta(days=1))
    assert len(rec.sessions) == 1 and len(rec.steps) == 2
    assert [p.step_order for p in rec.steps] == [1, 2]

    import backend.repositories.supabase as sb                                      # 한 번에 1행씩 받아도 끝까지 받는다
    monkeypatch.setattr(sb, "PAGE", 1)
    paged = repos.sessions.recent_records(uid, today - timedelta(days=7), today + timedelta(days=1))
    assert [p.step_id for p in paged.steps] == [p.step_id for p in rec.steps]
    assert repos.sessions.recent_records(uid, today + timedelta(days=1), today + timedelta(days=2)).steps == []


def test_content_step_detail(repos, uid):
    a = repos.alarms.create_alarm(uid, {"alarm_time": time(9, 0), "morning_call_type": "CONTENT",
                                        "content_type": "QUIZ", "content_option": "HISTORY"})
    snap = SessionSnapshot(uid, a.alarm_id, now_kst().date(), "CONTENT", None, "QUIZ", "HISTORY", time(9, 0), time(9, 0))
    s = repos.sessions.get_or_create_session(snap)
    steps = repos.sessions.create_steps(uid, s.session_id, [StepPlan(1, "퀴즈 1번", None, 60, step_detail={"q": "1+1?", "a": "2"})])
    assert steps[0].step_detail == {"q": "1+1?", "a": "2"}
    repos.alarms.delete_alarm(uid, a.alarm_id)


def test_personalization(repos, uid):
    row = {"session_date": now_kst().date().isoformat(), "source": "rule", "mode": "rule", "strategy_id": "gentle",
           "strategy": json.dumps({"coaching": "soft"}), "rule_ids": json.dumps(["R1"]), "propensity": 1.0,
           "prompt_version": "test"}
    log_id = repos.personalization.save_strategy_log(uid, None, row)
    log = repos.personalization.get_strategy_log(uid, now_kst().date())
    assert log.log_id == log_id and log.strategy == {"coaching": "soft"} and log.rule_ids == ["R1"]
    repos.personalization.set_reward(log_id, 0.75)
    assert repos.personalization.get_strategy_log(uid, now_kst().date()).reward == 0.75

    key = f"user:{uid}"
    repos.personalization.save_bandit_state(key, {"counts": {"a": 1}}, uid)
    repos.personalization.save_bandit_state(key, {"counts": {"a": 2}}, uid)
    assert repos.personalization.get_bandit_state(key) == {"counts": {"a": 2}}


def test_storage(repos, uid):
    d = date(2026, 11, 1)
    photo = repos.storage.upload_photo(uid, d, b"\xff\xd8\xff\xd9")
    assert photo.photo_path == f"{uid}/2026-11-01.jpg"
    assert repos.storage.upload_photo(uid, d, b"\xff\xd8\xff\xd9").diary_photo_id == photo.diary_photo_id   # 덮어쓰기
    assert [p.diary_date for p in repos.storage.list_photos(uid, date(2026, 11, 1), date(2026, 11, 30))] == [d]
    assert repos.storage.signed_url("photo", photo.photo_path, 60).startswith("http")
    assert repos.storage.signed_url("photo", f"{uid}/1999-01-01.jpg", 60) is None              # 없는 파일

    with pytest.raises(ValidationFailed):                                                      # 버킷 제한: 10MB 초과
        repos.storage.upload_voice(uid, d, b"\x00" * (10 * 1024 * 1024 + 1))
    with pytest.raises(ValidationFailed):                                                      # 버킷 제한: jpeg가 아닌 형식
        repos.storage._upload("photo", f"{uid}/2026-11-02.jpg", b"\x89PNG", "image/png")
    path = repos.storage.upload_voice(uid, d, b"\x00\x00")
    assert repos.storage.delete_files("voice", [path]) == 1
    repos.storage.upload_voice(uid, d, b"\x00\x00")
    assert repos.storage.delete_user_files(uid) == 2


def test_delete_account_with_data(repos):
    """Auth 계정 삭제는 supabase_auth_admin 권한으로 CASCADE된다. 알람·항목 설정·하루 한정 변경이 남아 있어도 지워져야 한다."""
    user_id = _make_auth_user(repos, "repo-delete")
    repos.users.create_user(user_id, "탈퇴", date(2000, 1, 1), "NONE")
    repos.users.save_setting(user_id, {"user_call_name": "탈퇴", "music_genre": "POP"})
    a = repos.alarms.create_alarm(user_id, {"alarm_time": time(7, 0), "morning_call_type": "ROUTINE", "template_id": N})
    items = repos.alarms.get_alarm_routine(user_id, a.alarm_id)
    repos.alarms.replace_alarm_routine(user_id, a.alarm_id, {items[0].template_item_id: 300, items[3].template_item_id: 0})
    nid = repos.nights.save_night_raw(user_id, [{"role": "user", "text": "잘 자", "at": ""}], None, None)
    repos.nights.save_night_extract(user_id, nid, {"override_wake_time": time(6, 30)}, a.alarm_id)
    s = repos.sessions.get_or_create_session(SessionSnapshot(user_id, a.alarm_id, now_kst().date(), "ROUTINE", N,
                                                             None, None, time(7, 0), time(6, 30)))
    repos.sessions.create_steps(user_id, s.session_id, [StepPlan(1, items[0].item_name, items[0].template_item_id, 300)])

    repos.users.delete_account(user_id)
    db = repos.users.db
    for table in ("rs_user", "rs_alarm", "rs_alarm_routine_item", "rs_night_record", "rs_morning_session", "rs_session_step"):
        assert db.table(table).select("user_id").eq("user_id", user_id).execute().data == [], table
