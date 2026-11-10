"""메모리 저장소의 포춘 3칸·받아쓴 글 규칙 — Supabase(11_fortune.sql CHECK, supabase.py)와 같게 동작하는지."""
import pytest

from backend.core.errors import ValidationFailed

from .conftest import USER_A, auth
from .test_api_night import TALK, first_alarm
from .test_api_users import onboard

H = auth(USER_A)


def test_voice_text_saved_with_voice_and_kept_on_reextract(client, repos):
    onboard(client)
    nid = client.post("/night/transcript", json={"transcript": TALK}, headers=H).json()["night_record_id"]
    repos.nights.set_voice_path(USER_A, nid, " 내일 발표 떨지 말고 잘하자! ")
    assert repos.nights.get_night(USER_A, nid).voice_text == "내일 발표 떨지 말고 잘하자!"
    repos.nights.save_night_extract(USER_A, nid, {"voice_text": "x", "tomorrow_goal": "발표"}, None)
    rec = repos.nights.get_night(USER_A, nid)
    assert (rec.voice_text, rec.tomorrow_goal) == ("내일 발표 떨지 말고 잘하자!", "발표")   # 다시 추출해도 그대로
    repos.nights.set_voice_path(USER_A, nid)                                           # 다시 녹음, 글 없음 → 비운다
    assert repos.nights.get_night(USER_A, nid).voice_text is None
    with pytest.raises(ValidationFailed):
        repos.nights.set_voice_path(USER_A, nid, "가" * 501)                            # varchar(500)
    assert client.get(f"/night/records/{nid}", headers=H).json()["voice_text"] is None


def test_fortune_fields_all_or_nothing(client, repos):
    onboard(client)
    v = client.post("/morning/sessions", json={"alarm_id": first_alarm(client)["alarm_id"]}, headers=H).json()
    sid = v["session"]["session_id"]
    assert v["session"]["fortune_text"] is None
    fortune = {"fortune_text": "떨리는 건 그만큼 준비했다는 뜻이에요.", "fortune_style": "COOKIE", "fortune_source": "ai"}
    repos.sessions.update_session(USER_A, sid, fortune)
    got = client.get(f"/morning/sessions/{sid}", headers=H).json()["session"]
    assert {k: got[k] for k in fortune} == fortune
    with pytest.raises(ValidationFailed):
        repos.sessions.update_session(USER_A, sid, {"fortune_style": None})             # 문장만 있고 스타일 없음
    with pytest.raises(ValidationFailed):
        repos.sessions.update_session(USER_A, sid, {"fortune_style": "TAROT"})
    assert repos.sessions.get_session(USER_A, sid).fortune_style == "COOKIE"            # 실패한 변경은 남지 않는다
