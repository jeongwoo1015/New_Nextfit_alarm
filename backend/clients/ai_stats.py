"""리포트·일기 통계 — AI 파트 `ai.personalization.user_model`을 부른다 (Gemini 키·네트워크 불필요).

개인화 판단과 리포트가 같은 계산을 쓰도록 AI 파트가 만든 함수다(기상 성공률, 평균 기상 시간, 루틴 완료율, 연속 달성일,
주·월·연 칸). 가정(기상 시각 = 첫 응답, 세션 없는 날은 건너뛰되 3일 넘게 비면 연속이 끊김 등)도 그쪽 설명을 따른다.
"""
from dataclasses import asdict, dataclass
from datetime import date

from backend.core.errors import ServiceUnavailable
from backend.domain.models import RecentRecords


@dataclass(frozen=True)
class Totals:
    n_sessions: int = 0
    wake_success_rate: float | None = None
    avg_wake_clock: str | None = None          # "HH:MM"
    routine_done: int = 0
    routine_total: int = 0
    routine_completion_rate: float | None = None
    bed_days: int = 0                          # 취침 통계는 서버가 채운다 (domain/bedtime_rules.py)
    bed_record_days: int = 0
    bedtime_success_rate: float | None = None
    avg_bed_clock: str | None = None           # "HH:MM"


@dataclass(frozen=True)
class Bucket(Totals):
    label: str = ""
    start: date | None = None
    end: date | None = None                    # 포함하지 않는 끝 날짜


@dataclass(frozen=True)
class Report:
    period: str
    start: date
    end: date                                  # 포함하지 않는 끝 날짜
    overall: Totals                            # 화면 상단: 전체 기간 누적 (기상 성공률·루틴 성공률)
    totals: Totals                             # 선택한 기간 합계 (그래프 영역의 평균 기상 시간·루틴 완료율)
    series: list[Bucket]
    current_streak: int
    best_streak: int


def _ai():
    try:
        from ai.personalization import records, user_model
    except ImportError:
        raise ServiceUnavailable("통계 모듈(ai 패키지)이 없어요", "AI_UNAVAILABLE") from None
    return records, user_model


def _sessions(rec: RecentRecords):
    records, _ = _ai()
    return records.build_sessions([asdict(s) for s in rec.sessions], [asdict(s) for s in rec.steps],
                                  [asdict(n) for n in rec.nights])


def _totals(x) -> dict:
    return dict(n_sessions=x.n_sessions, wake_success_rate=x.wake_success_rate, avg_wake_clock=x.avg_wake_clock,
                routine_done=x.routine_done, routine_total=x.routine_total, routine_completion_rate=x.routine_completion_rate)


def period_bounds(period: str, anchor: date) -> tuple[date, date]:
    """anchor가 속한 기간 [시작, 끝) — 그래프 칸의 처음과 끝."""
    _, um = _ai()
    buckets = um.period_series([], period, anchor)
    return buckets[0].start, buckets[-1].end


def report(rec: RecentRecords, period: str, anchor: date, today: date) -> Report:
    """rec에는 선택한 기간과 전체 기록(누적·연속 달성일용)이 함께 들어 있어야 한다."""
    _, um = _ai()
    sessions = _sessions(rec)
    series = um.period_series(sessions, period, anchor)
    start, end = series[0].start, series[-1].end
    totals = um.aggregate([s for s in sessions if s.session_date and start <= s.session_date < end])
    overall = um.aggregate([s for s in sessions if s.session_date and s.session_date <= today])
    stats = um.compute_stats(sessions, today, include_today=True)
    return Report(period, start, end, Totals(**_totals(overall)), Totals(**_totals(totals)),
                  [Bucket(**_totals(b), label=b.label, start=b.start, end=b.end) for b in series],
                  stats.current_streak, stats.best_streak)


def day_summaries(rec: RecentRecords, days: list[date]) -> dict[date, Totals]:
    """일기 캘린더·상세의 하루 요약(기상 시간, 루틴 6/7, 루틴 성공률)."""
    _, um = _ai()
    sessions = _sessions(rec)
    return {d: Totals(**_totals(um.day_summary(sessions, d))) for d in days}
