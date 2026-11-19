"""11/18 굿나잇콜 알람을 RS_ALARM에 저장(방향 B, DB 14_bedtime_alarm.sql), 테마 AUTO(13_stretch_theme.sql)."""
from datetime import time

from backend.clients.ai_gateway import NightExtract
from backend.domain.models import AppConfig

from .conftest import USER_A, auth
from .test_api_alarms import CONTENT, ROUTINE, create, items_url
from .test_api_morning import start
from .test_api_night import send
from .test_api_users import onboard

H = auth(USER_A)


def wake_alarms(client):
    return [a for a in client.get("/alarms", headers=H).json() if a["alarm_kind"] == "WAKE"]
BEDTIME = {"alarm_time": "22:30", "alarm_kind": "BEDTIME", "repeat_days": "1111100"}


def test_create_bedtime_alarm_and_list_both_kinds(client, repos):
    w = create(client, ROUTINE).json()
    r = create(client, BEDTIME)
    assert r.status_code == 201
    b = r.json()
    assert (b["alarm_kind"], b["morning_call_type"], b["template_id"], b["alarm_mode"]) == ("BEDTIME", None, None, "ON_TIME")
    assert w["alarm_kind"] == "WAKE"
    assert [x["alarm_id"] for x in client.get("/alarms", headers=H).json()] == [w["alarm_id"], b["alarm_id"]]   # 목록은 둘 다
    assert [a.alarm_id for a in repos.alarms.list_alarms(USER_A)] == [w["alarm_id"]]                          # 기본은 모닝콜만
    assert client.get(f"/alarms/{b['alarm_id']}", headers=H).json()["alarm_kind"] == "BEDTIME"


def test_bedtime_alarm_rules(client):
    assert create(client, {**BEDTIME, "morning_call_type": "CONTENT", "content_type": "EXERCISE"}).status_code == 422
    assert create(client, {**BEDTIME, "alarm_mode": "BEFORE_10"}).status_code == 422          # 굿나잇콜은 정각에만
    assert create(client, {"alarm_time": "06:30"}).status_code == 422                          # 모닝콜은 종류가 필요
    assert create(client, {**BEDTIME, "alarm_kind": "NAP"}).status_code == 422
    b = create(client, BEDTIME).json()
    assert client.get(items_url(b), headers=H).json()["error"]["code"] == "NOT_ROUTINE_ALARM"


def test_switch_wake_to_bedtime_clears_wake_fields_and_back(client):
    w = create(client, CONTENT).json()
    r = client.patch(f"/alarms/{w['alarm_id']}", json={"alarm_kind": "BEDTIME"}, headers=H)
    assert r.status_code == 200
    assert (r.json()["morning_call_type"], r.json()["content_type"], r.json()["content_option"]) == (None, None, None)
    assert client.patch(f"/alarms/{w['alarm_id']}", json={"alarm_kind": "WAKE"}, headers=H).status_code == 422   # 종류가 없음
    r = client.patch(f"/alarms/{w['alarm_id']}", json={"alarm_kind": "WAKE", "morning_call_type": "CONTENT",
                                                       "content_type": "QUIZ"}, headers=H)
    assert r.status_code == 200 and (r.json()["alarm_kind"], r.json()["content_type"]) == ("WAKE", "QUIZ")


def test_switch_to_bedtime_keeps_sent_label_change(client):
    """굿나잇콜로 바꾸면서 보낸 라벨(지우기 포함)은 그대로 반영한다 (앱은 바꿀 때 label을 함께 보냄)."""
    w = create(client, ROUTINE).json()                                  # label "평일 기상"
    r = client.patch(f"/alarms/{w['alarm_id']}", json={"alarm_kind": "BEDTIME", "label": None}, headers=H)
    assert r.status_code == 200 and (r.json()["alarm_kind"], r.json()["label"]) == ("BEDTIME", None)
    w = create(client, ROUTINE).json()
    r = client.patch(f"/alarms/{w['alarm_id']}", json={"alarm_kind": "BEDTIME", "label": "자기 전"}, headers=H)
    assert r.json()["label"] == "자기 전" and r.json()["template_id"] is None


def test_bedtime_alarm_cannot_start_morning_session(client):
    onboard(client)
    b = create(client, BEDTIME).json()
    r = start(client, b)
    assert r.status_code == 409 and r.json()["error"]["code"] == "NOT_WAKE_ALARM"


def test_override_skips_earlier_bedtime_alarm(client, ai):
    """내일 아침 굿나잇콜이 모닝콜보다 먼저 울려도 '내일 6시 반에 깨워 줘'는 모닝콜에 붙는다."""
    onboard(client)                                                     # 모닝콜 07:00 매일
    create(client, {**BEDTIME, "alarm_time": "05:00", "repeat_days": "1111111"})
    ai.extract = NightExtract("DONE", {"override_wake_time": time(6, 30)})
    rec_id = send(client).json()["night_record_id"]
    rec = client.get(f"/night/records/{rec_id}", headers=H).json()
    assert rec["override_alarm_id"] == wake_alarms(client)[0]["alarm_id"] and rec["override_wake_time"] == "06:30"


def test_switching_override_target_to_bedtime_clears_override(client, ai):
    onboard(client)
    ai.extract = NightExtract("DONE", {"override_wake_time": time(6, 30), "override_skip_coaching": True})
    rec_id = send(client).json()["night_record_id"]
    wake = wake_alarms(client)[0]
    client.patch(f"/alarms/{wake['alarm_id']}", json={"alarm_kind": "BEDTIME"}, headers=H)
    rec = client.get(f"/night/records/{rec_id}", headers=H).json()
    assert (rec["override_alarm_id"], rec["override_wake_time"], rec["override_skip_coaching"]) == (None, None, False)


def test_onboarding_ignores_existing_bedtime_alarm(client):
    """굿나잇콜만 있으면 온보딩의 첫 모닝콜은 그대로 만든다."""
    create(client, BEDTIME)
    assert onboard(client).status_code in (200, 201)
    assert len(wake_alarms(client)) == 1


# ---------- 테마 AUTO, 스트레칭 시간 설정 ----------
def test_theme_auto_and_legacy_system(client):
    onboard(client)
    assert client.get("/settings", headers=H).json()["theme"] == "AUTO"                 # 기본값
    assert client.patch("/settings", json={"theme": "SYSTEM"}, headers=H).json()["theme"] == "AUTO"   # 예전 앱
    assert client.patch("/settings", json={"theme": "DARK"}, headers=H).json()["theme"] == "DARK"
    assert client.patch("/settings", json={"theme": "NIGHT"}, headers=H).status_code == 422


def test_stretch_duration_comes_from_app_config(client, repos):
    repos.presets.config = AppConfig(content_duration_sec=300, stretch_duration_sec=240)
    assert client.get("/config/limits", headers=H).json()["stretch_duration_sec"] == 240
