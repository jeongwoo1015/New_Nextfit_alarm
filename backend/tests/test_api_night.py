from datetime import datetime, time

import pytest

from backend.clients.ai_gateway import NightExtract, RoutineChangeRequest, UnavailableAIGateway
from backend.core.timeutil import KST

from .conftest import USER_A, USER_B, auth
from .test_api_users import onboard

TALK = [{"role": "assistant", "text": "오늘 발표는 잘 끝났어요?", "at": "2026-11-05T23:30:05+09:00"},
        {"role": "user", "text": "응 잘 끝났어. 내일은 피곤해서 루틴 쉬고 6시 반에 깨워 줘", "at": "2026-11-05T23:30:20+09:00"}]


def first_alarm(client, user=USER_A):
    return client.get("/alarms", headers=auth(user)).json()[0]


def send(client, user=USER_A, transcript=TALK):
    return client.post("/night/transcript", json={"transcript": transcript}, headers=auth(user))


def test_token_includes_persona_tomorrow_alarm_and_routine_items(client, ai):
    onboard(client)                                                    # 07:00 매일, 프리셋 N
    r = client.post("/night/token", json={"events_today": [{"title": "발표", "start": "14:00"}]}, headers=auth(USER_A))
    assert r.status_code == 200 and r.json()["token"] == "tok-night"
    ctx = ai.calls[-1][1]
    assert (ctx.persona.call_name, ctx.persona.tone, ctx.now_hhmm, ctx.tomorrow_wake_time) == ("정우님", "CASUAL", "23:30", "07:00")
    assert ctx.events_today[0].title == "발표" and len(ctx.routine_items) == 6


def test_token_rejects_bad_input_and_needs_onboarding(client):
    assert client.post("/night/token", json={}, headers=auth(USER_A)).status_code == 404
    onboard(client)
    assert client.post("/night/token", json={"events_today": [{"title": "x", "start": "25:00"}]}, headers=auth(USER_A)).status_code == 422


def test_transcript_then_background_extract(client, ai):
    onboard(client)
    r = send(client)
    assert r.status_code == 202
    body = r.json()
    assert (body["extract_status"], body["record_date"], body["apply_date"]) == ("PENDING", "2026-11-05", "2026-11-06")
    rec = client.get(f"/night/records/{body['night_record_id']}", headers=auth(USER_A)).json()
    assert rec["extract_status"] == "DONE" and rec["ai_summary"] == "요약"
    assert ai.calls[-1][0] == "routine"                                  # 루틴 알람이라 항목 변경도 확인
    by_date = client.get("/night/records", params={"record_date": "2026-11-05"}, headers=auth(USER_A)).json()
    assert by_date["night_record_id"] == body["night_record_id"]


def test_override_goes_to_tomorrow_alarm(client, ai):
    onboard(client)
    ai.extract = NightExtract("DONE", {"tomorrow_goal": "보고서", "override_wake_time": time(6, 30), "override_skip_coaching": True})
    rec_id = send(client).json()["night_record_id"]
    rec = client.get(f"/night/records/{rec_id}", headers=auth(USER_A)).json()
    assert (rec["override_alarm_id"], rec["override_wake_time"], rec["override_skip_coaching"]) == (first_alarm(client)["alarm_id"], "06:30", True)


def test_override_dropped_when_no_alarm_next_morning(client, ai, clock):
    onboard(client)
    client.patch(f"/alarms/{first_alarm(client)['alarm_id']}", json={"repeat_days": "1111100"}, headers=auth(USER_A))
    clock.now = datetime(2026, 11, 6, 23, 0, tzinfo=KST)                # 금요일 밤 → 토요일 아침엔 알람 없음
    ai.extract = NightExtract("DONE", {"override_skip_coaching": True})
    rec = client.get(f"/night/records/{send(client).json()['night_record_id']}", headers=auth(USER_A)).json()
    assert rec["extract_status"] == "DONE" and rec["override_alarm_id"] is None and rec["override_skip_coaching"] is False


def test_extract_failure_marks_failed(client, ai):
    onboard(client)
    ai.extract = NightExtract("FAILED", error="모든 모델 호출 실패")
    rec = client.get(f"/night/records/{send(client).json()['night_record_id']}", headers=auth(USER_A)).json()
    assert rec["extract_status"] == "FAILED"


def test_conversation_routine_change_updates_tomorrow_alarm(client, ai):
    onboard(client)
    alarm = first_alarm(client)
    items = client.get(f"/alarms/{alarm['alarm_id']}/routine-items", headers=auth(USER_A)).json()["items"]
    ai.routine = RoutineChangeRequest(remove=[items[3]["template_item_id"]])          # 이불 정리 빼기
    send(client)
    after = client.get(f"/alarms/{alarm['alarm_id']}/routine-items", headers=auth(USER_A)).json()
    assert after["items"][3]["duration_sec"] == 0 and after["total_sec"] == 540


def test_conversation_cannot_remove_every_item(client, ai):
    onboard(client)
    alarm = first_alarm(client)
    items = client.get(f"/alarms/{alarm['alarm_id']}/routine-items", headers=auth(USER_A)).json()["items"]
    ai.routine = RoutineChangeRequest(remove=[i["template_item_id"] for i in items])
    send(client)
    after = client.get(f"/alarms/{alarm['alarm_id']}/routine-items", headers=auth(USER_A)).json()
    assert [i["duration_sec"] for i in after["items"]] == [0, 0, 0, 0, 0, 120]       # 마지막 항목은 남김


@pytest.mark.parametrize("transcript", [
    [{"role": "assistant", "text": "안녕하세요"}],
    [{"role": "user", "text": "   "}],
])
def test_transcript_without_user_speech(client, transcript):
    onboard(client)
    assert send(client, transcript=transcript).status_code == 422


def test_same_night_overwrites(client):
    onboard(client)
    a = send(client).json()["night_record_id"]
    b = send(client).json()["night_record_id"]
    assert a == b


def test_voice_upload(client):
    onboard(client)
    rec_id = send(client).json()["night_record_id"]
    url = f"/night/records/{rec_id}/voice"
    r = client.put(url, content=b"m4a-bytes", headers={**auth(USER_A), "Content-Type": "audio/mp4"})
    assert r.status_code == 200 and r.json()["voice_url"].startswith(f"memory://voice/{USER_A}/2026-11-05.m4a")
    assert client.put(url, content=b"", headers=auth(USER_A)).status_code == 422
    assert client.put(url, content=b"x", headers=auth(USER_B)).status_code == 404
    assert client.get(f"/night/records/{rec_id}", headers=auth(USER_B)).status_code == 404


def test_ai_unavailable(client, ai):
    client.app.state.ai = UnavailableAIGateway("GEMINI_API_KEY가 없어요")
    onboard(client)
    r = client.post("/night/token", json={}, headers=auth(USER_A))
    assert r.status_code == 503 and r.json()["error"]["code"] == "AI_UNAVAILABLE"
    rec = client.get(f"/night/records/{send(client).json()['night_record_id']}", headers=auth(USER_A)).json()
    assert rec["extract_status"] == "FAILED"                           # 원문은 남아 나중에 다시 추출
