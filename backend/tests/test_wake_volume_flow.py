"""기상 알람 시작 볼륨 개인화 흐름 (AI B23, DB 11-22): 밤에 정하고 → 밤 대화로 확정 → 앱이 미리 받고 → 아침 보고·학습 → 리포트.

시계 기본값은 2026-11-05(목) 23:30, 온보딩 알람은 07:00 매일이라 대상은 11-06(금) 07:00.
가짜 AI는 추천을 wake_recommended로 고정하고, 사용자 우선·상승 보류 규칙만 AI처럼 흉내 낸다.
"""
from datetime import date, datetime

import pytest

from backend.clients.ai_gateway import WakeInputs, default_wake_decision
from backend.core.timeutil import KST
from backend.domain import wake_rules

from .conftest import USER_A, USER_B, auth
from .test_api_users import onboard

FRI = date(2026, 11, 6)
THU = date(2026, 11, 5)
H = auth(USER_A)
SILENT = [{"role": "assistant", "text": "오늘 하루 어땠어요?"}, {"role": "user", "text": "괜찮았어"}]
ANNOUNCED = [{"role": "assistant", "text": "내일은 알람 소리를 3단계부터 시작할게요. 괜찮으세요?"}, {"role": "user", "text": "응 좋아"}]


@pytest.fixture
def user(client):
    onboard(client)
    return client


def yesterday(repos, level):
    """목요일 아침에 level단계로 울린 기록 (직전 단계)."""
    repos.personalization.save_wake_result(USER_A, THU, level, level, True)


def night(client, transcript, events_tomorrow=()):
    client.post("/night/token", json={"events_tomorrow": list(events_tomorrow)}, headers=H)
    return client.post("/night/transcript", json={"transcript": transcript}, headers=H)


def next_level(client):
    return client.get("/wake-volume/next", headers=H).json()


def morning(client, clock):
    clock.now = datetime(2026, 11, 6, 7, 0, tzinfo=KST)
    alarm = client.get("/alarms", headers=H).json()[0]
    return client.post("/morning/sessions", json={"alarm_id": alarm["alarm_id"]}, headers=H).json()["session"]["session_id"]


def test_raise_is_announced_at_night_then_applied(user, ai, repos):
    yesterday(repos, 1)
    ai.wake_recommended = 3
    user.post("/night/token", json={}, headers=H)
    s = ai.calls[-1][1].settings                                                   # 밤 입력: 올리는 날이라 AI가 알린다
    assert (s.wake_start_level, s.wake_raise_from, s.wake_state) == (3, 1, "RECOMMENDED")
    user.post("/night/transcript", json={"transcript": ANNOUNCED}, headers=H)
    n = next_level(user)
    assert (n["session_date"], n["alarm_time"], n["start_level"], n["rewake_level"], n["source"]) == ("2026-11-06", "07:00", 3, 4, "bandit")
    row = repos.personalization.get_wake_log(USER_A, FRI)
    assert (row["previous_level"], row["prefs"]["announced"], row["prefs"]["important_day"]) == (1, True, False)


def test_raise_not_announced_keeps_previous_level(user, ai, repos):
    yesterday(repos, 1)
    ai.wake_recommended = 3
    night(user, SILENT)                                                            # AI가 알리지 않음
    n = next_level(user)
    assert (n["start_level"], n["source"]) == (1, "hold_no_notice")
    seeds = {c[1][2] for c in ai.calls if c[0] == "wake_decide"}
    assert seeds == {f"{USER_A}|2026-11-06"}                                       # 다시 정해도 같은 seed → 같은 추천


def test_no_night_talk_does_not_raise(user, ai, repos):
    yesterday(repos, 1)
    ai.wake_recommended = 3
    assert (next_level(user)["start_level"], next_level(user)["source"]) == (1, "hold_no_notice")
    repos.personalization.wake_logs.clear()
    assert next_level(user)["start_level"] == 3                                     # 직전 단계가 없으면 올리는 날이 아니다


def test_user_values_win_and_next_level_is_used_once(user, ai, repos, clock):
    ai.wake_recommended = 3
    user.patch("/settings", json={"wake_max_start_level": 2}, headers=H)
    assert (next_level(user)["start_level"], next_level(user)["source"], next_level(user)["state"]) == (2, "user_max", "USER")
    night(user, [{"role": "assistant", "text": "내일 몇 단계로 할까요?"}, {"role": "user", "text": "내일은 4단계"}])
    user.post("/settings/ai-change", json={"field": "wake_volume", "value": "4", "scope": "TODAY"}, headers=H)
    assert next_level(user)["start_level"] == 4 and next_level(user)["source"] == "user_today"
    morning(user, clock)                                                           # 알람이 울렸으면 '다음 한 번'은 쓴 것
    assert user.get("/settings", headers=H).json()["wake_next_level"] is None
    n = next_level(user)                                                           # 오늘 남은 알람이 없으니 다음은 토요일
    assert (n["session_date"], n["start_level"], n["source"]) == ("2026-11-07", 2, "user_max")   # 한 번만 쓰고 상한으로 돌아감


def test_later_alarm_same_morning_keeps_decided_level(user, ai, repos, clock):
    """그날 첫 알람이 울린 뒤 다른 모닝콜이 남아 있으면 같은 단계로 울린다 (다시 정하지 않음)."""
    user.post("/alarms", json={"alarm_time": "07:30", "morning_call_type": "CONTENT", "content_type": "QUIZ",
                               "content_option": "WORDCHAIN"}, headers=H)
    ai.wake_recommended = 3
    assert next_level(user)["start_level"] == 3
    ai.wake_recommended = 1
    morning(user, clock)
    n = next_level(user)
    assert (n["session_date"], n["alarm_time"], n["start_level"]) == ("2026-11-06", "07:00", 3)


def test_important_day_from_morning_events(user, ai, repos):
    night(user, SILENT, [{"title": "면접", "start": "09:30"}])
    assert repos.personalization.get_wake_log(USER_A, FRI)["source"] == "important_day"
    assert wake_rules.is_important_day([]) is False


def test_report_then_learn_once_at_wake_check(user, ai, repos, clock):
    night(user, SILENT)
    sid = morning(user, clock)
    url = f"/morning/sessions/{sid}"
    r = user.post(f"{url}/wake-volume", json={"start_level": 1, "received_level": 3}, headers=H).json()
    assert (r["start_level"], r["received_level"], r["confirmed"], r["user_set"]) == (1, 3, None, False)
    assert not [c for c in ai.calls if c[0] == "wake_learn"]                       # 확인 질문 전에는 학습하지 않음
    user.post(f"{url}/response", headers=H)
    user.post(f"{url}/wake-check", headers=H)
    learned = [c[1] for c in ai.calls if c[0] == "wake_learn"]
    assert learned == [("ok", 1, 3, True)] * 2                                  # 내 기록 + 전체 기록
    assert repos.personalization.get_bandit_state(f"wake:user:{USER_A}")["days"] == [["ok", 1, 3, True]]
    assert repos.personalization.get_bandit_state("wake:global")["days"] == [["ok", 1, 3, True]]
    assert repos.personalization.get_wake_log(USER_A, FRI)["confirmed"] is True
    user.post(f"{url}/end", headers=H)                                             # 끝내도 다시 학습하지 않음
    r = user.post(f"{url}/wake-volume", json={"start_level": 2, "received_level": 2}, headers=H).json()
    assert (r["start_level"], len([c for c in ai.calls if c[0] == "wake_learn"])) == (1, 2)   # 두 번째 보고는 무시


def test_rewake_before_check_is_not_confirmed(user, ai, repos, clock):
    sid = morning(user, clock)
    url = f"/morning/sessions/{sid}"
    user.post(f"{url}/wake-volume", json={"start_level": 1, "received_level": 1}, headers=H)
    user.post(f"{url}/rewake", headers=H)
    user.post(f"{url}/wake-check", headers=H)
    assert [c[1] for c in ai.calls if c[0] == "wake_learn"][0][3] is False
    assert repos.personalization.get_wake_log(USER_A, FRI)["confirmed"] is False


def test_not_received_learns_at_once_and_end_without_check(user, ai, repos, clock):
    sid = morning(user, clock)
    user.post(f"/morning/sessions/{sid}/wake-volume", json={"start_level": 2, "received_level": None}, headers=H)
    assert [c[1] for c in ai.calls if c[0] == "wake_learn"][0][1:] == (2, None, False)

    clock.now = datetime(2026, 11, 7, 7, 0, tzinfo=KST)                           # 다음 날: 확인 없이 끝남
    alarm = user.get("/alarms", headers=H).json()[0]
    sid = user.post("/morning/sessions", json={"alarm_id": alarm["alarm_id"]}, headers=H).json()["session"]["session_id"]
    user.post(f"/morning/sessions/{sid}/wake-volume", json={"start_level": 1, "received_level": 2}, headers=H)
    user.post(f"/morning/sessions/{sid}/end", headers=H)
    assert [c[1] for c in ai.calls if c[0] == "wake_learn"][-1][1:] == (1, 2, False)


def test_wake_volume_input_checks(user, clock):
    sid = morning(user, clock)
    url = f"/morning/sessions/{sid}/wake-volume"
    for bad in ({"start_level": 3, "received_level": 2}, {"start_level": 0, "received_level": None},
                {"start_level": 1, "received_level": 6}, {"start_level": 1}):
        assert user.post(url, json=bad, headers=H).status_code == 422
    assert user.post(url, json={"start_level": 1, "received_level": 1}, headers=auth(USER_B)).status_code == 404


def test_no_upcoming_alarm(client):
    onboard(client)
    alarm = client.get("/alarms", headers=H).json()[0]
    client.delete(f"/alarms/{alarm['alarm_id']}", headers=H)
    r = client.get("/wake-volume/next", headers=H)
    assert r.status_code == 404 and r.json()["error"]["code"] == "NO_UPCOMING_ALARM"


def test_report_shows_wake_levels(user, ai, repos, clock):
    yesterday(repos, 1)
    night(user, SILENT)
    sid = morning(user, clock)
    user.post(f"/morning/sessions/{sid}/wake-volume", json={"start_level": 1, "received_level": 2}, headers=H)
    clock.now = datetime(2026, 11, 6, 22, 0, tzinfo=KST)
    next_level(user)                                                               # 내일(토) 값은 리포트에 넣지 않는다
    levels = user.get("/reports?period=week", headers=H).json()["wake_levels"]
    assert [(x["session_date"], x["start_level"], x["received_level"]) for x in levels] == [("2026-11-05", 1, 1), ("2026-11-06", 1, 2)]


def test_default_decision_without_ai():
    """AI를 쓸 수 없어도 알람은 울린다: 사용자 값 → 직전 단계(상한 안) → 1단계."""
    row = default_wake_decision(USER_A, FRI, WakeInputs(previous_level=4, max_start_level=3)).row
    assert (row["applied_level"], row["source"]) == (3, "default")
    assert default_wake_decision(USER_A, FRI, WakeInputs(next_level=5)).row["source"] == "user_today"
    assert default_wake_decision(USER_A, FRI, WakeInputs()).row["applied_level"] == 1
    assert wake_rules.rewake_level(5) == 6 and wake_rules.rewake_level(1) == 2
