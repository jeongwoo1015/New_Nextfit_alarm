"""리포트·일기·사진·탈퇴. 아침 API로 여러 날 세션을 만든 뒤 실제 user_model 계산으로 확인한다."""
from datetime import date, datetime, timedelta

import pytest

from backend.core.timeutil import KST

from .conftest import USER_A, USER_B, auth
from .test_api_night import TALK
from .test_api_users import onboard

pytest.importorskip("ai.personalization.user_model")

H = auth(USER_A)


def morning(client, clock, day: date, respond=True, done=6):
    """그날 07:00 알람 → (07:03 첫 응답·확인 멘트 완료) → 코칭 단계 done개 완료 → 종료. 프리셋 N은 6단계."""
    alarm = client.get("/alarms", headers=H).json()[0]
    clock.now = datetime(day.year, day.month, day.day, 7, 0, tzinfo=KST)
    v = client.post("/morning/sessions", json={"alarm_id": alarm["alarm_id"]}, headers=H).json()
    url = f"/morning/sessions/{v['session']['session_id']}"
    if respond:
        clock.now = clock.now.replace(minute=3)
        client.post(f"{url}/response", headers=H)
        client.post(f"{url}/wake-check", headers=H)                         # 확인 멘트 완료 → 기상 성공
    client.post(f"{url}/coaching-token", headers=H)
    for n in range(1, done + 1):
        client.post(f"{url}/steps/{n}/verdict", json={"verdict": "done"}, headers=H)
    client.post(f"{url}/end", headers=H)


@pytest.fixture
def week(client, clock):
    """11/2(월)~11/6(금): 성공·성공·성공·실패(무응답)·성공, 완료 단계 6·3·6·0·6. 지금은 11/6(금) 저녁."""
    onboard(client)
    for day, (respond, done) in zip(range(2, 7), [(True, 6), (True, 3), (True, 6), (False, 0), (True, 6)]):
        morning(client, clock, date(2026, 11, day), respond, done)
    clock.now = datetime(2026, 11, 6, 21, 0, tzinfo=KST)


def test_week_report(client, week):
    r = client.get("/reports", params={"period": "week"}, headers=H).json()
    assert (r["start"], r["end"]) == ("2026-11-02", "2026-11-09")
    t = r["totals"]
    assert (t["n_sessions"], t["wake_success_rate"], t["routine_done"], t["routine_total"]) == (5, 0.8, 21, 30)
    assert t["routine_completion_rate"] == pytest.approx(0.7) and t["avg_wake_clock"] == "07:03"
    assert [b["label"] for b in r["series"]] == list("월화수목금토일")
    assert [b["routine_done"] for b in r["series"]] == [6, 3, 6, 0, 6, 0, 0]
    assert r["series"][5]["routine_completion_rate"] is None                    # 기록 없는 날
    assert (r["current_streak"], r["best_streak"]) == (1, 3)                    # 목요일 실패로 끊김
    assert r["overall"] == r["totals"]                                          # 기록이 이번 주뿐이면 누적 = 기간


def test_month_and_year_reports(client, week):
    m = client.get("/reports", params={"period": "month", "date": "2026-11-15"}, headers=H).json()
    assert m["series"][0]["label"] == "1주" and m["totals"]["n_sessions"] == 5 and (m["start"], m["end"]) == ("2026-11-01", "2026-12-01")
    y = client.get("/reports", params={"period": "year"}, headers=H).json()
    assert [b["label"] for b in y["series"]][-1] == "12월" and y["series"][10]["n_sessions"] == 5
    empty = client.get("/reports", params={"period": "week", "date": "2026-10-01"}, headers=H).json()
    assert empty["totals"]["n_sessions"] == 0 and empty["totals"]["wake_success_rate"] is None
    assert client.get("/reports", params={"period": "day"}, headers=H).status_code == 422


def test_calendar(client, clock, week):
    client.put("/diary/2026-11-03/photo", content=b"jpg", headers={**H, "Content-Type": "image/jpeg"})
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    client.post("/night/transcript", json={"transcript": TALK}, headers=H)       # 11/5 밤 기록
    clock.now = datetime(2026, 11, 6, 21, 0, tzinfo=KST)
    days = {d["date"]: d for d in client.get("/diary/calendar", params={"month": "2026-11"}, headers=H).json()}
    assert len(days) == 30
    assert days["2026-11-03"]["photo_url"].startswith(f"memory://photo/{USER_A}/2026-11-03.jpg")
    assert days["2026-11-02"]["photo_url"] is None and days["2026-11-02"]["routine_completion_rate"] == 1.0
    assert (days["2026-11-05"]["has_session"], days["2026-11-05"]["routine_total"], days["2026-11-05"]["has_night_record"]) == (True, 6, True)
    assert days["2026-11-10"]["has_session"] is False and days["2026-11-10"]["routine_completion_rate"] is None
    assert client.get("/diary/calendar", params={"month": "2026-13"}, headers=H).status_code == 422


def test_diary_day(client, clock, week, repos):
    clock.now = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    rec_id = client.post("/night/transcript", json={"transcript": TALK}, headers=H).json()["night_record_id"]
    client.put(f"/night/records/{rec_id}/voice", content=b"m4a", headers={**H, "Content-Type": "audio/mp4"})
    clock.now = datetime(2026, 11, 6, 21, 0, tzinfo=KST)
    d = client.get("/diary/2026-11-05", headers=H).json()
    assert d["night"]["ai_summary"] == "요약" and d["night"]["voice_url"].startswith("memory://voice/")
    assert len(d["sessions"]) == 1 and len(d["sessions"][0]["steps"]) == 6 and d["sessions"][0]["session"]["wake_success"] is False
    assert d["summary"]["routine_total"] == 6 and d["photo_url"] is None
    repos.storage.files.clear()                                                  # 파일이 없어도 URL만 비고 정상 응답
    d = client.get("/diary/2026-11-05", headers=H).json()
    assert d["night"]["voice_url"] is None and d["night"]["ai_summary"] == "요약"
    empty = client.get("/diary/2026-10-01", headers=H).json()
    assert empty["night"] is None and empty["sessions"] == [] and empty["summary"]["n_sessions"] == 0


def test_photo_rules(client, clock):
    onboard(client)
    put = lambda day, body, user=USER_A: client.put(f"/diary/{day}/photo", content=body, headers={**auth(user), "Content-Type": "image/jpeg"})
    assert put("2026-11-05", b"jpg-1").json()["photo_url"].startswith("memory://photo/")
    assert put("2026-11-05", b"jpg-2").status_code == 200                       # 다시 올리면 덮어씀
    assert put("2026-11-06", b"x").status_code == 422                           # 미래 (지금은 11/5 23:30)
    assert put("2026-11-05", b"").status_code == 422
    assert client.get("/diary/2026-11-05", headers=auth(USER_B)).json()["photo_url"] is None   # 다른 사용자에게 안 보임


def test_delete_account(client, repos):
    onboard(client)
    client.put("/diary/2026-11-05/photo", content=b"jpg", headers={**H, "Content-Type": "image/jpeg"})
    assert client.delete("/users/me", headers=H).status_code == 204
    assert client.get("/users/me", headers=H).json()["onboarding_done"] is False
    assert not [k for k in repos.storage.files if k[1].startswith(USER_A)]


def test_upload_media_types(client, clock):
    onboard(client)
    r = client.put("/diary/2026-11-05/photo", content=b"\x89PNG", headers={**H, "Content-Type": "image/png"})
    assert r.status_code == 415 and r.json()["error"]["code"] == "UNSUPPORTED_MEDIA_TYPE"
    assert client.put("/diary/2026-11-05/photo", content=b"jpg", headers={**H, "Content-Type": "image/jpeg; charset=binary"}).status_code == 200
    rec_id = client.post("/night/transcript", json={"transcript": TALK}, headers=H).json()["night_record_id"]
    url = f"/night/records/{rec_id}/voice"
    assert client.put(url, content=b"m4a", headers={**H, "Content-Type": "audio/x-m4a"}).status_code == 200
    assert client.put(url, content=b"wav", headers={**H, "Content-Type": "audio/wav"}).status_code == 415


def test_overall_is_cumulative_and_series_is_period(client, clock, week):
    morning(client, clock, date(2026, 10, 26), True, 0)                          # 지난주 월요일: 성공, 0/6
    clock.now = datetime(2026, 11, 6, 21, 0, tzinfo=KST)
    r = client.get("/reports", params={"period": "week"}, headers=H).json()
    assert (r["overall"]["n_sessions"], r["overall"]["routine_done"], r["overall"]["routine_total"]) == (6, 21, 36)
    assert r["overall"]["wake_success_rate"] == pytest.approx(5 / 6)
    assert (r["totals"]["n_sessions"], r["totals"]["routine_total"]) == (5, 30)   # 그래프 영역은 이번 주만
    last = client.get("/reports", params={"period": "week", "date": "2026-10-26"}, headers=H).json()
    assert last["totals"]["n_sessions"] == 1 and last["overall"]["n_sessions"] == 6   # 지난주를 봐도 상단은 누적
