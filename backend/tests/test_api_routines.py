from backend.repositories.seed import template_id

from .conftest import USER_A, auth


def test_presets_match_seed(client):
    presets = client.get("/presets", headers=auth(USER_A)).json()
    assert [p["template_id"] for p in presets] == [template_id(k) for k in "MNOPQ"]
    for p in presets:
        assert p["total_sec"] == 600, p["template_name"]
        assert [i["item_order"] for i in p["items"]] == list(range(1, len(p["items"]) + 1))
    q = client.get(f"/presets/{template_id('Q')}", headers=auth(USER_A)).json()
    assert [(i["item_name"], i["default_duration_sec"]) for i in q["items"]] == [
        ("스트레칭 (축소)", 180), ("물 한 잔", 60), ("유산소 (제자리 러닝 등)", 360)]
    assert client.get("/presets/unknown", headers=auth(USER_A)).status_code == 404


def test_presets_need_login(client):
    assert client.get("/presets").status_code == 401


def test_limits(client, repos):
    assert client.get("/config/limits", headers=auth(USER_A)).json() == {
        "routine_max_sec": 900, "duration_step_sec": 30, "content_duration_sec": 600}
