"""11/16 성격·강도·영어 수준 설정과 스트레칭 부위 교체 (DB 12_persona_stretch.sql, AI CONTRACTS B17)."""
from dataclasses import replace
from datetime import time

import pytest

from backend.core.errors import ValidationFailed
from backend.core.timeutil import now_kst
from backend.domain.models import SessionSnapshot, StepPlan

from .conftest import USER_A, USER_B, auth
from .test_api_users import onboard

H = auth(USER_A)
NEW_APP = {"personality": "CHEERFUL", "intensity": 3, "auto_intensity": False, "english_level": "INTERMEDIATE"}


def _persona(body: dict) -> tuple:
    return tuple(body[k] for k in ("personality", "intensity", "auto_intensity", "english_level"))


def test_onboarding_with_new_persona_fields(client):
    body = {k: v for k, v in NEW_APP.items()}
    r = onboard(client, **body)                                   # 지금 앱은 speech_style 없이 새 칸을 보낸다
    assert r.status_code == 201
    assert _persona(r.json()["setting"]) == ("CHEERFUL", 3, False, "INTERMEDIATE")


def test_onboarding_old_app_maps_speech_style(client):
    r = onboard(client, speech_style="FIRM")                      # 예전 앱: 성격은 말투에서 (FIRM → STRICT)
    assert _persona(r.json()["setting"]) == ("STRICT", 2, True, "BEGINNER")


def test_patch_new_persona_fields(client):
    onboard(client)
    r = client.patch("/settings", json=NEW_APP, headers=H)
    assert r.status_code == 200 and _persona(r.json()) == ("CHEERFUL", 3, False, "INTERMEDIATE")
    assert _persona(client.get("/settings", headers=H).json()) == ("CHEERFUL", 3, False, "INTERMEDIATE")
    for bad in ({"personality": "FIRM"}, {"intensity": 0}, {"intensity": 4}, {"english_level": "ADVANCED"}):
        assert client.patch("/settings", json=bad, headers=H).status_code == 422, bad


def test_patch_old_speech_style_moves_personality(client):
    onboard(client)
    r = client.patch("/settings", json={"speech_style": "FIRM"}, headers=H)
    assert (r.json()["speech_style"], r.json()["personality"]) == ("FIRM", "STRICT")
    r = client.patch("/settings", json={"speech_style": "KIND", "personality": "CHEERFUL"}, headers=H)
    assert r.json()["personality"] == "CHEERFUL"                  # 둘 다 오면 성격 값을 따른다


def test_memory_replace_pending_steps_matches_db(repos):
    """test_supabase_repos.test_stretch_area_and_intensity와 같은 시나리오."""
    a = repos.alarms.create_alarm(USER_A, {"alarm_time": time(8, 0), "morning_call_type": "CONTENT",
                                           "content_type": "EXERCISE", "content_option": "STRETCH"})
    snap = SessionSnapshot(USER_A, a.alarm_id, now_kst().date(), "CONTENT", None, "EXERCISE", "STRETCH", time(8, 0), time(8, 0))
    s = repos.sessions.get_or_create_session(snap)
    full = [StepPlan(n, name, None, sec) for n, (name, sec) in
            enumerate([("누워서 기지개", 40), ("무릎 좌우로 넘기기", 65), ("무릎 가슴으로 당기기", 65)], 1)]
    neck = [StepPlan(1, name, None, sec, step_detail={"area": "NECK_SHOULDER"}) for name, sec in
            [("턱 당기기", 40), ("고개 좌우로 돌리기", 65), ("팔 열어 가슴 펴기", 65)]]

    repos.sessions.create_steps(USER_A, s.session_id, full)
    steps = repos.sessions.replace_pending_steps(USER_A, s.session_id, neck)
    assert [(p.step_order, p.step_name) for p in steps] == [(1, "턱 당기기"), (2, "고개 좌우로 돌리기"), (3, "팔 열어 가슴 펴기")]
    assert steps[0].step_detail == {"area": "NECK_SHOULDER"} and steps[0].planned_duration_sec == 40

    repos.sessions.save_step(USER_A, replace(steps[0], step_status="DONE", checked_at=now_kst(), ai_verdict="done"))
    steps = repos.sessions.replace_pending_steps(USER_A, s.session_id, full)
    assert [(p.step_order, p.step_name, p.step_status) for p in steps] == [
        (1, "턱 당기기", "DONE"), (2, "누워서 기지개", "PENDING"), (3, "무릎 좌우로 넘기기", "PENDING"), (4, "무릎 가슴으로 당기기", "PENDING")]
    assert repos.sessions.replace_pending_steps(USER_B, s.session_id, neck) is None
    assert len(repos.sessions.list_steps(USER_A, s.session_id)) == 4
    with pytest.raises(ValidationFailed):
        repos.sessions.replace_pending_steps(USER_A, s.session_id, [])

    s = repos.sessions.update_session(USER_A, s.session_id, {"applied_intensity": 3, "stretch_area": "NECK_SHOULDER"})
    assert (s.applied_intensity, s.stretch_area) == (3, "NECK_SHOULDER")
    for bad in ({"applied_intensity": 4}, {"stretch_area": "HEAD"}):
        with pytest.raises(ValidationFailed):
            repos.sessions.update_session(USER_A, s.session_id, bad)
