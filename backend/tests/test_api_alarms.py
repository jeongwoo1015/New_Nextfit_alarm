from dataclasses import replace

from backend.repositories.seed import template_id

from .conftest import USER_A, USER_B, auth

ROUTINE = {"alarm_time": "06:30", "morning_call_type": "ROUTINE", "template_id": template_id("Q"), "routine_mode": "CUSTOM",
           "repeat_days": "1111100", "label": "평일 기상"}
CONTENT = {"alarm_time": "08:00", "morning_call_type": "CONTENT", "content_type": "LANGUAGE", "content_option": "ENGLISH",
           "coaching_min": 10, "repeat_days": "0000011"}


def create(client, body, user=USER_A):
    return client.post("/alarms", json=body, headers=auth(user))


def test_create_list_get(client):
    a = create(client, ROUTINE).json()
    c = create(client, CONTENT).json()
    assert (a["alarm_time"], a["coaching_min"], a["enabled"], a["alarm_mode"]) == ("06:30", 20, True, "ON_TIME")
    assert c["template_id"] is None and c["content_option"] == "ENGLISH"
    listed = client.get("/alarms", headers=auth(USER_A)).json()
    assert [x["alarm_id"] for x in listed] == [a["alarm_id"], c["alarm_id"]]       # 시각 순
    assert client.get(f"/alarms/{a['alarm_id']}", headers=auth(USER_A)).json()["label"] == "평일 기상"


def test_create_rejects_rule_violations(client):
    assert create(client, {**ROUTINE, "content_type": "QUIZ"}).status_code == 422
    assert create(client, {**ROUTINE, "template_id": None}).status_code == 422
    assert create(client, {**ROUTINE, "template_id": "unknown"}).status_code == 422
    assert create(client, {**CONTENT, "content_option": "HISTORY"}).status_code == 422
    assert create(client, {**ROUTINE, "repeat_days": "1111"}).status_code == 422
    assert create(client, {**ROUTINE, "label": "가" * 21}).status_code == 422
    assert create(client, {**ROUTINE, "user_id": USER_B}).status_code == 422          # 다른 사용자 지정 불가


def test_toggle_and_switch_type(client):
    a = create(client, ROUTINE).json()
    url = f"/alarms/{a['alarm_id']}"
    r = client.patch(url, json={"enabled": False}, headers=auth(USER_A))
    assert r.status_code == 200 and r.json()["enabled"] is False and r.json()["label"] == "평일 기상"
    bad = client.patch(url, json={"morning_call_type": "CONTENT", "content_type": "QUIZ"}, headers=auth(USER_A))
    assert bad.status_code == 422                                                    # 루틴 항목을 비우지 않음
    ok = client.patch(url, json={"morning_call_type": "CONTENT", "content_type": "QUIZ", "template_id": None,
                                 "routine_mode": None}, headers=auth(USER_A))
    assert ok.status_code == 200 and ok.json()["template_id"] is None
    assert client.patch(url, json={"alarm_time": None}, headers=auth(USER_A)).status_code == 422


def test_other_users_alarm_is_forbidden(client):
    a = create(client, ROUTINE).json()
    url = f"/alarms/{a['alarm_id']}"
    assert client.get(url, headers=auth(USER_B)).status_code == 403
    assert client.patch(url, json={"enabled": False}, headers=auth(USER_B)).status_code == 403
    assert client.delete(url, headers=auth(USER_B)).status_code == 403
    assert client.get("/alarms", headers=auth(USER_B)).json() == []


def test_delete(client):
    a = create(client, ROUTINE).json()
    assert client.delete(f"/alarms/{a['alarm_id']}", headers=auth(USER_A)).status_code == 204
    assert client.get(f"/alarms/{a['alarm_id']}", headers=auth(USER_A)).status_code == 404


def test_inactive_preset_cannot_be_newly_chosen_but_existing_alarm_keeps_it(client, repos):
    a = create(client, ROUTINE).json()
    q = template_id("Q")
    repos.templates.templates[q] = replace(repos.templates.templates[q], is_active=False)
    assert create(client, ROUTINE).status_code == 422
    r = client.patch(f"/alarms/{a['alarm_id']}", json={"alarm_time": "06:00"}, headers=auth(USER_A))
    assert r.status_code == 200
    assert client.patch(f"/alarms/{a['alarm_id']}", json={"template_id": template_id("N")}, headers=auth(USER_A)).status_code == 200
    assert client.patch(f"/alarms/{a['alarm_id']}", json={"template_id": q}, headers=auth(USER_A)).status_code == 422
