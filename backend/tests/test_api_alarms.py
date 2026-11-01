from dataclasses import replace

from backend.repositories.seed import template_id

from .conftest import USER_A, USER_B, auth

ROUTINE = {"alarm_time": "06:30", "morning_call_type": "ROUTINE", "template_id": template_id("Q"),
           "repeat_days": "1111100", "label": "평일 기상"}
CONTENT = {"alarm_time": "08:00", "morning_call_type": "CONTENT", "content_type": "LANGUAGE", "content_option": "ENGLISH",
           "repeat_days": "0000011"}


def create(client, body, user=USER_A):
    return client.post("/alarms", json=body, headers=auth(user))


def items_url(alarm):
    return f"/alarms/{alarm['alarm_id']}/routine-items"


def test_create_list_get(client):
    a = create(client, ROUTINE).json()
    c = create(client, CONTENT).json()
    assert (a["alarm_time"], a["enabled"], a["alarm_mode"], a["content_type"]) == ("06:30", True, "ON_TIME", None)
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
    assert create(client, {**ROUTINE, "coaching_min": 10}).status_code == 422      # 11/01 정의서에서 삭제된 값
    assert create(client, {**ROUTINE, "user_id": USER_B}).status_code == 422       # 다른 사용자 지정 불가


def test_toggle_and_switch_type(client):
    a = create(client, ROUTINE).json()
    url = f"/alarms/{a['alarm_id']}"
    r = client.patch(url, json={"enabled": False}, headers=auth(USER_A))
    assert r.status_code == 200 and r.json()["enabled"] is False and r.json()["label"] == "평일 기상"
    bad = client.patch(url, json={"morning_call_type": "CONTENT", "content_type": "QUIZ"}, headers=auth(USER_A))
    assert bad.status_code == 422                                                    # 프리셋을 비우지 않음
    ok = client.patch(url, json={"morning_call_type": "CONTENT", "content_type": "QUIZ", "template_id": None}, headers=auth(USER_A))
    assert ok.status_code == 200 and ok.json()["template_id"] is None
    assert client.patch(url, json={"alarm_time": None}, headers=auth(USER_A)).status_code == 422


def test_other_users_alarm_is_forbidden(client):
    a = create(client, ROUTINE).json()
    url = f"/alarms/{a['alarm_id']}"
    assert client.get(url, headers=auth(USER_B)).status_code == 403
    assert client.patch(url, json={"enabled": False}, headers=auth(USER_B)).status_code == 403
    assert client.delete(url, headers=auth(USER_B)).status_code == 403
    assert client.get(items_url(a), headers=auth(USER_B)).status_code == 403
    assert client.put(items_url(a), json={"items": []}, headers=auth(USER_B)).status_code == 403
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
    assert client.patch(f"/alarms/{a['alarm_id']}", json={"alarm_time": "06:00"}, headers=auth(USER_A)).status_code == 200
    assert client.patch(f"/alarms/{a['alarm_id']}", json={"template_id": template_id("N")}, headers=auth(USER_A)).status_code == 200
    assert client.patch(f"/alarms/{a['alarm_id']}", json={"template_id": q}, headers=auth(USER_A)).status_code == 422


# ---------- 알람 세부 설정: 루틴 항목 시간 ----------
def test_routine_items_default(client):
    a = create(client, ROUTINE).json()
    r = client.get(items_url(a), headers=auth(USER_A)).json()
    assert (r["total_sec"], r["max_sec"], r["template_id"]) == (600, 900, template_id("Q"))
    assert [(i["item_name"], i["duration_sec"], i["customized"]) for i in r["items"]] == [
        ("스트레칭 (축소)", 180, False), ("물 한 잔", 60, False), ("유산소 (제자리 러닝 등)", 360, False)]


def test_set_routine_items_and_reset(client, repos):
    a = create(client, ROUTINE).json()
    stretch, water, cardio = (i["template_item_id"] for i in client.get(items_url(a), headers=auth(USER_A)).json()["items"])
    body = {"items": [{"template_item_id": stretch, "duration_sec": 300}, {"template_item_id": water, "duration_sec": 0},
                      {"template_item_id": cardio, "duration_sec": 360}]}
    r = client.put(items_url(a), json=body, headers=auth(USER_A))
    assert r.status_code == 200
    assert r.json()["total_sec"] == 660 and [i["duration_sec"] for i in r.json()["items"]] == [300, 0, 360]
    assert repos.alarm_routine_items.get_overrides(a["alarm_id"]) == {stretch: 300, water: 0}   # 바꾼 항목만 저장
    r = client.put(items_url(a), json={"items": []}, headers=auth(USER_A))                   # 전부 기본값으로
    assert r.json()["total_sec"] == 600 and repos.alarm_routine_items.get_overrides(a["alarm_id"]) == {}


def test_set_routine_items_rejects(client):
    a = create(client, ROUTINE).json()
    stretch, water, cardio = (i["template_item_id"] for i in client.get(items_url(a), headers=auth(USER_A)).json()["items"])
    put = lambda items: client.put(items_url(a), json={"items": items}, headers=auth(USER_A))
    over = put([{"template_item_id": cardio, "duration_sec": 690}])                         # 930초
    assert over.status_code == 422 and over.json()["error"]["code"] == "ROUTINE_TIME_INVALID"
    assert put([{"template_item_id": x, "duration_sec": 0} for x in (stretch, water, cardio)]).status_code == 422
    assert put([{"template_item_id": water, "duration_sec": 45}]).status_code == 422           # 30초 단위
    assert put([{"template_item_id": "nope", "duration_sec": 60}]).json()["error"]["code"] == "ROUTINE_ITEM_UNKNOWN"
    assert put([{"template_item_id": water, "duration_sec": 30}, {"template_item_id": water, "duration_sec": 60}]).status_code == 422


def test_routine_items_only_for_routine_alarm(client):
    c = create(client, CONTENT).json()
    r = client.get(items_url(c), headers=auth(USER_A))
    assert r.status_code == 422 and r.json()["error"]["code"] == "NOT_ROUTINE_ALARM"


def test_changing_preset_clears_item_settings(client, repos):
    a = create(client, ROUTINE).json()
    water = client.get(items_url(a), headers=auth(USER_A)).json()["items"][1]["template_item_id"]
    client.put(items_url(a), json={"items": [{"template_item_id": water, "duration_sec": 0}]}, headers=auth(USER_A))
    client.patch(f"/alarms/{a['alarm_id']}", json={"template_id": template_id("N")}, headers=auth(USER_A))
    r = client.get(items_url(a), headers=auth(USER_A)).json()
    assert r["template_id"] == template_id("N") and r["total_sec"] == 600
    assert repos.alarm_routine_items.get_overrides(a["alarm_id"]) == {}
