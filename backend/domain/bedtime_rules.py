"""취침 통계 (팀 결정 11/06): 리포트의 취침 성공률·평균 취침 시각.

- 밤 대화를 마친 날 = 밤 기록(RS_NIGHT_RECORD)이 있는 RECORD_DATE. 굿나잇콜을 마치고 받아쓰기를 저장하면 생긴다.
- 취침 성공률 = 밤 대화를 마친 날 ÷ 전체 기록 기간의 날 수.
  기록 기간 = 첫 기록일(세션 또는 밤 기록 중 가장 이른 날) ~ 어제. 오늘 밤 대화를 이미 마쳤으면 오늘까지.
  (오늘 밤이 아직 오지 않았는데 분모에 넣으면 낮 동안 성공률이 떨어져 보이므로)
- 평균 취침 시각 = 밤 대화를 마친 시각(밤 기록이 처음 만들어진 시각, KST)의 평균 "HH:MM".
  자정을 넘기는 시각을 이어서 평균 내려고 정오 이전 시각은 하루 뒤로 본다(23:30·00:30 → 00:00).
"""
from dataclasses import dataclass
from datetime import date, timedelta

from backend.core.timeutil import to_kst
from backend.domain.models import NightRecord

DAY_MIN = 24 * 60
NOON_MIN = 12 * 60


@dataclass(frozen=True)
class BedStats:
    bed_days: int = 0
    bed_record_days: int = 0
    bedtime_success_rate: float | None = None
    avg_bed_clock: str | None = None


def record_span(first_day: date | None, nights: list[NightRecord], today: date) -> tuple[date, date] | None:
    """취침 성공률 분모로 세는 날짜 범위 [처음, 끝] (양 끝 포함). 기록이 없으면 None."""
    if first_day is None:
        return None
    last = today if any(n.record_date == today for n in nights) else today - timedelta(days=1)
    return (first_day, last) if first_day <= last else None


def avg_bed_clock(nights: list[NightRecord]) -> str | None:
    minutes = []
    for n in nights:
        if n.created_at is None:
            continue
        t = to_kst(n.created_at)
        m = t.hour * 60 + t.minute + t.second / 60
        minutes.append(m + DAY_MIN if m < NOON_MIN else m)
    if not minutes:
        return None
    avg = round(sum(minutes) / len(minutes)) % DAY_MIN
    return f"{avg // 60:02d}:{avg % 60:02d}"


def bed_stats(nights: list[NightRecord], span: tuple[date, date] | None, start: date, end: date) -> BedStats:
    """[start, end) 범위의 취침 통계. 분모는 span과 겹치는 날만 센다."""
    in_range = [n for n in nights if start <= n.record_date < end]
    days = {n.record_date for n in in_range}
    record_days = 0
    if span:
        lo, hi = max(start, span[0]), min(end - timedelta(days=1), span[1])
        record_days = max((hi - lo).days + 1, 0)
        days = {d for d in days if lo <= d <= hi}
    rate = len(days) / record_days if record_days else None
    return BedStats(len(days), record_days, rate, avg_bed_clock(in_range))
