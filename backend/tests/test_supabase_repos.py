"""Supabase 저장소 통합 테스트 — 실제 DB에 쓰고 마지막에 지운다.

실행: backend/.env에 SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY를 넣고
      RUN_SUPABASE_TESTS=1 .venv/Scripts/python -m pytest tests/test_supabase_repos.py
테스트용 Auth 계정을 만들어 쓰고, 끝나면 Storage 파일과 계정(→ DB 행 CASCADE)을 지운다.
"""
import json
import os
import uuid
from datetime import date, time, timedelta, timezone

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
    config = repos.presets.db.table("rs_app_config").select("content_duration_sec, stretch_duration_sec").execute().data[0]
    assert config == {"content_duration_sec": 300, "stretch_duration_sec": 180}       # 영어·게임 5분, 스트레칭 3분


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

    db = repos.users.db
    voice_text = lambda: db.table("rs_night_record").select("voice_text").eq("night_record_id", nid).execute().data[0]["voice_text"]
    repos.nights.set_voice_path(uid, nid, " 내일 발표 떨지 말고 잘하자! ")                 # 받아쓴 한마디는 음성과 함께
    assert voice_text() == "내일 발표 떨지 말고 잘하자!"
    repos.nights.save_night_extract(uid, nid, {"voice_text": "x", "tomorrow_goal": "발표"}, alarm.alarm_id)
    assert voice_text() == "내일 발표 떨지 말고 잘하자!"                                 # 다시 추출해도 그대로
    repos.nights.set_voice_path(uid, nid, "")                                        # 다시 녹음했는데 글이 없으면 비운다
    assert voice_text() is None
    with pytest.raises(Exception, match="rs_night_voice_text"):                      # 음성 없이 글만은 안 됨
        db.table("rs_night_record").update({"voice_audio_path": None, "voice_text": "x"}).eq("night_record_id", nid).execute()

    # 30일 정리: 만료일을 과거로
    db.table("rs_night_raw").update({"expires_at": "2026-01-01T00:00:00+09:00", "audio_path": f"{uid}/x.m4a"}) \
      .eq("night_record_id", nid).execute()
    assert f"{uid}/x.m4a" in repos.nights.purge_expired_raw(now_kst())
    raw = repos.nights.get_night_raw(nid)
    assert raw.transcript is None and raw.audio_path is None
    assert repos.nights.get_night(uid, nid).tomorrow_goal == "발표"                 # 요약은 남는다


def test_night_routine_tomorrow_only(repos, uid, other, alarm):
    """밤 대화로 내일 하루만 항목 시간 바꾸기·빼기. 알람 설정은 그대로 남는다."""
    items = repos.alarms.get_alarm_routine(uid, alarm.alarm_id)
    stretch, bed = items[0].template_item_id, items[3].template_item_id
    repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {stretch: 300})          # 알람 설정: 스트레칭 5분(합계 720)
    nid = repos.nights.save_night_raw(uid, [{"role": "user", "text": "내일 스트레칭 줄여 줘", "at": ""}], None, None)
    assert repos.nights.get_night_routine(uid, nid) == []                           # 대상 알람이 아직 없음
    with pytest.raises(ValidationFailed):
        repos.nights.replace_night_routine(uid, nid, {stretch: 180})
    repos.nights.save_night_extract(uid, nid, {}, alarm.alarm_id)

    tomorrow = repos.nights.replace_night_routine(uid, nid, {stretch: 180, bed: 0})   # 내일만 3분, 이불 정리 빼기
    assert sum(i.duration_sec for i in tomorrow) == 720 - 120 - 60
    assert sum(i.duration_sec for i in repos.alarms.get_alarm_routine(uid, alarm.alarm_id)) == 720   # 알람 설정은 그대로
    rows = repos.users.db.table("rs_night_routine_item").select("template_item_id").eq("night_record_id", nid).execute().data
    assert len(rows) == 2
    assert len(repos.nights.replace_night_routine(uid, nid, {stretch: 300})) == 6     # 알람 설정과 같은 값은 저장 안 함
    assert repos.users.db.table("rs_night_routine_item").select("template_item_id").eq("night_record_id", nid).execute().data == []

    with pytest.raises(ValidationFailed) as e:
        repos.nights.replace_night_routine(uid, nid, {i.template_item_id: 180 for i in items})
    assert e.value.code == "ROUTINE_OVER_LIMIT"
    with pytest.raises(ValidationFailed) as e:
        repos.nights.replace_night_routine(uid, nid, {i.template_item_id: 0 for i in items})
    assert e.value.code == "ROUTINE_ALL_REMOVED"
    assert repos.nights.replace_night_routine(other, nid, {stretch: 60}) is None
    assert repos.nights.get_night_routine(other, nid) is None

    repos.nights.replace_night_routine(uid, nid, {stretch: 120})
    repos.alarms.update_alarm(uid, alarm.alarm_id, {"template_id": M})                # 알람 프리셋이 바뀌면 내일 변경도 무효
    assert repos.users.db.table("rs_night_routine_item").select("template_item_id").eq("night_record_id", nid).execute().data == []


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

    changed = repos.sessions.update_step_duration(uid, s.session_id, 2, 30)          # 코칭 중 "오늘 좀 적게"
    assert changed.planned_duration_sec == 30 and changed.step_status == "PENDING"
    assert repos.sessions.update_step_duration(other, s.session_id, 2, 30) is None
    assert repos.sessions.update_step_duration(uid, s.session_id, 99, 30) is None
    with pytest.raises(ValidationFailed):
        repos.sessions.update_step_duration(uid, s.session_id, 2, 0)

    assert repos.sessions.add_rewake(uid, s.session_id) == 1
    assert repos.sessions.add_rewake(uid, s.session_id) == 2
    assert repos.sessions.add_snooze(uid, s.session_id) == 1
    assert repos.sessions.add_snooze(uid, s.session_id) == 2
    checked = now_kst()
    s2 = repos.sessions.update_session(uid, s.session_id, {"planned_total_sec": 300, "wake_success": True,
                                                           "wake_checked_at": checked})
    assert (s2.planned_total_sec, s2.wake_success, s2.rewake_count, s2.snooze_count) == (300, True, 2, 2)
    assert abs((s2.wake_checked_at - checked).total_seconds()) < 1

    fortune = {"fortune_text": "떨리는 건 그만큼 준비했다는 뜻이에요.", "fortune_style": "COOKIE", "fortune_source": "ai"}
    repos.sessions.update_session(uid, s.session_id, fortune)                        # 오늘의 포춘: 다시 연결해도 같은 문장
    row = repos.users.db.table("rs_morning_session").select("fortune_text, fortune_style, fortune_source") \
        .eq("session_id", s.session_id).execute().data[0]
    assert row == fortune
    with pytest.raises(ValidationFailed):                                            # 문장만 있고 스타일이 없으면 안 됨
        repos.sessions.update_session(uid, s.session_id, {"fortune_style": None})
    with pytest.raises(ValidationFailed):
        repos.sessions.update_session(uid, s.session_id, {"fortune_style": "TAROT"})

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
    s2 = repos.sessions.update_session(uid, s.session_id, {"content_option": "WORDCHAIN"})   # AI가 정한 그날 세부
    assert s2.content_option == "WORDCHAIN"
    assert repos.alarms.get_alarm(uid, a.alarm_id).content_option == "HISTORY"              # 알람 설정은 그대로
    repos.alarms.delete_alarm(uid, a.alarm_id)


def test_stretch_area_and_intensity(repos, uid, other):
    """통화 중 부위를 고르면 판정하지 않은 단계만 새 루틴으로 바뀐다. 그날 강도·부위는 세션에 남는다."""
    from dataclasses import replace
    a = repos.alarms.create_alarm(uid, {"alarm_time": time(8, 0), "morning_call_type": "CONTENT",
                                        "content_type": "EXERCISE", "content_option": "STRETCH"})
    snap = SessionSnapshot(uid, a.alarm_id, now_kst().date(), "CONTENT", None, "EXERCISE", "STRETCH", time(8, 0), time(8, 0))
    s = repos.sessions.get_or_create_session(snap)
    full = [StepPlan(n, name, None, sec) for n, (name, sec) in
            enumerate([("누워서 기지개", 40), ("무릎 좌우로 넘기기", 65), ("무릎 가슴으로 당기기", 65)], 1)]
    neck = [StepPlan(1, name, None, sec, step_detail={"area": "NECK_SHOULDER"}) for name, sec in
            [("턱 당기기", 40), ("고개 좌우로 돌리기", 65), ("팔 열어 가슴 펴기", 65)]]

    repos.sessions.create_steps(uid, s.session_id, full)                              # 아무것도 판정하지 않았으면 1번부터
    steps = repos.sessions.replace_pending_steps(uid, s.session_id, neck)
    assert [(p.step_order, p.step_name) for p in steps] == [(1, "턱 당기기"), (2, "고개 좌우로 돌리기"), (3, "팔 열어 가슴 펴기")]
    assert steps[0].step_detail == {"area": "NECK_SHOULDER"} and steps[0].planned_duration_sec == 40

    repos.sessions.save_step(uid, replace(steps[0], step_status="DONE", checked_at=now_kst(), ai_verdict="done"))
    steps = repos.sessions.replace_pending_steps(uid, s.session_id, full)            # 판정한 1번은 두고 다음 번호부터
    assert [(p.step_order, p.step_name, p.step_status) for p in steps] == [
        (1, "턱 당기기", "DONE"), (2, "누워서 기지개", "PENDING"), (3, "무릎 좌우로 넘기기", "PENDING"), (4, "무릎 가슴으로 당기기", "PENDING")]
    assert repos.sessions.replace_pending_steps(other, s.session_id, neck) is None
    assert len(repos.sessions.list_steps(uid, s.session_id)) == 4                       # 남의 요청은 아무것도 안 바뀜
    with pytest.raises(ValidationFailed):
        repos.sessions.replace_pending_steps(uid, s.session_id, [])

    db = repos.users.db
    area = lambda: db.table("rs_morning_session").select("stretch_area").eq("session_id", s.session_id).execute().data[0]["stretch_area"]
    for code in ("NECK", "ANKLE_FOOT", "NECK_SHOULDER"):                              # 부위 12개 + 11/13 예전 값
        repos.sessions.update_session(uid, s.session_id, {"stretch_area": code})
        assert area() == code
    repos.sessions.update_session(uid, s.session_id, {"applied_intensity": 3})
    assert db.table("rs_morning_session").select("applied_intensity").eq("session_id", s.session_id).execute().data[0] \
        == {"applied_intensity": 3}
    for bad in ({"applied_intensity": 4}, {"stretch_area": "HEAD"}):
        with pytest.raises(ValidationFailed):
            repos.sessions.update_session(uid, s.session_id, bad)

    setting = db.table("rs_user_setting").select("personality, intensity, auto_intensity, english_level, theme") \
        .eq("user_id", uid).execute().data[0]
    assert setting == {"personality": "KIND", "intensity": 2, "auto_intensity": True, "english_level": "BEGINNER",
                       "theme": "AUTO"}                                                  # 기본값
    for bad in ({"personality": "FIRM"}, {"intensity": 0}, {"english_level": "ADVANCED"}, {"theme": "SYSTEM"}):
        with pytest.raises(Exception, match="check constraint"):
            db.table("rs_user_setting").update(bad).eq("user_id", uid).execute()
    repos.alarms.delete_alarm(uid, a.alarm_id)


def test_session_flow_fields(repos, uid, other):
    """11/26 회의: 모닝콜에서 말한 잠든 시각, 자동 연결 여부, 포춘까지 마친 시각을 세션에 남긴다."""
    a = repos.alarms.create_alarm(uid, {"alarm_time": time(6, 30), "morning_call_type": "CONTENT",
                                        "content_type": "QUIZ", "content_option": "PERSON"})
    snap = SessionSnapshot(uid, a.alarm_id, now_kst().date(), "CONTENT", None, "QUIZ", "PERSON", time(6, 30), time(6, 30))
    s = repos.sessions.get_or_create_session(snap)
    db = repos.users.db
    cols = "reported_bedtime, auto_connected, flow_completed_at"
    row = lambda: db.table("rs_morning_session").select(cols).eq("session_id", s.session_id).execute().data[0]
    assert row() == {"reported_bedtime": None, "auto_connected": False, "flow_completed_at": None}   # 기본값

    bed = now_kst().replace(hour=0, minute=40, second=0, microsecond=0)
    done = now_kst().replace(microsecond=0)
    repos.sessions.update_session(uid, s.session_id, {"reported_bedtime": bed, "auto_connected": True, "flow_completed_at": done})
    got = row()
    assert got["auto_connected"] is True
    assert (got["reported_bedtime"][:16], got["flow_completed_at"][:19]) == (
        bed.astimezone(timezone.utc).isoformat()[:16], done.astimezone(timezone.utc).isoformat()[:19])

    assert repos.sessions.update_session(other, s.session_id, {"auto_connected": False}) is None   # 남의 세션은 못 바꿈
    assert row()["auto_connected"] is True
    repos.sessions.update_session(uid, s.session_id, {"reported_bedtime": None})                    # 잘못 말한 시각은 지울 수 있음
    assert row()["reported_bedtime"] is None
    repos.alarms.delete_alarm(uid, a.alarm_id)


def test_bedtime_alarm(repos, uid, alarm):
    """굿나잇콜 알람은 RS_ALARM에 BEDTIME으로 저장하고, 모닝콜 흐름(세션·하루 한정 변경)에는 쓰지 못한다."""
    db = repos.users.db
    new_alarm = lambda row: db.table("rs_alarm").insert({"user_id": uid, "alarm_time": "22:00:00", **row}).execute().data[0]["alarm_id"]
    bed = new_alarm({"alarm_kind": "BEDTIME"})
    with pytest.raises(Exception, match="rs_alarm_type_fields"):                      # 굿나잇콜에 콘텐츠를 넣으면 안 됨
        new_alarm({"alarm_kind": "BEDTIME", "morning_call_type": "CONTENT", "content_type": "EXERCISE"})
    with pytest.raises(Exception, match="rs_alarm_type_fields"):                      # 굿나잇콜은 정각에만
        new_alarm({"alarm_kind": "BEDTIME", "alarm_mode": "BEFORE_10"})
    with pytest.raises(Exception, match="rs_alarm_type_fields"):                      # 모닝콜은 종류가 꼭 있어야 함
        new_alarm({"alarm_kind": "WAKE"})

    assert [a.alarm_id for a in repos.alarms.list_alarms(uid)] == [alarm.alarm_id]   # 기본은 모닝콜만
    assert [a.alarm_id for a in repos.alarms.list_alarms(uid, kind="BEDTIME")] == [bed]
    assert {a.alarm_id for a in repos.alarms.list_alarms(uid, kind=None)} == {alarm.alarm_id, bed}

    snap = SessionSnapshot(uid, bed, now_kst().date(), "CONTENT", None, "EXERCISE", None, time(22, 0), time(22, 0))
    with pytest.raises(ValidationFailed, match="굿나잇콜"):
        repos.sessions.get_or_create_session(snap)
    nid = repos.nights.save_night_raw(uid, [{"role": "user", "text": "내일 6시 반에 깨워 줘", "at": ""}], None, None)
    with pytest.raises(ValidationFailed, match="굿나잇콜"):
        repos.nights.save_night_extract(uid, nid, {"override_wake_time": time(6, 30)}, bed)
    assert repos.nights.save_night_extract(uid, nid, {"override_wake_time": time(6, 30)}, alarm.alarm_id) \
        .override_alarm_id == alarm.alarm_id                                           # 모닝콜에는 그대로 붙음

    items = repos.alarms.get_alarm_routine(uid, alarm.alarm_id)
    repos.alarms.replace_alarm_routine(uid, alarm.alarm_id, {items[0].template_item_id: 300})
    db.table("rs_alarm").update({"alarm_kind": "BEDTIME", "morning_call_type": None, "template_id": None}) \
        .eq("alarm_id", alarm.alarm_id).execute()                                      # 모닝콜 → 굿나잇콜로 바꾸면
    night = repos.nights.get_night(uid, nid)
    assert (night.override_alarm_id, night.override_wake_time) == (None, None)       # 하루 한정 변경은 지워지고
    assert db.table("rs_alarm_routine_item").select("alarm_id").eq("alarm_id", alarm.alarm_id).execute().data == []
    repos.alarms.delete_alarm(uid, bed)


def test_wake_volume_log(repos, uid, other):
    """기상 볼륨: 밤 결정과 아침 결과가 하루 한 행에 쌓이고, 다시 정해도 결과는 남는다. 학습은 한 번만."""
    p, d = repos.personalization, now_kst().date() + timedelta(days=1)
    decision = {"user_id": "무시됨", "session_date": "무시됨", "context_key": "sleep_normal", "recommended_level": 1,
                "applied_level": 3, "source": "user_today", "explored": False, "propensity": None, "state": "USER",
                "reason": "사용자가 다음 알람을 3단계로 정함", "previous_level": 1, "prefs": {"next_alarm_level": 3},
                "created_at": "2026-11-22T00:00:00+00:00"}
    assert p.save_wake_decision(uid, d, decision)["applied_level"] == 3
    for bad in ({"applied_level": 6}, {"source": "rule"}):
        with pytest.raises(ValidationFailed):
            p.save_wake_decision(uid, d, {**decision, **bad})

    row = p.save_wake_result(uid, d, start_level=3, received_level=4, confirmed=True)
    assert (row["applied_level"], row["start_level"], row["received_level"]) == (3, 3, 4)   # 결정은 그대로
    row = p.save_wake_decision(uid, d, {**decision, "applied_level": 1, "source": "hold_no_notice"})
    assert (row["applied_level"], row["received_level"]) == (1, 4)                         # 다시 정해도 결과는 남음
    assert p.save_wake_result(uid, d, 3, 6, True)["received_level"] == 6                   # 재기상 알람은 6단계까지
    with pytest.raises(ValidationFailed):
        p.save_wake_result(uid, d, 3, 2, True)                                             # 시작보다 낮게 받을 수 없음
    assert p.mark_wake_learned(uid, d) is True and p.mark_wake_learned(uid, d) is False    # 학습은 한 번만
    assert [r["session_date"] for r in p.list_wake_logs(uid, d, d)] == [d.isoformat()]
    assert p.get_wake_log(other, d) is None

    db = repos.users.db
    db.table("rs_user_setting").update({"wake_max_start_level": 3, "wake_next_level": None}).eq("user_id", uid).execute()
    with pytest.raises(Exception, match="check constraint"):
        db.table("rs_user_setting").update({"wake_next_level": 6}).eq("user_id", uid).execute()

    p.save_bandit_state(f"wake:user:{uid}", {"version": 1, "counts": {}}, uid)              # 코칭과 따로 학습
    assert p.get_bandit_state(f"wake:user:{uid}") == {"version": 1, "counts": {}}
    with pytest.raises(Exception, match="rs_bandit_state_key"):
        p.save_bandit_state("wake:user:someone", {}, uid)


def test_game_score_and_content(repos, uid, other):
    """게임 기록(주고받은 횟수)의 최고 기록과 끝말잇기 승패, 콘텐츠 화면용 단계 모아 보기, 미루기 다시 울릴 시각."""
    today = now_kst().date()
    made = []

    def session(content_type, option, area=None, steps=()):
        a = repos.alarms.create_alarm(uid, {"alarm_time": time(6, len(made)), "morning_call_type": "CONTENT",
                                            "content_type": content_type, "content_option": option})
        made.append(a.alarm_id)
        s = repos.sessions.get_or_create_session(SessionSnapshot(uid, a.alarm_id, today, "CONTENT", None, content_type, option,
                                                                 time(6, 0), time(6, 0)))
        if area:
            repos.sessions.update_session(uid, s.session_id, {"stretch_area": area})
        repos.sessions.create_steps(uid, s.session_id, [StepPlan(n, name, None, 60, step_detail=detail)
                                                        for n, (name, detail) in enumerate(steps, 1)])
        return s

    g1 = session("QUIZ", "WORDCHAIN", steps=[("끝말잇기", {"script": "시작 단어: 나무"})])
    g2 = session("QUIZ", "WORDCHAIN", steps=[("끝말잇기", {"script": "시작 단어: 바다"})])
    assert repos.sessions.best_score(uid) is None
    repos.sessions.set_step_score(uid, g1.session_id, 1, 7)
    repos.sessions.set_step_score(uid, g2.session_id, 1, 12)
    assert repos.sessions.best_score(uid) == 12 and repos.sessions.best_score(uid, "QUIZ", "WORDCHAIN") == 12
    assert repos.sessions.best_score(other) is None
    assert repos.sessions.set_step_score(other, g1.session_id, 1, 99) is None
    with pytest.raises(ValidationFailed):
        repos.sessions.set_step_score(uid, g1.session_id, 1, -1)

    step_winner = lambda s: (repos.users.db.table("rs_session_step").select("game_winner")
                             .eq("session_id", s.session_id).eq("step_order", 1).execute().data[0]["game_winner"])
    assert step_winner(g1) is None and repos.sessions.count_user_wins(uid) == 0
    assert repos.sessions.set_game_winner(uid, g1.session_id, 1, "USER") is not None
    repos.sessions.set_game_winner(uid, g2.session_id, 1, "NEXTY")
    assert (step_winner(g1), step_winner(g2), repos.sessions.count_user_wins(uid)) == ("USER", "NEXTY", 1)
    repos.sessions.set_game_winner(uid, g2.session_id, 1, "USER")
    assert repos.sessions.count_user_wins(uid) == 2 and repos.sessions.count_user_wins(uid, "PERSON") == 0
    assert repos.sessions.count_user_wins(other) == 0
    assert repos.sessions.set_game_winner(other, g1.session_id, 1, "NEXTY") is None
    assert repos.sessions.set_game_winner(uid, g1.session_id, 9, "NEXTY") is None
    assert step_winner(g1) == "USER"
    with pytest.raises(ValidationFailed):
        repos.sessions.set_game_winner(uid, g1.session_id, 1, "DRAW")
    repos.sessions.set_game_winner(uid, g2.session_id, 1, None)
    assert step_winner(g2) is None and repos.sessions.count_user_wins(uid) == 1

    stretch = session("EXERCISE", "STRETCH", "NECK", [("턱 당기기", {"area": "NECK", "script": "턱을 살짝 당겨요"})])
    rows = [r for r in repos.sessions.list_content_steps(uid, "EXERCISE", today, today) if r["session_id"] == stretch.session_id]
    assert [(r["step_name"], r["stretch_area"], r["step_detail"]["script"]) for r in rows] == [("턱 당기기", "NECK", "턱을 살짝 당겨요")]
    assert all(r["session_date"] == today.isoformat() for r in repos.sessions.list_content_steps(uid, "EXERCISE", today, today))
    assert repos.sessions.list_content_steps(other, "EXERCISE", today, today) == []

    until = now_kst() + timedelta(minutes=10)
    repos.sessions.update_session(uid, g1.session_id, {"snoozed_until": until})
    saved = repos.users.db.table("rs_morning_session").select("snoozed_until").eq("session_id", g1.session_id).execute().data[0]
    assert saved["snoozed_until"] is not None
    for alarm_id in made:
        repos.alarms.delete_alarm(uid, alarm_id)


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

    d2 = date(2026, 11, 3)
    path2 = repos.storage.upload_photo(uid, d2, b"\xff\xd8\xff\xd9").photo_path
    assert repos.storage.delete_photo("00000000-0000-4000-8000-000000000000", d2) is False     # 남의 사진
    assert repos.storage.get_photo(uid, d2) is not None
    assert repos.storage.delete_photo(uid, d2) is True
    assert repos.storage.get_photo(uid, d2) is None
    assert repos.storage.signed_url("photo", path2, 60) is None                                # 파일도 지워짐
    assert repos.storage.delete_photo(uid, d2) is False                                        # 다시 지우면 False

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
