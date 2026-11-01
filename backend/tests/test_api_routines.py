from backend.repositories.seed import template_id

from .conftest import USER_A, USER_B, auth


def test_presets_match_seed_rules(client):
    presets = client.get("/presets", headers=auth(USER_A)).json()
    assert len(presets) == 5
    for p in presets:
        assert (p["total_10_sec"], p["total_20_sec"]) == (600, 1200), p["template_name"]
        assert [i["item_order"] for i in p["items"]] == list(range(1, len(p["items"]) + 1))
    q = client.get(f"/presets/{template_id('Q')}", headers=auth(USER_A)).json()
    assert [i["item_name"] for i in q["items"]][-1] == "샤워" and q["items"][-1]["duration_10_sec"] is None
    assert client.get("/presets/unknown", headers=auth(USER_A)).status_code == 404


def test_presets_need_login(client):
    assert client.get("/presets").status_code == 401


def test_exclude_and_restore_item(client):
    url = f"/presets/{template_id('Q')}/my-items"
    items = client.get(url, headers=auth(USER_A)).json()
    assert all(i["enabled"] for i in items)
    shower = items[-1]["template_item_id"]
    r = client.put(f"/routine-items/{shower}", json={"enabled": False}, headers=auth(USER_A))
    assert r.status_code == 200 and [i["enabled"] for i in r.json()] == [True, True, True, False]
    assert all(i["enabled"] for i in client.get(url, headers=auth(USER_B)).json())    # 다른 사용자는 그대로
    r = client.put(f"/routine-items/{shower}", json={"enabled": True}, headers=auth(USER_A))
    assert all(i["enabled"] for i in r.json())
    assert client.put(f"/routine-items/{shower}", json={"enabled": True}, headers=auth(USER_A)).status_code == 200   # 두 번 넣어도 됨


def test_cannot_exclude_every_item(client):
    items = client.get(f"/presets/{template_id('Q')}/my-items", headers=auth(USER_A)).json()
    for i in items[:-1]:
        assert client.put(f"/routine-items/{i['template_item_id']}", json={"enabled": False}, headers=auth(USER_A)).status_code == 200
    r = client.put(f"/routine-items/{items[-1]['template_item_id']}", json={"enabled": False}, headers=auth(USER_A))
    assert r.status_code == 422 and r.json()["error"]["code"] == "LAST_ROUTINE_ITEM"


def test_unknown_item(client):
    assert client.put("/routine-items/nope", json={"enabled": False}, headers=auth(USER_A)).status_code == 404
