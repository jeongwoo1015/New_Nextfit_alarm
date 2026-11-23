"""콘텐츠 화면 GET /contents/steps?date= — 앱 RespecApi.contentSteps가 기다리던 형식."""
from dataclasses import replace
from datetime import datetime

from backend.core.timeutil import KST

from .conftest import USER_A, USER_B, auth
from .test_api_morning import start, url
from .test_api_users import onboard

H = auth(USER_A)


def content_alarm(client, time_, content_type, option):
    return client.post("/alarms", json={"alarm_time": time_, "morning_call_type": "CONTENT", "content_type": content_type,
                                        "content_option": option}, headers=H).json()


def run(client, alarm):
    v = start(client, alarm).json()
    client.post(url(v, "/coaching-token"), headers=H)
    return v


def test_content_steps_of_the_day(client, clock, repos):
    onboard(client)                                                                # 루틴 알람(07:00)은 콘텐츠가 아니라 빠진다
    quiz = content_alarm(client, "06:30", "QUIZ", "PERSON")
    stretch = content_alarm(client, "06:40", "EXERCISE", "STRETCH")
    clock.now = datetime(2026, 11, 6, 6, 30, tzinfo=KST)
    q = run(client, quiz)
    client.post(url(q, "/steps/1/verdict"), json={"verdict": "done"}, headers=H)
    repos.sessions.set_step_score(USER_A, q["session"]["session_id"], 2, 7)
    clock.now = datetime(2026, 11, 6, 6, 40, tzinfo=KST)
    s = run(client, stretch)
    client.post(url(s, "/stretch-area"), json={"area": "NECK"}, headers=H)

    r = client.get("/contents/steps?date=2026-11-06", headers=H).json()
    assert r["date"] == "2026-11-06"
    quiz_rows = [x for x in r["steps"] if x["content_type"] == "QUIZ"]
    assert [x["step_order"] for x in quiz_rows] == [1, 2, 3]
    assert quiz_rows[0]["step_status"] == "DONE" and quiz_rows[0]["script"].startswith("문제:")
    assert (quiz_rows[1]["score"], quiz_rows[1]["words"], quiz_rows[1]["content_option"]) == (7, [], "PERSON")
    stretch_rows = [x for x in r["steps"] if x["content_type"] == "EXERCISE"]
    assert stretch_rows and all(x["stretch_area"] == "NECK" and x["script"] for x in stretch_rows)

    assert client.get("/contents/steps?date=2026-11-05", headers=H).json()["steps"] == []
    assert client.get("/contents/steps?date=2026-11-06", headers=auth(USER_B)).json()["steps"] == []
    assert client.get("/contents/steps?date=2026-13-01", headers=H).status_code == 422
    assert client.get("/contents/steps", headers=H).status_code == 422


def test_saved_words_are_returned(client, clock, repos):
    onboard(client)
    game = content_alarm(client, "06:30", "QUIZ", "WORDCHAIN")
    clock.now = datetime(2026, 11, 6, 6, 30, tzinfo=KST)
    v = run(client, game)
    step = repos.sessions.list_steps(USER_A, v["session"]["session_id"])[0]
    repos.sessions.steps[step.step_id] = replace(step, step_detail={**(step.step_detail or {}), "words": ["기차", "차표"]})
    rows = client.get("/contents/steps?date=2026-11-06", headers=H).json()["steps"]
    assert rows[0]["words"] == ["기차", "차표"]
