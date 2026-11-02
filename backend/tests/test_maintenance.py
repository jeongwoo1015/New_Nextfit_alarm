"""정리 작업 — 메모리 저장소로 확인 (실제 DB 확인은 test_supabase_repos.py)."""
from datetime import date, datetime, time, timedelta

from backend.clients.ai_gateway import NightExtract
from backend.clients.fake_ai import FakeAIGateway
from backend.core.timeutil import KST
from backend.jobs import maintenance
from backend.repositories.memory import memory_repositories
from backend.repositories.seed import template_id

USER = "aaaaaaaa-0000-0000-0000-000000000001"
SAVED = datetime(2026, 11, 5, 23, 30, tzinfo=KST)        # 목요일 밤 → 금요일 아침 적용
TALK = [{"role": "user", "text": "내일은 6시 반에 깨워 줘", "at": "2026-11-05T23:30:00+09:00"}]


def _setup():
    clock = lambda: SAVED
    repos = memory_repositories(clock=clock)
    repos.users.create_user(USER, "테스트", date(2003, 1, 1), "NONE")
    repos.users.save_setting(USER, {"user_call_name": "찬미", "music_genre": "POP"})
    alarm = repos.alarms.create_alarm(USER, {"alarm_time": time(7, 0), "morning_call_type": "ROUTINE",
                                             "repeat_days": "1111111", "template_id": template_id("N")})
    nid = repos.nights.save_night_raw(USER, TALK, SAVED - timedelta(minutes=5), SAVED)
    return repos, alarm, nid


def test_purge_keeps_summary_and_clears_raw():
    repos, _, nid = _setup()
    report = maintenance.run(repos, FakeAIGateway(), SAVED + timedelta(days=31))
    assert repos.nights.get_night_raw(nid).transcript is None
    assert repos.nights.get_night(USER, nid) is not None
    assert report.errors == []


def test_purge_before_30_days_keeps_raw():
    repos, _, nid = _setup()
    maintenance.run(repos, FakeAIGateway(), SAVED + timedelta(days=29))
    assert repos.nights.get_night_raw(nid).transcript == TALK


def test_retry_failed_extract_uses_original_saved_time():
    repos, alarm, nid = _setup()
    repos.nights.mark_extract_failed(USER, nid)
    ai = FakeAIGateway(extract=NightExtract("DONE", {"ai_summary": "요약", "tomorrow_notes": [],
                                                       "override_wake_time": time(6, 30)}))
    report = maintenance.run(repos, ai, SAVED + timedelta(days=2))     # 이틀 뒤에 재시도
    rec = repos.nights.get_night(USER, nid)
    assert (report.retried, report.recovered) == (1, 1)
    assert rec.extract_status == "DONE" and rec.override_alarm_id == alarm.alarm_id


def test_retry_still_failing_stays_failed():
    repos, _, nid = _setup()
    repos.nights.mark_extract_failed(USER, nid)
    report = maintenance.run(repos, FakeAIGateway(extract=NightExtract("FAILED", {}, "503")), SAVED + timedelta(days=1))
    assert (report.retried, report.recovered) == (1, 0)
    assert repos.nights.get_night(USER, nid).extract_status == "FAILED"


def test_retry_after_raw_purged_does_not_call_ai():
    repos, _, nid = _setup()
    repos.nights.mark_extract_failed(USER, nid)
    ai = FakeAIGateway()
    maintenance.run(repos, ai, SAVED + timedelta(days=31))
    assert not [c for c in ai.calls if c[0] == "extract"]
    assert repos.nights.get_night(USER, nid).extract_status == "FAILED"


def test_delete_account_removes_files_first():
    repos, _, nid = _setup()
    repos.storage.upload_voice(USER, date(2026, 11, 5), b"\x00")
    assert maintenance.delete_account(repos, USER) == 1
    assert repos.users.get_user(USER) is None
