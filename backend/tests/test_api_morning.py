from datetime import datetime, time

import pytest

from backend.clients.ai_gateway import ItemChange, NightExtract, RoutineChangeRequest, Weather
from backend.core.errors import ServiceUnavailable
from backend.core.timeutil import KST
from backend.repositories.seed import template_id

from .conftest import USER_A, USER_B, auth
from .test_api_night import TALK
from .test_api_users import onboard

FRIDAY_7 = datetime(2026, 11, 6, 7, 0, tzinfo=KST)


@pytest.fixture
def morning(client, clock):
    """온보딩(프리셋 N, 07:00 매일) 뒤 금요일 07:00."""
    onboard(client)
    clock.now = FRIDAY_7
    return client.get("/alarms", headers=auth(USER_A)).json()[0]


def start(client, alarm, user=USER_A):
    return client.post("/morning/sessions", json={"alarm_id": alarm["alarm_id"]}, headers=auth(user))


def url(view, tail=""):
    return f"/morning/sessions/{view['session']['session_id']}{tail}"


def test_start_session_is_idempotent(client, morning, clock):
    v = start(client, morning).json()
    s = v["session"]
    assert (s["session_date"], s["morning_call_type"], s["template_id"], s["target_wake_time"], s["coaching_skipped"]) == (
        "2026-11-06", "ROUTINE", template_id("N"), "07:00", False)
    assert s["alarm_rang_at"].startswith("2026-11-06T07:00") and v["steps"] == []
    clock.now = datetime(2026, 11, 6, 7, 5, tzinfo=KST)
    again = start(client, morning).json()["session"]
    assert again["session_id"] == s["session_id"] and again["alarm_rang_at"] == s["alarm_rang_at"]


def test_night_override_skips_coaching(client, morning, clock, ai):
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    ai.extract = NightExtract("DONE", {"override_wake_time": time(6, 30), "override_skip_coaching": True, "condition_label": "TIRED"})
    client.post("/night/transcript", json={"transcript": TALK}, headers=auth(USER_A))
    clock.now = FRIDAY_7
    v = start(client, morning).json()
    assert (v["session"]["coaching_skipped"], v["session"]["target_wake_time"], v["session"]["base_wake_time"]) == (True, "06:30", "07:00")
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A))
    ctx = ai.calls[-1][1]
    assert ctx.coaching_skipped is True and ctx.night.condition_label == "TIRED"
    r = client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    assert r.status_code == 409 and r.json()["error"]["code"] == "COACHING_SKIPPED"


def test_briefing_token_decides_strategy_once(client, morning, ai, repos):
    v = start(client, morning).json()
    assert client.post(url(v, "/briefing-token"), json={"events_today": [{"title": "회의", "start": "10:00"}]}, headers=auth(USER_A)).status_code == 200
    ctx = ai.calls[-1][1]
    assert ctx.program_name == "간단하게 하루를 바꾸는 현실 모닝루틴" and ctx.content_name == ""
    assert ctx.strategy_block == "[브리핑 전략 gentle]" and ctx.now_hhmm == "07:00" and ctx.events_today[0].title == "회의"
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A))                # 재연결
    assert [c[0] for c in ai.calls].count("decide") == 1
    log = repos.personalization.get_strategy_log(USER_A, FRIDAY_7.date())
    assert log.strategy_id == "gentle" and log.session_id == v["session"]["session_id"]


def test_briefing_weather_from_app_location(client, morning, ai, weather):
    """위치를 보내면 그 위치의 날씨를 브리핑에 넣고, 안 보내거나 날씨가 없으면 날씨 없이."""
    v = start(client, morning).json()
    weather.result = Weather("맑음", 3.5, 9.0, 1.2, 10, "광진구")
    body = {"location": {"lat": 37.5385, "lon": 127.0823, "name": " 광진구 "}}
    assert client.post(url(v, "/briefing-token"), json=body, headers=auth(USER_A)).status_code == 200
    assert weather.calls[-1] == (37.5385, 127.0823, "광진구") and ai.calls[-1][1].weather == weather.result
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A))
    assert len(weather.calls) == 1 and ai.calls[-1][1].weather is None                # 위치가 없으면 부르지도 않음
    weather.result = None                                                               # 날씨 API 실패
    assert client.post(url(v, "/briefing-token"), json=body, headers=auth(USER_A)).status_code == 200
    assert ai.calls[-1][1].weather is None
    bad = {"location": {"lat": 91, "lon": 127.0}}
    assert client.post(url(v, "/briefing-token"), json=bad, headers=auth(USER_A)).status_code == 422


def test_strategy_failure_falls_back_to_default(client, morning, ai, repos):
    def broken(*_, **__):
        raise ServiceUnavailable("AI 기능을 쓸 수 없어요", "AI_UNAVAILABLE")
    ai.decide_strategy = broken
    v = start(client, morning).json()
    assert client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A)).status_code == 200
    assert ai.calls[-1][1].strategy_block == "" and repos.personalization.logs == {}


def test_coaching_creates_steps_once_and_resumes(client, morning, ai):
    v = start(client, morning).json()
    r = client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    assert r.status_code == 200 and r.json()["token"] == "tok-coaching" and len(r.json()["steps"]) == 6
    ctx = ai.calls[-1][1]
    assert (len(ctx.steps), ctx.resumed, ctx.morning_call_type) == (6, False, "ROUTINE")
    assert ctx.steps[0].response_type == "PLAY" and ctx.steps[0].script and ctx.steps[5].response_type == "SPEECH"
    assert ctx.strategy_block == "[코칭 전략 gentle]"
    assert client.get(url(v), headers=auth(USER_A)).json()["session"]["planned_total_sec"] == 600

    client.post(url(v, "/steps/1/verdict"), json={"verdict": "done"}, headers=auth(USER_A))
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))                         # 재연결
    ctx = ai.calls[-1][1]
    assert (len(ctx.steps), ctx.steps[0].order, ctx.resumed) == (5, 2, True)
    assert len(client.get(url(v), headers=auth(USER_A)).json()["steps"]) == 6           # 다시 만들지 않음


def test_steps_follow_alarm_item_times(client, morning):
    items = client.get(f"/alarms/{morning['alarm_id']}/routine-items", headers=auth(USER_A)).json()["items"]
    body = {"items": [{"template_item_id": items[0]["template_item_id"], "duration_sec": 300},
                      {"template_item_id": items[3]["template_item_id"], "duration_sec": 0}]}
    client.put(f"/alarms/{morning['alarm_id']}/routine-items", json=body, headers=auth(USER_A))
    v = start(client, morning).json()
    steps = client.post(url(v, "/coaching-token"), headers=auth(USER_A)).json()["steps"]
    assert [s["step_name"] for s in steps] == ["스트레칭 (축소)", "햇빛 보기", "물 한 잔", "세수·양치", "오늘 할 일 3개 말하기"]
    assert steps[0]["planned_duration_sec"] == 300
    assert client.get(url(v), headers=auth(USER_A)).json()["session"]["planned_total_sec"] == 660


def test_deleted_alarm_falls_back_to_preset_defaults(client, morning):
    v = start(client, morning).json()
    client.delete(f"/alarms/{morning['alarm_id']}", headers=auth(USER_A))
    assert len(client.post(url(v, "/coaching-token"), headers=auth(USER_A)).json()["steps"]) == 6


def test_verdicts_and_manual_marks(client, morning):
    v = start(client, morning).json()
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    r = client.post(url(v, "/steps/1/verdict"), json={"verdict": "done"}, headers=auth(USER_A)).json()
    assert r["steps"][0]["step_status"] == "DONE" and r["progress"]["done"] == 1
    r = client.post(url(v, "/steps/2/verdict"), json={"verdict": "not_done"}, headers=auth(USER_A)).json()
    assert r["steps"][1]["step_status"] == "SKIPPED" and r["steps"][1]["ai_verdict"] == "not_done"
    r = client.post(url(v, "/steps/3/verdict"), json={"verdict": "ambiguous"}, headers=auth(USER_A)).json()
    assert r["steps"][2]["step_status"] == "PENDING" and r["steps"][2]["checked_at"] is None
    r = client.patch(url(v, "/steps/2"), json={"step_status": "DONE"}, headers=auth(USER_A)).json()
    assert r["steps"][1]["step_status"] == "DONE" and r["progress"]["done"] == 2
    assert client.post(url(v, "/steps/1/verdict"), json={"verdict": "DONE"}, headers=auth(USER_A)).status_code == 422
    assert client.post(url(v, "/steps/9/verdict"), json={"verdict": "done"}, headers=auth(USER_A)).status_code == 404


def test_all_checked_then_coaching_conflict(client, morning):
    v = start(client, morning).json()
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    for n in range(1, 7):
        r = client.post(url(v, f"/steps/{n}/verdict"), json={"verdict": "done"}, headers=auth(USER_A)).json()
    assert r["progress"]["finished"] is True
    r = client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    assert r.status_code == 409 and r.json()["error"]["code"] == "ALL_STEPS_CHECKED"


def test_content_alarm_steps_from_ai(client, morning, ai):
    alarm = client.post("/alarms", json={"alarm_time": "06:30", "morning_call_type": "CONTENT", "content_type": "QUIZ",
                                         "content_option": "HISTORY"}, headers=auth(USER_A)).json()
    v = start(client, alarm).json()
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A))
    assert ai.calls[-1][1].content_name == "퀴즈 게임" and ai.calls[-1][1].program_name == ""
    steps = client.post(url(v, "/coaching-token"), headers=auth(USER_A)).json()["steps"]
    assert [s["template_item_id"] for s in steps] == [None, None, None]
    assert ("content", ("QUIZ", "HISTORY", 300)) in ai.calls
    ctx = ai.calls[-1][1]
    assert ctx.morning_call_type == "CONTENT" and ctx.steps[0].response_type == "SPEECH" and "정답:" in ctx.steps[0].script
    assert client.get(url(v), headers=auth(USER_A)).json()["session"]["planned_total_sec"] == 300
    assert (ctx.content_type, ctx.content_option) == ("QUIZ", "HISTORY")                  # B14: 콘텐츠 종류·세부를 코칭에


def test_content_option_decided_by_ai_is_kept(client, morning, ai):
    """세부를 고르지 않은 퀴즈 알람: AI가 정한 오늘 세부를 세션에 남기고, 코칭·재연결·브리핑 이름에 쓴다."""
    alarm = client.post("/alarms", json={"alarm_time": "06:30", "morning_call_type": "CONTENT", "content_type": "QUIZ"},
                        headers=auth(USER_A)).json()
    v = start(client, alarm).json()
    assert v["session"]["content_option"] is None
    ai.content_option = "WORDCHAIN"
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    assert (ai.calls[-1][1].content_type, ai.calls[-1][1].content_option) == ("QUIZ", "WORDCHAIN")
    assert client.get(url(v), headers=auth(USER_A)).json()["session"]["content_option"] == "WORDCHAIN"
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))                         # 재연결: 다시 구성하지 않고 같은 세부
    assert [c[0] for c in ai.calls].count("content") == 1 and ai.calls[-1][1].content_option == "WORDCHAIN"
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_A))
    assert ai.calls[-1][1].content_name == "끝말잇기"
    assert client.get("/alarms", headers=auth(USER_A)).json()[0]["content_option"] is None   # 알람 설정은 그대로


@pytest.mark.parametrize("option", ["PERSON", "GENERAL", "WORDCHAIN"])
def test_new_quiz_options_accepted(client, morning, option):
    r = client.post("/alarms", json={"alarm_time": "06:30", "morning_call_type": "CONTENT", "content_type": "QUIZ",
                                     "content_option": option}, headers=auth(USER_A))
    assert r.status_code in (200, 201) and r.json()["content_option"] == option


def test_response_and_wake_check_success(client, morning, clock):
    """확인 멘트에 모두 답하고(wake-check) 스누즈·재기상이 없으면 기상 성공, 시각은 첫 응답."""
    v = start(client, morning).json()
    clock.now = datetime(2026, 11, 6, 7, 2, tzinfo=KST)
    first = client.post(url(v, "/response"), headers=auth(USER_A)).json()["first_response_at"]
    clock.now = datetime(2026, 11, 6, 7, 4, tzinfo=KST)
    assert client.post(url(v, "/response"), headers=auth(USER_A)).json()["first_response_at"] == first   # 처음 한 번만
    s = client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]
    assert s["wake_success"] is True and s["wake_checked_at"].startswith("2026-11-06T07:04") and s["first_response_at"] == first
    client.post(url(v, "/rewake"), headers=auth(USER_A))                         # 판정 뒤의 재기상은 상관없음
    s = client.post(url(v, "/end"), headers=auth(USER_A)).json()["session"]
    assert s["wake_success"] is True and s["session_end_at"].startswith("2026-11-06T07:04")


def test_rewake_before_check_fails(client, morning):
    v = start(client, morning).json()
    assert client.post(url(v, "/rewake"), headers=auth(USER_A)).json() == {"rewake_count": 1}
    client.post(url(v, "/response"), headers=auth(USER_A))
    s = client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]
    assert s["wake_success"] is False and s["wake_checked_at"] is not None       # 실패해도 판정 시각은 남음
    assert client.post(url(v, "/coaching-token"), headers=auth(USER_A)).status_code == 200   # 브리핑·루틴은 그대로 진행


def test_snooze_fails_and_reuses_session(client, morning, clock):
    v = start(client, morning).json()
    assert client.post(url(v, "/snooze"), headers=auth(USER_A)).json() == {"snooze_count": 1}
    assert client.post(url(v, "/snooze"), headers=auth(USER_A)).json() == {"snooze_count": 2}   # 횟수 제한 없음
    clock.now = datetime(2026, 11, 6, 7, 10, tzinfo=KST)                         # 5분 뒤 다시 울림 → 같은 세션
    again = start(client, morning).json()["session"]
    assert again["session_id"] == v["session"]["session_id"] and again["alarm_rang_at"] == v["session"]["alarm_rang_at"]
    client.post(url(v, "/response"), headers=auth(USER_A))
    assert client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]["wake_success"] is False


def test_wake_check_is_judged_once(client, morning):
    v = start(client, morning).json()
    client.post(url(v, "/response"), headers=auth(USER_A))
    assert client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]["wake_success"] is True
    client.post(url(v, "/snooze"), headers=auth(USER_A))
    assert client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]["wake_success"] is True


def test_wake_check_fills_missing_first_response(client, morning):
    v = start(client, morning).json()
    s = client.post(url(v, "/wake-check"), headers=auth(USER_A)).json()["session"]
    assert s["wake_success"] is True and s["first_response_at"] == s["wake_checked_at"]


def test_end_without_wake_check_is_failure(client, morning):
    v = start(client, morning).json()
    client.post(url(v, "/response"), headers=auth(USER_A))                       # 응답은 했지만 확인 멘트를 끝내지 못함
    s = client.post(url(v, "/end"), headers=auth(USER_A)).json()["session"]
    assert s["wake_success"] is False and s["wake_checked_at"] is None


def test_today_and_isolation(client, morning):
    assert client.get("/morning/sessions/today", headers=auth(USER_A)).status_code == 404
    v = start(client, morning).json()
    assert client.get("/morning/sessions/today", headers=auth(USER_A)).json()["session"]["session_id"] == v["session"]["session_id"]
    assert client.get(url(v), headers=auth(USER_B)).status_code == 404
    assert client.post(url(v, "/coaching-token"), headers=auth(USER_B)).status_code == 404
    assert client.post(url(v, "/snooze"), headers=auth(USER_B)).status_code == 404
    assert client.post(url(v, "/wake-check"), headers=auth(USER_B)).status_code == 404
    assert start(client, morning, user=USER_B).status_code == 404


def test_steps_use_last_night_tomorrow_only_changes(client, morning, clock, ai):
    items = client.get(f"/alarms/{morning['alarm_id']}/routine-items", headers=auth(USER_A)).json()["items"]
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    ai.routine = RoutineChangeRequest([ItemChange(items[3]["template_item_id"], 0), ItemChange(items[0]["template_item_id"], 60)])
    client.post("/night/transcript", json={"transcript": TALK}, headers=auth(USER_A))
    clock.now = FRIDAY_7
    v = start(client, morning).json()
    steps = client.post(url(v, "/coaching-token"), headers=auth(USER_A)).json()["steps"]
    assert [s["step_name"] for s in steps] == ["스트레칭 (축소)", "햇빛 보기", "물 한 잔", "세수·양치", "오늘 할 일 3개 말하기"]
    assert steps[0]["planned_duration_sec"] == 60
    # 다음 날(토)은 알람 설정 그대로
    clock.now = datetime(2026, 11, 7, 7, 0, tzinfo=KST)
    v2 = start(client, morning).json()
    assert len(client.post(url(v2, "/coaching-token"), headers=auth(USER_A)).json()["steps"]) == 6


def test_today_duration_change(client, morning):
    v = start(client, morning).json()
    client.post(url(v, "/coaching-token"), headers=auth(USER_A))
    r = client.patch(url(v, "/steps/1/duration"), json={"duration_sec": 60}, headers=auth(USER_A)).json()
    assert r["steps"][0]["planned_duration_sec"] == 60 and r["session"]["planned_total_sec"] == 480
    r = client.patch(url(v, "/steps/4/duration"), json={"duration_sec": 0}, headers=auth(USER_A)).json()   # 오늘만 빼기
    assert r["steps"][3]["step_status"] == "SKIPPED" and r["session"]["planned_total_sec"] == 420
    over = client.patch(url(v, "/steps/2/duration"), json={"duration_sec": 600}, headers=auth(USER_A))    # 420-60+600 = 960
    assert over.status_code == 422 and over.json()["error"]["code"] == "ROUTINE_OVER_LIMIT"
    assert client.patch(url(v, "/steps/2/duration"), json={"duration_sec": 45}, headers=auth(USER_A)).status_code == 422
    done = client.patch(url(v, "/steps/4/duration"), json={"duration_sec": 60}, headers=auth(USER_A))     # 이미 체크한 단계
    assert done.status_code == 409 and done.json()["error"]["code"] == "STEP_ALREADY_CHECKED"
    assert client.patch(url(v, "/steps/9/duration"), json={"duration_sec": 60}, headers=auth(USER_A)).status_code == 404
    assert client.patch(url(v, "/steps/1/duration"), json={"duration_sec": 60}, headers=auth(USER_B)).status_code == 404
    alarm = client.get(f"/alarms/{morning['alarm_id']}/routine-items", headers=auth(USER_A)).json()
    assert alarm["total_sec"] == 600                                                        # 알람 설정은 그대로
