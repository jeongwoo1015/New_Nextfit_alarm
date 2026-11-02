"""AI 음악 (공용 캐시 RS_AI_MUSIC): 재기상 음악은 설정의 음악 장르로, 수면 음악은 오늘 밤 컨디션으로 고른다.

없으면 404 — 앱은 상세설계서 흐름(재질문 → AI 음악 → 기존 알람음·진동)대로 기본 알람음으로 넘어간다.
"""
from dataclasses import dataclass

from backend.core.errors import NotFound
from backend.core.timeutil import now_kst, record_date
from backend.domain import codes
from backend.domain.models import AiMusic
from backend.repositories.registry import Repositories

# 재기상 음악은 밤에 미리 받아 두고 아침에 바로 틀 수 있게 서명 URL을 길게 준다(아침에 네트워크가 느려도 울리도록)
REWAKE_URL_SEC = 12 * 3600
SLEEP_URL_SEC = 3600
DEFAULT_MOOD = "NORMAL"


@dataclass(frozen=True)
class MusicTrack:
    music: AiMusic
    url: str
    expires_in: int


class MusicService:
    def __init__(self, repos: Repositories, clock=now_kst):
        self.r, self.clock = repos, clock

    def _track(self, music: AiMusic | None, expires_in: int, what: str) -> MusicTrack:
        url = self.r.storage.signed_url("music", music.music_path, expires_in) if music else None
        if not url:                                   # 행이 없거나 파일이 없음
            raise NotFound(f"{what}이 아직 준비되지 않았어요", "MUSIC_NOT_FOUND")
        return MusicTrack(music, url, expires_in)

    def rewake(self, user_id: str) -> MusicTrack:
        """8초 무응답 때 트는 재기상 음악. 설정의 음악 장르(RS_USER_SETTING.MUSIC_GENRE)."""
        setting = self.r.users.get_setting(user_id)
        if not setting:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        return self._track(self.r.presets.pick_music("REWAKE", genre=setting.music_genre), REWAKE_URL_SEC, "재기상 음악")

    def sleep(self, user_id: str, mood: str | None = None) -> MusicTrack:
        """밤 대화 뒤 트는 수면 음악. 무드 = 오늘 밤 기록의 컨디션(CONDITION_LABEL), 없으면 NORMAL.
        mood를 직접 주면 그 값(앱에서 고를 때)."""
        if mood is None:
            night = self.r.nights.get_night_by_date(user_id, record_date(self.clock()))
            mood = night.condition_label if night and night.condition_label in codes.CONDITION_LABELS else DEFAULT_MOOD
        return self._track(self.r.presets.pick_music("SLEEP", mood=mood), SLEEP_URL_SEC, "수면 음악")
