"""기록 화면: 리포트, 한 줄 일기 캘린더·상세, 일기 사진.

통계 계산은 AI 파트 user_model(clients/ai_stats.py)을 쓰고, 여기서는 기간 데이터를 모아 넘긴다.
일기 날짜 D = RECORD_DATE가 D인 밤 기록 + SESSION_DATE가 D인 아침 세션 + DIARY_DATE가 D인 사진 (정의서 공통 규칙).
"""
import calendar
from dataclasses import dataclass
from datetime import date, timedelta

from backend.clients import ai_stats
from backend.core.errors import ValidationFailed, check_media_type
from backend.core.timeutil import now_kst, today_kst
from backend.domain import codes
from backend.domain.models import MorningSession, NightRecord, SessionStep
from backend.repositories.registry import Repositories

STREAK_DAYS = 365          # 연속 달성일을 셀 과거 기록 범위
MAX_PHOTO_BYTES = 10 * 1024 * 1024
PERIODS = ("week", "month", "year")


@dataclass(frozen=True)
class CalendarDay:
    day: date
    summary: ai_stats.Totals
    photo_url: str | None
    has_night_record: bool


@dataclass(frozen=True)
class DiaryDay:
    day: date
    summary: ai_stats.Totals
    night: NightRecord | None
    voice_url: str | None
    sessions: list[tuple[MorningSession, list[SessionStep]]]
    photo_url: str | None


class HistoryService:
    def __init__(self, repos: Repositories, clock=now_kst):
        self.r, self.clock = repos, clock

    def report(self, user_id: str, period: str, anchor: date | None) -> ai_stats.Report:
        if period not in PERIODS:
            raise ValidationFailed(f"period는 {'/'.join(PERIODS)} 중 하나예요")
        today = today_kst(self.clock())
        anchor = anchor or today
        start, end = ai_stats.period_bounds(period, anchor)
        rec = self.r.sessions.recent_records(user_id, min(start, today - timedelta(days=STREAK_DAYS)), max(end - timedelta(days=1), today))
        return ai_stats.report(rec, period, anchor, today)

    def _photo_url(self, path: str | None) -> str | None:
        return self.r.storage.signed_url("photo", path) if path else None

    def calendar(self, user_id: str, year: int, month: int) -> list[CalendarDay]:
        """한 달 캘린더: 날짜별 사진(있으면) 또는 루틴 성공률. 밤 기록은 RECORD_DATE 기준이라 하루 뒤까지 읽는다."""
        start = date(year, month, 1)
        end = date(year, month, calendar.monthrange(year, month)[1])
        rec = self.r.sessions.recent_records(user_id, start, end + timedelta(days=1))
        days = [start + timedelta(days=i) for i in range((end - start).days + 1)]
        summaries = ai_stats.day_summaries(rec, days)
        photos = {p.diary_date: p for p in self.r.storage.list_photos(user_id, start, end)}
        nights = {n.record_date for n in rec.nights}
        return [CalendarDay(d, summaries[d], self._photo_url(photos[d].photo_path) if d in photos else None, d in nights)
                for d in days]

    def diary(self, user_id: str, day: date) -> DiaryDay:
        rec = self.r.sessions.recent_records(user_id, day, day)
        night = self.r.nights.get_night_by_date(user_id, day)
        photo = self.r.storage.get_photo(user_id, day)
        sessions = [(s, [st for st in rec.steps if st.session_id == s.session_id]) for s in rec.sessions]
        voice = self.r.storage.signed_url("voice", night.voice_audio_path) if night and night.voice_audio_path else None
        return DiaryDay(day, ai_stats.day_summaries(rec, [day])[day], night, voice, sessions,
                        self._photo_url(photo.photo_path) if photo else None)

    def upload_photo(self, user_id: str, day: date, data: bytes, content_type: str | None = "image/jpeg") -> str | None:
        """하루 대표 사진 1장(다시 올리면 덮어씀). 오늘 이후 날짜는 받지 않는다."""
        if day > today_kst(self.clock()):
            raise ValidationFailed("미래 날짜에는 사진을 올릴 수 없어요")
        if not data:
            raise ValidationFailed("사진 파일이 비어 있어요")
        if len(data) > MAX_PHOTO_BYTES:
            raise ValidationFailed(f"사진은 {MAX_PHOTO_BYTES // (1024 * 1024)}MB 이하여야 해요")
        check_media_type(content_type, codes.PHOTO_MIME_TYPES)
        photo = self.r.storage.upload_photo(user_id, day, data)
        return self._photo_url(photo.photo_path)
