"""메모리 저장소의 기상 볼륨 기록·게임 기록·콘텐츠 단계·미루기 시각 (11/22) — Supabase(15_wake_volume.sql, supabase.py)와 같게 동작하는지."""
from datetime import date, datetime, time, timedelta

import pytest

from backend.core.errors import ValidationFailed
from backend.core.timeutil import KST
from backend.domain.models import SessionSnapshot, StepPlan

from .conftest import USER_A, USER_B

DAY = date(2026, 11, 23)
DECISION = {"user_id": "무시됨", "session_date": "무시됨", "context_key": "ok", "recommended_level": 1, "applied_level": 3,
            "source": "user_today", "explored": False, "propensity": None, "state": "USER", "reason": "사용자가 3단계로 정함",
            "previous_level": 1, "prefs": {"max_start_level": None, "next_alarm_level": 3}, "created_at": "2026-11-22T00:00:00+00:00"}


def test_wake_decision_and_result_share_one_row(repos):
    p = repos.personalization
    row = p.save_wake_decision(USER_A, DAY, DECISION)
    assert (row["applied_level"], row["session_date"], row["user_id"]) == (3, DAY.isoformat(), USER_A)   # 넘긴 user_id·날짜 키는 무시
    assert "created_at" in row and row["created_at"] != DECISION["created_at"]
    for bad in ({"applied_level": 6}, {"source": "rule"}, {"state": "AUTO"}, {"propensity": 1.5}):
        with pytest.raises(ValidationFailed):
            p.save_wake_decision(USER_A, DAY, {**DECISION, **bad})

    row = p.save_wake_result(USER_A, DAY, start_level=3, received_level=4, confirmed=True, session_id="s1")
    assert (row["applied_level"], row["start_level"], row["received_level"], row["session_id"]) == (3, 3, 4, "s1")
    row = p.save_wake_decision(USER_A, DAY, {**DECISION, "applied_level": 1, "source": "hold_no_notice", "state": "RECOMMENDED"})
    assert (row["applied_level"], row["received_level"]) == (1, 4)                       # 다시 정해도 결과는 남음
    assert p.save_wake_result(USER_A, DAY, 3, 6, True)["received_level"] == 6             # 재기상 알람은 6단계까지
    assert p.save_wake_result(USER_A, DAY, 3, None, False)["received_level"] is None      # 못 받음
    with pytest.raises(ValidationFailed):
        p.save_wake_result(USER_A, DAY, 3, 2, True)                                       # 시작보다 낮게 받을 수 없음
    with pytest.raises(ValidationFailed):
        p.save_wake_result(USER_A, DAY, 3, 7, True)

    assert p.mark_wake_learned(USER_A, DAY) is True and p.mark_wake_learned(USER_A, DAY) is False   # 학습은 한 번만
    assert p.mark_wake_learned(USER_A, DAY + timedelta(days=1)) is False                # 행이 없는 날
    assert p.get_wake_log(USER_B, DAY) is None and p.get_wake_log(USER_A, DAY)["learned_at"]


def test_wake_logs_listed_by_date(repos):
    p = repos.personalization
    for d in (DAY + timedelta(days=2), DAY, DAY + timedelta(days=1)):
        p.save_wake_result(USER_A, d, 1, 2, True)
    p.save_wake_result(USER_B, DAY, 1, 1, True)
    got = [r["session_date"] for r in p.list_wake_logs(USER_A, DAY, DAY + timedelta(days=1))]
    assert got == [DAY.isoformat(), (DAY + timedelta(days=1)).isoformat()]


def test_bandit_state_keys(repos):
    p = repos.personalization
    p.save_bandit_state("wake:global", {"counts": {}})
    p.save_bandit_state(f"wake:user:{USER_A}", {"counts": {"ok|1": [1, 0]}}, USER_A)     # 코칭(user:)과 따로
    p.save_bandit_state(f"user:{USER_A}", {"counts": {}}, USER_A)
    assert p.get_bandit_state(f"wake:user:{USER_A}") == {"counts": {"ok|1": [1, 0]}}
    for key, uid in ((f"wake:user:{USER_B}", USER_A), ("wake:global", USER_A), (f"user:{USER_A}", None), ("other", None)):
        with pytest.raises(ValidationFailed):
            p.save_bandit_state(key, {}, uid)


def test_wake_levels_in_user_setting(repos):
    repos.users.save_setting(USER_A, {"user_call_name": "정우", "music_genre": "POP"})
    s = repos.users.save_setting(USER_A, {"wake_max_start_level": 3, "wake_next_level": 4})
    assert (s.wake_max_start_level, s.wake_next_level) == (3, 4)
    assert repos.users.save_setting(USER_A, {"wake_next_level": None}).wake_next_level is None     # 쓰고 나면 비움
    for bad in ({"wake_next_level": 6}, {"wake_max_start_level": 0}):
        with pytest.raises(ValidationFailed):
            repos.users.save_setting(USER_A, bad)


def _session(repos, user_id, n, content_type, option, area=None, steps=(), day=DAY):
    s = repos.sessions.get_or_create_session(SessionSnapshot(user_id, f"alarm-{n}", day, "CONTENT", None, content_type, option,
                                                             time(6, 0), time(6, 0)))
    if area:
        repos.sessions.update_session(user_id, s.session_id, {"stretch_area": area})
    repos.sessions.create_steps(user_id, s.session_id, [StepPlan(i, name, None, 60, step_detail=detail)
                                                        for i, (name, detail) in enumerate(steps, 1)])
    return s


def test_game_score_best(repos):
    g1 = _session(repos, USER_A, 1, "QUIZ", "WORDCHAIN", steps=[("끝말잇기", {"script": "시작 단어: 나무"})])
    g2 = _session(repos, USER_A, 2, "QUIZ", "WORDCHAIN", steps=[("끝말잇기", {"script": "시작 단어: 바다"})])
    assert repos.sessions.best_score(USER_A) is None
    assert repos.sessions.set_step_score(USER_A, g1.session_id, 1, 7).score == 7
    repos.sessions.set_step_score(USER_A, g2.session_id, 1, 12)
    assert repos.sessions.best_score(USER_A) == 12 == repos.sessions.best_score(USER_A, "QUIZ", "WORDCHAIN")
    assert repos.sessions.best_score(USER_A, "QUIZ", "OTHER") is None
    assert repos.sessions.best_score(USER_B) is None
    assert repos.sessions.set_step_score(USER_B, g1.session_id, 1, 99) is None             # 남의 세션
    assert repos.sessions.set_step_score(USER_A, g1.session_id, 9, 1) is None              # 없는 단계
    with pytest.raises(ValidationFailed):
        repos.sessions.set_step_score(USER_A, g1.session_id, 1, -1)


def test_content_steps_and_snoozed_until(repos):
    later = _session(repos, USER_A, 1, "EXERCISE", "STRETCH", "NECK", [("턱 당기기", {"area": "NECK", "script": "턱을 살짝 당겨요"})],
                     day=DAY + timedelta(days=1))
    first = _session(repos, USER_A, 2, "EXERCISE", "STRETCH", "FULL", [("기지개", {"script": "쭉 펴요"}), ("목 돌리기", {"script": "천천히"})])
    _session(repos, USER_A, 3, "ENGLISH", "DAILY", steps=[("문장", {"script": "Good morning"})])
    rows = repos.sessions.list_content_steps(USER_A, "EXERCISE", DAY, DAY + timedelta(days=1))
    assert [(r["session_date"], r["step_name"], r["stretch_area"], r["step_detail"]["script"]) for r in rows] == [
        (DAY.isoformat(), "기지개", "FULL", "쭉 펴요"), (DAY.isoformat(), "목 돌리기", "FULL", "천천히"),
        ((DAY + timedelta(days=1)).isoformat(), "턱 당기기", "NECK", "턱을 살짝 당겨요")]
    assert {r["session_id"] for r in rows} == {first.session_id, later.session_id}
    assert rows[0]["content_option"] == "STRETCH"
    assert repos.sessions.list_content_steps(USER_A, "EXERCISE", DAY, DAY) == rows[:2]
    assert repos.sessions.list_content_steps(USER_B, "EXERCISE", DAY, DAY + timedelta(days=1)) == []

    until = datetime(2026, 11, 23, 6, 10, tzinfo=KST)
    assert repos.sessions.update_session(USER_A, first.session_id, {"snoozed_until": until}).snoozed_until == until
