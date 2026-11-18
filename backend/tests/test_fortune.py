"""오늘의 포춘(11/10 결정): 밤 한마디 받아쓴 글 → 아침 세션 생성 뒤 백그라운드로 문장 → 포춘 읽기 토큰."""
from datetime import datetime

from backend.core.errors import ServiceUnavailable
from backend.core.timeutil import KST

from .conftest import USER_A, USER_B, auth
from .test_api_morning import FRIDAY_7, start, url
from .test_api_night import send
from .test_api_users import onboard

H = auth(USER_A)
NIGHT = datetime(2026, 11, 5, 23, 30, tzinfo=KST)


def night_with_voice(client, clock, text=None):
    """11/5 밤: 받아쓰기 → 목소리 업로드 → (받아쓴 글)."""
    clock.now = NIGHT
    rec_id = send(client).json()["night_record_id"]
    client.put(f"/night/records/{rec_id}/voice", content=b"m4a", headers={**H, "Content-Type": "audio/mp4"})
    if text is not None:
        r = client.patch(f"/night/records/{rec_id}/voice-text", json={"text": text}, headers=H)
        assert r.status_code == 200
    return rec_id


def alarm(client):
    return client.get("/alarms", headers=H).json()[0]


# ---------- 3. 받아쓴 글 (별도 요청) ----------
def test_voice_text_after_voice_upload(client, clock):
    onboard(client)
    rec_id = night_with_voice(client, clock, " 내일 발표 떨지 말고 잘하자! ")
    assert client.get(f"/night/records/{rec_id}", headers=H).json()["voice_text"] == "내일 발표 떨지 말고 잘하자!"
    client.put(f"/night/records/{rec_id}/voice", content=b"m4a2", headers={**H, "Content-Type": "audio/mp4"})
    assert client.get(f"/night/records/{rec_id}", headers=H).json()["voice_text"] is None      # 다시 녹음하면 비워짐
    r = client.patch(f"/night/records/{rec_id}/voice-text", json={"text": "가" * 501}, headers=H)
    assert r.status_code == 422
    assert client.patch(f"/night/records/{rec_id}/voice-text", json={"text": "x"}, headers=auth(USER_B)).status_code == 404


def test_voice_text_needs_voice(client, clock):
    onboard(client)
    clock.now = NIGHT
    rec_id = send(client).json()["night_record_id"]
    r = client.patch(f"/night/records/{rec_id}/voice-text", json={"text": "한마디"}, headers=H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "VOICE_NOT_UPLOADED"


# ---------- 4. 포춘 ----------
def test_fortune_prepared_in_background_from_voice_text(client, clock, ai):
    onboard(client)
    night_with_voice(client, clock, "내일 발표 잘하자")
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    assert v["session"]["fortune_text"] is None                                # 응답에는 아직 없음 (뒤에서 만든다)
    s = client.get(url(v), headers=H).json()["session"]
    assert (s["fortune_text"], s["fortune_source"]) == ("포춘: 내일 발표 잘하자", "ai")
    assert ai.calls[-1] == ("fortune", ("내일 발표 잘하자", "CASUAL", FRIDAY_7.date(), ""))   # 온보딩 말투 = 반말

    r = client.post(url(v, "/fortune-token"), headers=H)
    assert r.status_code == 200
    body = r.json()
    assert body["token"] == "tok-fortune" and body["fortune_text"] == "포춘: 내일 발표 잘하자"
    assert [c[0] for c in ai.calls].count("fortune") == 1                      # 저장된 문장을 다시 씀
    persona, fortune, based_on = ai.calls[-1][1]
    assert fortune.text == body["fortune_text"] and persona.call_name
    assert based_on == "ONE_LINER"                                              # 어젯밤 한마디로 만든 문장

    start(client, alarm(client))                                               # 재연결: 다시 만들지 않는다
    client.post(url(v, "/fortune-token"), headers=H)
    assert [c[0] for c in ai.calls].count("fortune") == 1


def test_fortune_uses_diary_when_no_voice_text(client, clock, ai):
    onboard(client)
    clock.now = NIGHT
    ai.extract = ai.extract.__class__("DONE", {"diary_text": "발표 준비로 바빴던 하루", "tomorrow_notes": []})
    send(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    assert client.get(url(v), headers=H).json()["session"]["fortune_text"] == "포춘: 발표 준비로 바빴던 하루"


def test_fortune_fallback_without_night(client, clock):
    onboard(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    s = client.post(url(v, "/fortune-token"), headers=H).json()
    assert s["fortune_source"] == "fallback" and s["fortune_text"]


def test_background_failure_retried_at_token(client, clock, ai):
    """백그라운드에서 실패해도 알람 응답은 정상, 포춘 토큰 때 다시 만든다."""
    onboard(client)
    clock.now = FRIDAY_7
    ai.fortune_error = ServiceUnavailable("AI 기능을 쓸 수 없어요", "AI_UNAVAILABLE")
    r = start(client, alarm(client))
    assert r.status_code == 200
    v = r.json()
    assert client.get(url(v), headers=H).json()["session"]["fortune_text"] is None
    r = client.post(url(v, "/fortune-token"), headers=H)
    assert r.status_code == 503 and r.json()["error"]["code"] == "AI_UNAVAILABLE"
    ai.fortune_error = None
    assert client.post(url(v, "/fortune-token"), headers=H).json()["fortune_text"]


def test_fortune_token_other_user(client, clock):
    onboard(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    assert client.post(url(v, "/fortune-token"), headers=auth(USER_B)).status_code == 404


def test_fortune_based_on(client, clock, ai):
    """소개에 밝힐 재료: 일기로 만들면 DIARY, 기본 문장이면 밝히지 않는다."""
    onboard(client)
    clock.now = NIGHT
    ai.extract = ai.extract.__class__("DONE", {"diary_text": "바빴던 하루", "tomorrow_notes": []})
    send(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    client.post(url(v, "/fortune-token"), headers=H)
    assert ai.calls[-1][1][2] == "DIARY"


def test_fallback_fortune_does_not_claim_source(client, clock, ai):
    onboard(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    assert client.post(url(v, "/fortune-token"), headers=H).json()["fortune_source"] == "fallback"
    assert ai.calls[-1][1][2] == ""


def test_briefing_says_one_liner_follows(client, clock, ai):
    """어젯밤 한마디 녹음이 있으면 브리핑이 '한마디 → 포춘 → 콘텐츠' 순서를 안내한다."""
    onboard(client)
    clock.now = FRIDAY_7
    v = start(client, alarm(client)).json()
    client.post(url(v, "/briefing-token"), json={}, headers=H)
    assert ai.calls[-1][1].one_liner_follows is False                         # 밤 기록 없음
    night_with_voice(client, clock)
    clock.now = FRIDAY_7
    client.post(url(v, "/briefing-token"), json={}, headers=H)
    assert ai.calls[-1][1].one_liner_follows is True
