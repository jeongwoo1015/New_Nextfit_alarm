from dataclasses import replace

from backend.repositories.seed import template_id

from .conftest import USER_A, USER_B, auth

ONBOARDING = {
    "user_name": "정우", "birth_date": "2000-03-15", "gender": "MALE",
    "user_call_name": "정우님", "tone": "CASUAL", "speech_style": "FRIENDLY", "voice_type": "MALE", "music_genre": "JAZZ",
    "morning_call_type": "ROUTINE", "template_id": template_id("N"),
}


def onboard(client, user=USER_A, **over):
    return client.post("/onboarding", json={**ONBOARDING, **over}, headers=auth(user))


def test_before_onboarding(client):
    r = client.get("/users/me", headers=auth(USER_A))
    assert r.json() == {"onboarding_done": False, "user": None, "setting": None}
    assert client.get("/settings", headers=auth(USER_A)).status_code == 404


def test_onboarding_creates_user_setting_and_first_alarm(client):
    r = onboard(client)
    assert r.status_code == 201
    body = r.json()
    assert body["onboarding_done"] and body["user"]["user_name"] == "정우"
    assert body["setting"]["tone"] == "CASUAL" and body["setting"]["sleep_time"] == "23:00"
    alarm = body["first_alarm"]
    assert (alarm["alarm_time"], alarm["morning_call_type"], alarm["content_type"]) == ("07:00", "ROUTINE", None)
    assert alarm["template_id"] == template_id("N")
    assert client.get("/users/me", headers=auth(USER_A)).json()["onboarding_done"] is True


def test_onboarding_with_content_alarm(client):
    r = onboard(client, morning_call_type="CONTENT", template_id=None, content_type="QUIZ")
    assert r.status_code == 201
    alarm = r.json()["first_alarm"]
    assert (alarm["morning_call_type"], alarm["content_type"], alarm["content_option"], alarm["template_id"]) == ("CONTENT", "QUIZ", None, None)
    assert onboard(client, user=USER_B, morning_call_type="CONTENT", content_type="QUIZ").status_code == 422   # 프리셋도 보냄


def test_onboarding_twice_is_conflict(client):
    onboard(client)
    r = onboard(client)
    assert r.status_code == 409 and r.json()["error"]["code"] == "ONBOARDING_DONE"


def test_onboarding_resumes_without_duplicate_alarm(client, repos):
    onboard(client)
    repos.users.users[USER_A] = replace(repos.users.users[USER_A], onboarding_done=False)   # 중간 실패를 흉내
    r = onboard(client, user_name="정우2")
    assert r.status_code == 201 and r.json()["first_alarm"] is None
    assert len(client.get("/alarms", headers=auth(USER_A)).json()) == 1


def test_onboarding_rejects_bad_input(client):
    assert onboard(client, template_id="nope").status_code == 422
    r = onboard(client, tone="POLITE")
    assert r.status_code == 422 and r.json()["error"]["details"][0]["field"] == "tone"
    assert onboard(client, template_id=None).status_code == 422                    # 루틴인데 프리셋 없음


def test_patch_settings_and_user(client):
    onboard(client)
    r = client.patch("/settings", json={"speech_style": "FIRM", "sleep_time": "22:30", "theme": "DARK"}, headers=auth(USER_A))
    assert r.status_code == 200
    assert (r.json()["speech_style"], r.json()["sleep_time"], r.json()["theme"], r.json()["tone"]) == ("FIRM", "22:30", "DARK", "CASUAL")
    assert client.patch("/settings", json={"music_genre": "ROCK"}, headers=auth(USER_A)).status_code == 422
    assert client.patch("/settings", json={"coaching_min": 10}, headers=auth(USER_A)).status_code == 422   # 알람 쪽 값
    r = client.patch("/users/me", json={"gender": "NONE"}, headers=auth(USER_A))
    assert r.status_code == 200 and r.json()["gender"] == "NONE"


def test_wake_volume_settings_can_be_cleared(client):
    """기상 볼륨 두 칸(11/22)만 null로 비울 수 있고, 다른 칸의 null은 무시한다."""
    onboard(client)
    s = client.get("/settings", headers=auth(USER_A)).json()
    assert (s["wake_max_start_level"], s["wake_next_level"]) == (None, None)
    r = client.patch("/settings", json={"wake_max_start_level": 3, "wake_next_level": 4}, headers=auth(USER_A)).json()
    assert (r["wake_max_start_level"], r["wake_next_level"]) == (3, 4)
    r = client.patch("/settings", json={"wake_next_level": None, "tone": None}, headers=auth(USER_A)).json()
    assert (r["wake_max_start_level"], r["wake_next_level"], r["tone"]) == (3, None, "CASUAL")
    for bad in ({"wake_next_level": 6}, {"wake_max_start_level": 0}):
        assert client.patch("/settings", json=bad, headers=auth(USER_A)).status_code == 422


def test_ai_change_setting(client):
    """대화로 설정 바꾸기: AI update_setting 인자를 그대로 받는다."""
    onboard(client)
    post = lambda body, user=USER_A: client.post("/settings/ai-change", json=body, headers=auth(user))   # noqa: E731
    assert post({"field": "music_genre", "value": "classic", "scope": "ALWAYS"}).json()["music_genre"] == "CLASSIC"
    assert post({"field": "personality", "value": "STRICT"}).json()["personality"] == "STRICT"
    assert post({"field": "intensity", "value": "3"}).json()["intensity"] == 3
    r = post({"field": "wake_volume", "value": "2", "scope": "ALWAYS"}).json()
    assert (r["wake_max_start_level"], r["wake_next_level"]) == (2, None)                # 앞으로의 상한
    r = post({"field": "wake_volume", "value": "4", "scope": "TODAY"}).json()
    assert (r["wake_max_start_level"], r["wake_next_level"]) == (2, 4)                   # 다음 알람 한 번
    assert post({"field": "wake_volume", "value": "1"}).json()["wake_next_level"] == 1   # scope 없으면 TODAY
    for bad in ({"field": "music_genre", "value": "ROCK"}, {"field": "intensity", "value": "4"},
                {"field": "wake_volume", "value": "6", "scope": "TODAY"}, {"field": "personality", "value": "다정한"}):
        res = post(bad)
        assert res.status_code == 422 and res.json()["error"]["code"] == "SETTING_VALUE_INVALID"
    for bad in ({"field": "tone", "value": "CASUAL"}, {"field": "intensity", "value": "2", "scope": "FOREVER"}):
        assert post(bad).status_code == 422                                          # 대화로 바꿀 수 없는 칸·범위
    assert post({"field": "intensity", "value": "1"}, USER_B).status_code == 404


def test_users_are_isolated(client):
    onboard(client)
    assert client.get("/users/me", headers=auth(USER_B)).json()["onboarding_done"] is False
    assert client.patch("/settings", json={"theme": "DARK"}, headers=auth(USER_B)).status_code == 404
