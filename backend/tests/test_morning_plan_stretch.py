"""11/16 아침 순서표·스트레칭 부위·그날 강도·영어 수준 (AI CONTRACTS B15·B17, DB 12_persona_stretch.sql)."""
from datetime import date, datetime, time, timedelta

from backend.clients.ai_gateway import NightExtract
from backend.core.timeutil import KST
from backend.domain.models import SessionSnapshot

from .conftest import USER_A, USER_B, auth
from .test_api_morning import FRIDAY_7, start, url
from .test_api_night import TALK
from .test_api_users import onboard

H = auth(USER_A)
AREAS = ["FULL", "NECK_SHOULDER", "ARM_WRIST", "BACK_HIP", "LEG"]


def stretch_alarm(client, clock):
    onboard(client)
    clock.now = FRIDAY_7
    return client.post("/alarms", json={"alarm_time": "06:30", "morning_call_type": "CONTENT", "content_type": "EXERCISE",
                                        "content_option": "STRETCH"}, headers=H).json()


def last(ai, kind):
    return next(c[1] for c in reversed(ai.calls) if c[0] == kind)


# ---------- 순서표 (B15) ----------
def test_start_returns_morning_plan_and_passes_it_to_ai(client, clock, ai):
    alarm = stretch_alarm(client, clock)
    v = start(client, alarm).json()
    assert v["morning_plan"] == ["BRIEFING", "COACHING", "FORTUNE"]           # 어젯밤 한마디 녹음이 없으면 ONE_LINER 없음
    client.post(url(v, "/briefing-token"), json={}, headers=H)
    ctx = last(ai, "briefing_token")
    assert ctx.plan == ("BRIEFING", "COACHING", "FORTUNE") and ctx.already_done is False
    client.post(url(v, "/coaching-token"), headers=H)
    assert last(ai, "coaching_token").plan == ("BRIEFING", "COACHING", "FORTUNE")
    client.post(url(v, "/fortune-token"), headers=H)
    assert ai.fortune_plan == ("BRIEFING", "COACHING", "FORTUNE")
    assert client.get(url(v), headers=H).json()["morning_plan"] is None        # 세션 시작 응답에만


def test_plan_with_one_liner_and_skipped_coaching(client, clock, ai, repos):
    alarm = stretch_alarm(client, clock)
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    ai.extract = NightExtract("DONE", {"override_skip_coaching": True})
    nid = client.post("/night/transcript", json={"transcript": TALK}, headers=H).json()["night_record_id"]
    repos.nights.set_voice_path(USER_A, nid)
    clock.now = FRIDAY_7
    v = start(client, alarm).json()
    assert v["morning_plan"] == ["BRIEFING", "ONE_LINER", "FORTUNE"] and v["stretch_routines"] is None


def test_already_done_day(client, clock, ai):
    """다 마친 뒤 다시 들어오면 순서표에서 코칭을 빼고 브리핑은 칭찬만 (B15 already_done)."""
    alarm = stretch_alarm(client, clock)
    v = start(client, alarm).json()
    steps = client.post(url(v, "/coaching-token"), headers=H).json()["steps"]
    for s in steps:
        client.post(url(v, f"/steps/{s['step_order']}/verdict"), json={"verdict": "done"}, headers=H)
    v = start(client, alarm).json()
    assert v["morning_plan"] == ["BRIEFING", "FORTUNE"] and v["stretch_routines"] is None
    client.post(url(v, "/briefing-token"), json={}, headers=H)
    assert last(ai, "briefing_token").already_done is True


# ---------- 스트레칭 부위 (B17) ----------
def test_start_gives_area_routines_and_coaching_asks_area(client, clock, ai):
    v = start(client, stretch_alarm(client, clock)).json()
    routines = v["stretch_routines"]
    assert list(routines) == AREAS and [s["step_order"] for s in routines["LEG"]] == [1, 2, 3, 4, 5]
    assert sum(s["planned_duration_sec"] for s in routines["LEG"]) == 300 and routines["LEG"][0]["response_type"] == "PLAY"
    client.post(url(v, "/coaching-token"), headers=H)
    ctx = last(ai, "coaching_token")
    assert [a for a, _ in ctx.stretch_routines] == AREAS and ctx.stretch_routines[1][1][0].name == "NECK_SHOULDER 동작 1"


def test_select_area_replaces_steps_and_locks(client, clock, ai, repos):
    v = start(client, stretch_alarm(client, clock)).json()
    client.post(url(v, "/coaching-token"), headers=H)
    r = client.post(url(v, "/stretch-area"), json={"area": "NECK_SHOULDER"}, headers=H)
    assert r.status_code == 200
    body = r.json()
    assert body["session"]["stretch_area"] == "NECK_SHOULDER" and body["session"]["planned_total_sec"] == 300
    assert [(s["step_order"], s["step_name"]) for s in body["steps"]][:2] == [(1, "NECK_SHOULDER 동작 1"), (2, "NECK_SHOULDER 동작 2")]
    assert len(body["steps"]) == 5
    assert repos.sessions.list_steps(USER_A, v["session"]["session_id"])[0].step_detail == {
        "response_type": "PLAY", "script": "NECK_SHOULDER 안내 1", "area": "NECK_SHOULDER"}
    assert client.post(url(v, "/stretch-area"), json={"area": "NECK_SHOULDER"}, headers=H).status_code == 200   # 재시도
    r = client.post(url(v, "/stretch-area"), json={"area": "LEG"}, headers=H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "STRETCH_AREA_LOCKED"                        # 이미 골랐음

    client.post(url(v, "/coaching-token"), headers=H)                         # 재연결: 다시 묻지 않고 고른 루틴으로
    ctx = last(ai, "coaching_token")
    assert ctx.stretch_routines == () and ctx.steps[0].name == "NECK_SHOULDER 동작 1" and ctx.steps[0].script == "NECK_SHOULDER 안내 1"


def test_select_area_after_verdict_is_locked(client, clock):
    v = start(client, stretch_alarm(client, clock)).json()
    client.post(url(v, "/coaching-token"), headers=H)
    client.post(url(v, "/steps/1/verdict"), json={"verdict": "done"}, headers=H)
    r = client.post(url(v, "/stretch-area"), json={"area": "LEG"}, headers=H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "STRETCH_AREA_LOCKED"


def test_select_area_checks(client, clock):
    alarm = stretch_alarm(client, clock)
    v = start(client, alarm).json()
    assert client.post(url(v, "/stretch-area"), json={"area": "HEAD"}, headers=H).status_code == 422
    assert client.post(url(v, "/stretch-area"), json={"area": "LEG"}, headers=auth(USER_B)).status_code == 404
    r = client.post(url(v, "/stretch-area"), json={"area": "LEG"}, headers=H)           # 단계가 아직 없으면 만들고 바꾼다
    assert r.status_code == 200 and r.json()["steps"][0]["step_name"] == "LEG 동작 1"
    routine_alarm = next(a for a in client.get("/alarms", headers=H).json() if a["morning_call_type"] == "ROUTINE")
    routine = start(client, routine_alarm).json()                                        # 루틴 알람에는 부위가 없다
    assert routine["stretch_routines"] is None
    r = client.post(url(routine, "/stretch-area"), json={"area": "LEG"}, headers=H)
    assert r.status_code == 409


# ---------- 강도·성격 ----------
def test_applied_intensity_saved_once_and_sent_to_ai(client, clock, ai):
    alarm = stretch_alarm(client, clock)
    client.patch("/settings", json={"personality": "STRICT", "intensity": 1}, headers=H)
    ai.intensity = 3                                                            # 자동 강도가 올렸다고 가정
    v = start(client, alarm).json()
    assert v["session"]["applied_intensity"] == 3
    ai.intensity = 1
    assert start(client, alarm).json()["session"]["applied_intensity"] == 3    # 재연결은 처음 정한 값
    client.post(url(v, "/briefing-token"), json={}, headers=H)
    p = last(ai, "briefing_token").persona
    assert (p.personality, p.intensity, p.auto_intensity) == ("STRICT", 3, True)
    assert last(ai, "decide")[3] == "STRICT"                                   # 전략 결정에도 성격


def test_auto_intensity_off_uses_setting(client, clock, ai):
    alarm = stretch_alarm(client, clock)
    client.patch("/settings", json={"intensity": 2, "auto_intensity": False}, headers=H)
    ai.intensity = 3
    assert start(client, alarm).json()["session"]["applied_intensity"] == 2


def test_intensity_history_is_past_sessions_oldest_first(client, clock, ai, repos):
    alarm = stretch_alarm(client, clock)
    for d in (date(2026, 11, 4), date(2026, 11, 2)):
        s = repos.sessions.get_or_create_session(SessionSnapshot(USER_A, alarm["alarm_id"], d, "CONTENT", None, "EXERCISE",
                                                                 "STRETCH", time(6, 30), time(6, 30)))
        repos.sessions.update_session(USER_A, s.session_id, {"alarm_rang_at": datetime.combine(d, time(6, 30), KST)})
    start(client, alarm)
    assert last(ai, "intensity") == (2, 2, True)


def test_intensity_failure_does_not_block_alarm(client, clock, ai):
    alarm = stretch_alarm(client, clock)

    def boom(*_):
        raise RuntimeError("AI 오류")
    ai.today_intensity = boom
    r = start(client, alarm)
    assert r.status_code == 200 and r.json()["session"]["applied_intensity"] == 2


def test_night_persona_has_personality(client, ai):
    onboard(client, personality="CHEERFUL", intensity=1)
    client.post("/night/token", json={}, headers=H)
    p = last(ai, "night_token").persona
    assert (p.personality, p.intensity) == ("CHEERFUL", 1)


# ---------- 영어 수준 ----------
def test_english_level_and_today_story(client, clock, ai):
    onboard(client, english_level="INTERMEDIATE")
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    ai.extract = NightExtract("DONE", {"tomorrow_goal": "발표 잘하기", "tomorrow_notes": ["10시 회의"]})
    client.post("/night/transcript", json={"transcript": TALK}, headers=H)
    clock.now = FRIDAY_7 - timedelta(minutes=30)
    alarm = client.post("/alarms", json={"alarm_time": "06:30", "morning_call_type": "CONTENT", "content_type": "LANGUAGE",
                                         "content_option": "ENGLISH"}, headers=H).json()
    v = start(client, alarm).json()
    assert v["stretch_routines"] is None
    client.post(url(v, "/coaching-token"), headers=H)
    assert ai.content_extra == ("INTERMEDIATE", "발표 잘하기 / 10시 회의")
