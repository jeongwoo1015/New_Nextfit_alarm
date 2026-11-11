"""기록 화면: 리포트, 한 줄 일기 캘린더·상세, 일기 사진.

통계 계산은 AI 파트 user_model(clients/ai_stats.py)을 쓰고, 여기서는 기간 데이터를 모아 넘긴다.
일기 날짜 D = RECORD_DATE가 D인 밤 기록 + SESSION_DATE가 D인 아침 세션 + DIARY_DATE가 D인 사진 (정의서 공통 규칙).
"""
import calendar
from dataclasses import asdict, dataclass, replace
from datetime import date, timedelta

from backend.clients import ai_stats
from backend.core.errors import ValidationFailed, check_media_type
from backend.core.timeutil import now_kst, today_kst
from backend.domain import bedtime_rules, codes
from backend.domain.models import MorningSession, NightRecord, SessionStep
from backend.repositories.registry import Repositories

ALL_HISTORY_SINCE = date(2020, 1, 1)   # 누적·연속 달성일은 전체 기록으로 센다 (서비스 시작 이전 날짜)
MAX_PHOTO_BYTES = 10 * 1024 * 1024
PERIODS = ("week", "month", "year")


@dataclass(frozen=True)
class CalendarDay:
    day: date
    summary: ai_stats.Totals
    photo_url: str | None
    has_night_record: bool
    condition_label: str | None = None     # 그날 밤 기록의 컨디션 (GOOD/NORMAL/TIRED/STRESSED), 없으면 None


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
        """상단(기상·루틴 성공률)은 전체 기간 누적, 그래프와 그 영역의 합계는 선택한 주·월·연 (팀 결정 11/01)."""
        if period not in PERIODS:
            raise ValidationFailed(f"period는 {'/'.join(PERIODS)} 중 하나예요")
        today = today_kst(self.clock())
        anchor = anchor or today
        start, end = ai_stats.period_bounds(period, anchor)
        # 밤 기록은 APPLY_DATE(= RECORD_DATE + 1)로 걸러지므로 하루 더 읽어야 오늘 밤 기록까지 들어온다 (취침 통계)
        rec = self.r.sessions.recent_records(user_id, ALL_HISTORY_SINCE, max(end - timedelta(days=1), today) + timedelta(days=1))
        return _with_bedtime(ai_stats.report(rec, period, anchor, today), rec, today)

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
        nights = {n.record_date: n for n in rec.nights}
        return [CalendarDay(d, summaries[d], self._photo_url(photos[d].photo_path) if d in photos else None, d in nights,
                            nights[d].condition_label if d in nights else None)
                for d in days]

    def diary(self, user_id: str, day: date) -> DiaryDay:
        rec = self.r.sessions.recent_records(user_id, day, day)
        night = self.r.nights.get_night_by_date(user_id, day)
        photo = self.r.storage.get_photo(user_id, day)
        # 하루에 모닝콜이 여럿이면 알람이 먼저 울린 순 (울리지 않은 세션은 뒤). 저장소 순서는 SESSION_ID(uuid)라 무작위
        ordered = sorted(rec.sessions, key=lambda s: (s.alarm_rang_at is None, s.alarm_rang_at.timestamp() if s.alarm_rang_at else 0))
        sessions = [(s, [st for st in rec.steps if st.session_id == s.session_id]) for s in ordered]
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

    def delete_photo(self, user_id: str, day: date) -> None:
        """하루 대표 사진 지우기. 사진이 없어도 성공(다시 눌러도 같은 결과)."""
        self.r.storage.delete_photo(user_id, day)


def _with_bedtime(rep: ai_stats.Report, rec, today: date) -> ai_stats.Report:
    """리포트에 취침 통계(팀 결정 11/06)를 더한다: 상단은 전체 기록, 합계·그래프 칸은 각자의 범위."""
    nights = [n for n in rec.nights if n.record_date <= today]
    first = min([s.session_date for s in rec.sessions if s.session_date <= today] + [n.record_date for n in nights], default=None)
    span = bedtime_rules.record_span(first, nights, today)

    def add(t, start, end):
        return replace(t, **asdict(bedtime_rules.bed_stats(nights, span, start, end)))

    return replace(rep, overall=add(rep.overall, ALL_HISTORY_SINCE, today + timedelta(days=1)),
                   totals=add(rep.totals, rep.start, rep.end),
                   series=[add(b, b.start, b.end) for b in rep.series])
