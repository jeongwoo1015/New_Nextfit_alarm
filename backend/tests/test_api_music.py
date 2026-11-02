from backend.clients.ai_gateway import NightExtract
from backend.domain.models import AiMusic

from .conftest import USER_A, auth
from .test_api_night import TALK
from .test_api_users import onboard                    # 음악 장르 JAZZ

H = auth(USER_A)


def add_music(repos, music_id, music_type, genre=None, mood=None, with_file=True):
    path = f"{music_type}/{genre or mood}/{music_id}.mp3"
    repos.presets.music.append(AiMusic(music_id, music_type, "prompt", path, genre, mood))
    if with_file:
        repos.storage.files[("music", path)] = b"mp3"


def test_rewake_music_uses_setting_genre(client, repos):
    onboard(client)
    add_music(repos, "pop1", "REWAKE", genre="POP")
    add_music(repos, "jazz1", "REWAKE", genre="JAZZ")
    r = client.get("/music/rewake", headers=H).json()
    assert (r["music_id"], r["genre"], r["mood"], r["expires_in"]) == ("jazz1", "JAZZ", None, 43200)
    assert r["url"].startswith("memory://music/REWAKE/JAZZ/jazz1.mp3")
    client.patch("/settings", json={"music_genre": "POP"}, headers=H)
    assert client.get("/music/rewake", headers=H).json()["music_id"] == "pop1"


def test_music_not_ready_is_404(client, repos):
    onboard(client)
    r = client.get("/music/rewake", headers=H)
    assert r.status_code == 404 and r.json()["error"]["code"] == "MUSIC_NOT_FOUND"
    add_music(repos, "jazz1", "REWAKE", genre="JAZZ", with_file=False)          # 행은 있는데 파일이 없음
    assert client.get("/music/rewake", headers=H).status_code == 404


def test_rewake_needs_onboarding(client):
    assert client.get("/music/rewake", headers=H).status_code == 404


def test_sleep_music_follows_tonight_condition(client, repos, ai):
    onboard(client)
    for mood in ("NORMAL", "TIRED"):
        add_music(repos, f"sleep-{mood.lower()}", "SLEEP", mood=mood)
    assert client.get("/music/sleep", headers=H).json()["mood"] == "NORMAL"     # 밤 기록 없음 → NORMAL
    ai.extract = NightExtract("DONE", {"condition_label": "TIRED"})
    client.post("/night/transcript", json={"transcript": TALK}, headers=H)       # 오늘 밤(11/5) 기록
    r = client.get("/music/sleep", headers=H).json()
    assert (r["music_id"], r["mood"], r["expires_in"]) == ("sleep-tired", "TIRED", 3600)
    assert client.get("/music/sleep", params={"mood": "NORMAL"}, headers=H).json()["mood"] == "NORMAL"   # 직접 고름
    assert client.get("/music/sleep", params={"mood": "SAD"}, headers=H).status_code == 422
