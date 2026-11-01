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


def test_users_are_isolated(client):
    onboard(client)
    assert client.get("/users/me", headers=auth(USER_B)).json()["onboarding_done"] is False
    assert client.patch("/settings", json={"theme": "DARK"}, headers=auth(USER_B)).status_code == 404
