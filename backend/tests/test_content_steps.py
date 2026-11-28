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


def test_game_score_saved_with_best(client, clock):
    """게임 기록: 앱이 센 주고받은 횟수. 같은 게임끼리 최고 기록, 이어 하기로 다시 보내면 바뀐다."""
    onboard(client)
    game = content_alarm(client, "06:30", "QUIZ", "WORDCHAIN")
    clock.now = datetime(2026, 11, 6, 6, 30, tzinfo=KST)
    v = run(client, game)
    put = lambda view, order, score, user=USER_A: client.put(url(view, f"/steps/{order}/score"), json={"score": score},   # noqa: E731
                                                             headers=auth(user))
    assert put(v, 1, 7).json() == {"step_order": 1, "score": 7, "best_score": 7, "is_best": True, "user_wins": 0}
    clock.now = datetime(2026, 11, 7, 6, 30, tzinfo=KST)
    w = run(client, game)
    assert put(w, 1, 4).json() == {"step_order": 1, "score": 4, "best_score": 7, "is_best": False, "user_wins": 0}
    assert put(w, 1, 9).json()["is_best"] is True                                  # 이어 하기로 늘어남
    rows = client.get("/contents/steps?date=2026-11-07", headers=H).json()["steps"]
    assert (rows[0]["score"], rows[0]["words"]) == (9, [])                         # 단어는 저장하지 않는다

    assert put(w, 1, -1).status_code == 422
    assert put(w, 9, 1).json()["error"]["code"] == "STEP_NOT_FOUND"
    assert put(w, 1, 1, USER_B).status_code == 404
    quiz = content_alarm(client, "06:45", "EXERCISE", "STRETCH")
    clock.now = datetime(2026, 11, 7, 6, 45, tzinfo=KST)
    r = put(run(client, quiz), 1, 3)
    assert r.status_code == 422 and r.json()["error"]["code"] == "NOT_GAME_SESSION"


def test_wordchain_winner_counts_user_wins(client, clock):
    """11/28 앱 요청: 끝말잇기 점수와 함께 승패(winner)를 받아 저장하고, 넥스티를 이긴 누적 횟수(user_wins)를 돌려준다.
    같은 세션을 다시 보내도 한 번만 세고, 승패를 안 보내면 이전 값을 그대로 둔다. 끝말잇기가 아니면 승패를 받지 않는다."""
    onboard(client)
    game = content_alarm(client, "06:30", "QUIZ", "WORDCHAIN")
    put = lambda view, body: client.put(url(view, "/steps/1/score"), json=body, headers=H)   # noqa: E731
    clock.now = datetime(2026, 11, 6, 6, 30, tzinfo=KST)
    v = run(client, game)
    assert put(v, {"score": 5, "winner": "USER"}).json()["user_wins"] == 1
    assert put(v, {"score": 8, "winner": "USER"}).json()["user_wins"] == 1         # 같은 세션은 한 번만
    assert put(v, {"score": 9}).json()["user_wins"] == 1                           # 승패 없이 이어 하기 → 그대로
    clock.now = datetime(2026, 11, 7, 6, 30, tzinfo=KST)
    w = run(client, game)
    assert put(w, {"score": 3, "winner": "NEXTY"}).json()["user_wins"] == 1
    assert put(w, {"score": 6, "winner": "USER"}).json()["user_wins"] == 2         # 이어 해서 이김
    assert put(w, {"score": 6, "winner": "DRAW"}).status_code == 422
    other = content_alarm(client, "06:45", "QUIZ", "PERSON")
    clock.now = datetime(2026, 11, 7, 6, 45, tzinfo=KST)
    q = run(client, other)
    assert put(q, {"score": 2}).json()["user_wins"] is None                        # 끝말잇기가 아니면 null
    r = put(q, {"score": 2, "winner": "USER"})
    assert r.status_code == 422 and r.json()["error"]["code"] == "NOT_WORDCHAIN"
