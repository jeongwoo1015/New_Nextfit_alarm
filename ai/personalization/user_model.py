"""User Model: 행동 기록을 통계로 가공한다. 판단은 하지 않고 숫자만 만든다.

같은 계산을 개인화 판단(Rule·Bandit)과 앱의 리포트·일기·모닝콜 종료 화면이 함께 쓰도록 만들었다
(화면의 기상 성공률, 평균 기상 시간, 루틴 완료율 52/70, 연속 달성일, 일별·주·월·연 집계).

가정(팀 확정 전, 실제 데이터를 보고 조정):
  - 기상 시각 = 알람 후 첫 응답 시각(FIRST_RESPONSE_AT). 정의서에 실제 기상 시각 컬럼이 없다.
  - 세션 행이 없는 날은 성공도 실패도 아닌 '기록 없음'이다. (울렸어야 하는 날을 알려면 알람 요일 정보가 필요하다.)
  - 연속 달성: 그날 세션 중 기상 성공이 하나라도 있으면 달성. 세션이 없는 날은 건너뛰되, 세션이 있는 두 날 사이가
    MAX_GAP_DAYS를 넘으면 끊긴다. 세션이 있는데 기상 성공이 없는 날도 끊긴다.
  - 루틴 완료율 = DONE 단계 ÷ 전체 단계(PENDING도 분모에 포함). 건너뜀 비율·항목별 수행률은 PENDING을 뺀다.
"""
from dataclasses import dataclass, field, replace
from datetime import date, timedelta

from .records import DONE, SKIPPED, SessionRecord

MIN_SESSIONS = 3      # 이보다 적으면 통계를 믿지 않는다 (가정값)
MAX_GAP_DAYS = 3      # 세션이 있는 두 날 사이가 이보다 길면 연속이 끊긴다 (금→월 주말을 허용하는 값)


def clock_str(minutes: float | None) -> str | None:
    """자정부터의 분 → 'HH:MM'."""
    if minutes is None:
        return None
    m = int(round(minutes))
    return f"{m // 60 % 24:02d}:{m % 60:02d}"


def _mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def _ratio(num: int, den: int) -> float | None:
    return num / den if den else None


@dataclass(frozen=True)
class UserStats:
    n_sessions: int = 0
    wake_success_rate: float | None = None      # 0~1, 기록이 없으면 None
    avg_rewake: float | None = None
    avg_reaction_sec: float | None = None       # 알람이 울린 시각을 알 수 있을 때만
    avg_wake_minutes: float | None = None       # 평균 기상 시각(자정부터의 분)
    avg_talk_min: float | None = None           # 평균 대화 시간(분)
    routine_done: int = 0                       # 수행한 단계 수 (루틴+콘텐츠)
    routine_total: int = 0                      # 전체 단계 수
    routine_completion_rate: float | None = None
    skip_ratio: float | None = None             # 건너뛴 단계 / (수행 + 건너뜀)
    routine_rates: dict = field(default_factory=dict)   # {template_item_id: 수행률} 루틴 단계만
    routine_counts: dict = field(default_factory=dict)  # {template_item_id: 기록된 횟수}
    last_condition: str | None = None           # 가장 최근 아침에 적용된 어젯밤 컨디션 코드
    current_streak: int = 0
    best_streak: int = 0

    @property
    def has_enough_data(self) -> bool:
        return self.n_sessions >= MIN_SESSIONS

    @property
    def avg_wake_clock(self) -> str | None:
        return clock_str(self.avg_wake_minutes)

    def to_dict(self) -> dict:
        return {"n_sessions": self.n_sessions, "wake_success_rate": self.wake_success_rate, "avg_rewake": self.avg_rewake,
                "avg_reaction_sec": self.avg_reaction_sec, "avg_wake_clock": self.avg_wake_clock, "avg_talk_min": self.avg_talk_min,
                "routine_done": self.routine_done, "routine_total": self.routine_total,
                "routine_completion_rate": self.routine_completion_rate, "skip_ratio": self.skip_ratio,
                "routine_rates": dict(self.routine_rates), "routine_counts": dict(self.routine_counts),
                "last_condition": self.last_condition, "current_streak": self.current_streak, "best_streak": self.best_streak}


DEFAULT_STATS = UserStats()    # 처음 쓰는 사용자


def _step_counts(sessions) -> tuple[int, int, int, int]:
    done = skipped = total = 0
    pending = 0
    for s in sessions:
        for st in s.active_steps:
            total += 1
            if st.status == DONE:
                done += 1
            elif st.status == SKIPPED:
                skipped += 1
            else:
                pending += 1
    return done, skipped, total, pending


def compute_streaks(sessions, upto: date | None = None) -> tuple[int, int]:
    """(현재 연속 달성일, 최장 연속 달성일). upto 이전(upto 포함)의 세션만 본다. 가정은 모듈 설명 참고."""
    by_day: dict[date, bool] = {}
    for s in sessions or []:
        if s.session_date is None or (upto is not None and s.session_date > upto):
            continue
        by_day[s.session_date] = by_day.get(s.session_date, False) or bool(s.wake_success)
    best = run = 0
    prev: date | None = None
    for day in sorted(by_day):
        if by_day[day]:
            run = run + 1 if prev is not None and by_day.get(prev) and (day - prev).days <= MAX_GAP_DAYS else 1
        else:
            run = 0
        best = max(best, run)
        prev = day
    if not by_day:
        return 0, 0
    last = max(by_day)
    current = run if by_day[last] and (upto is None or (upto - last).days <= MAX_GAP_DAYS) else 0
    return current, best


def aggregate(sessions) -> UserStats:
    """주어진 세션 전부로 통계를 만든다(기간 선택은 호출하는 쪽). 연속 달성일은 compute_stats가 따로 채운다."""
    sessions = sorted([s for s in (sessions or []) if s.session_date is not None], key=lambda s: s.session_date)
    if not sessions:
        return DEFAULT_STATS
    done, skipped, total, _ = _step_counts(sessions)
    per_item: dict[str, list[int]] = {}
    for s in sessions:
        for st in s.active_steps:
            if st.status in (DONE, SKIPPED) and st.item_key:
                per_item.setdefault(st.item_key, []).append(1 if st.status == DONE else 0)
    return UserStats(
        n_sessions=len(sessions),
        wake_success_rate=_mean(1.0 if s.wake_success else 0.0 for s in sessions),
        avg_rewake=_mean(s.rewake_count for s in sessions),
        avg_reaction_sec=_mean(s.reaction_sec for s in sessions),
        avg_wake_minutes=_mean(s.wake_minutes for s in sessions),
        avg_talk_min=_mean(s.talk_minutes for s in sessions),
        routine_done=done, routine_total=total, routine_completion_rate=_ratio(done, total),
        skip_ratio=_ratio(skipped, done + skipped),
        routine_rates={k: sum(v) / len(v) for k, v in per_item.items()},
        routine_counts={k: len(v) for k, v in per_item.items()},
        last_condition=sessions[-1].condition_code,
    )


def compute_stats(sessions, today: date | None = None, window_days: int = 7, include_today: bool = False) -> UserStats:
    """최근 window_days일의 세션으로 통계를 만든다. today가 None이면 받은 세션 전부를 쓴다.

    기본은 오늘을 뺀다(오늘 세션은 아직 결과를 모르기 때문). 리포트 화면처럼 오늘까지 보려면 include_today=True.
    연속 달성일은 기간과 상관없이 전체 기록으로 센다.
    """
    sessions = [s for s in (sessions or []) if s.session_date is not None]
    recent = sessions
    if today is not None:
        start = today - timedelta(days=window_days)
        recent = [s for s in sessions if start <= s.session_date and (s.session_date <= today if include_today else s.session_date < today)]
    base = aggregate(recent)
    upto = None if today is None else (today if include_today else today - timedelta(days=1))
    cur, best = compute_streaks(sessions, upto)
    return replace(base, current_streak=cur, best_streak=best)


# ---------------- 화면용 집계: 일별, 주·월·연 ----------------
@dataclass(frozen=True)
class Bucket:
    label: str
    start: date
    end: date                                  # 포함하지 않는 끝 날짜
    n_sessions: int = 0
    wake_success_rate: float | None = None
    avg_wake_minutes: float | None = None
    routine_done: int = 0
    routine_total: int = 0
    routine_completion_rate: float | None = None

    @property
    def avg_wake_clock(self) -> str | None:
        return clock_str(self.avg_wake_minutes)


def _bucket(label: str, start: date, end: date, sessions) -> Bucket:
    inside = [s for s in sessions if s.session_date is not None and start <= s.session_date < end]
    if not inside:
        return Bucket(label, start, end)
    st = aggregate(inside)
    return Bucket(label, start, end, st.n_sessions, st.wake_success_rate, st.avg_wake_minutes, st.routine_done, st.routine_total,
                  st.routine_completion_rate)


def day_summary(sessions, day: date) -> Bucket:
    """일기 화면의 하루 요약(기상 시간, 루틴 수행 6/7, 루틴 성공률)."""
    return _bucket(day.isoformat(), day, day + timedelta(days=1), sessions or [])


def period_series(sessions, period: str, anchor: date) -> list[Bucket]:
    """리포트 화면의 그래프 칸. period: 'week'(월~일 7칸) / 'month'(1주~5주, 1~7일=1주) / 'year'(1~12월). anchor가 속한 기간."""
    sessions = list(sessions or [])
    if period == "week":
        monday = anchor - timedelta(days=anchor.weekday())
        return [_bucket("월화수목금토일"[i], monday + timedelta(days=i), monday + timedelta(days=i + 1), sessions) for i in range(7)]
    if period == "month":
        first = anchor.replace(day=1)
        nxt = (first.replace(year=first.year + 1, month=1) if first.month == 12 else first.replace(month=first.month + 1))
        out, start, n = [], first, 1
        while start < nxt:
            end = min(start + timedelta(days=7), nxt)
            out.append(_bucket(f"{n}주", start, end, sessions))
            start, n = end, n + 1
        return out
    if period == "year":
        out = []
        for m in range(1, 13):
            start = date(anchor.year, m, 1)
            end = date(anchor.year + 1, 1, 1) if m == 12 else date(anchor.year, m + 1, 1)
            out.append(_bucket(f"{m}월", start, end, sessions))
        return out
    raise ValueError(f"period는 week/month/year 중 하나예요: {period!r}")
