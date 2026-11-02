"""AI 음악: 재기상 음악, 수면 음악."""
from typing import Literal

from fastapi import APIRouter, Depends, Query

from backend.app.deps import music_service
from backend.core.auth import current_user_id
from backend.domain import codes
from backend.services.music_service import MusicService, MusicTrack

from .schemas import MusicOut

router = APIRouter(prefix="/music", tags=["music"])


def _out(t: MusicTrack) -> MusicOut:
    m = t.music
    return MusicOut(music_id=m.music_id, music_type=m.music_type, genre=m.genre, mood=m.mood, url=t.url, expires_in=t.expires_in)


@router.get("/rewake", response_model=MusicOut)
def rewake_music(user_id: str = Depends(current_user_id), svc: MusicService = Depends(music_service)):
    """8초 무응답 때 트는 재기상 음악 (설정의 음악 장르). 밤에 미리 받아 두면 아침 네트워크와 상관없이 울린다.
    없으면 404 MUSIC_NOT_FOUND → 기본 알람음·진동으로."""
    return _out(svc.rewake(user_id))


@router.get("/sleep", response_model=MusicOut)
def sleep_music(mood: Literal[codes.CONDITION_LABELS] | None = Query(None, description="비우면 오늘 밤 기록의 컨디션, 없으면 NORMAL"),
                user_id: str = Depends(current_user_id), svc: MusicService = Depends(music_service)):
    """밤 대화 뒤 트는 수면 음악 (무드 = 컨디션). 없으면 404 MUSIC_NOT_FOUND."""
    return _out(svc.sleep(user_id, mood))
