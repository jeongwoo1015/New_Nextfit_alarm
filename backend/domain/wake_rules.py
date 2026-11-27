"""기상 알람 시작 볼륨 개인화(11/20 회의 2·3, AI CONTRACTS B23)에서 서버가 정하는 값.

시작 단계를 고르고 학습하는 계산은 AI(ai.personalization.wake_volume)가 하고, 여기서는 그 입력을 만든다:
대상 알람(그날 가장 먼저 울리는 모닝콜), 예상 수면 시간, 중요한 날, 직전 단계, 확인 성공 여부.
"""
from datetime import date, datetime

from backend.core.timeutil import to_kst

from . import codes
from .alarm_rules import ring_at, rings_on
from .models import Alarm, MorningSession

REWAKE_OFFSET = 1                     # 재기상 음악 = 시작 단계 + 1 (AI wake_volume.REWAKE_OFFSET, 2~6단계)
IMPORTANT_BEFORE = "12:00"            # 내일 이 시각 전에 시작하는 일정(종일 일정 제외)이 있으면 중요한 날 (서버 기본안)
RAISE_SOURCES = ("bandit", "important_day")   # 이 출처로 어제보다 올라가면 밤 대화로 알려야 한다 (AI raise_pending)
LOOKBACK_DAYS = 30                    # 직전 단계를 찾을 기간
MAX_LOOKAHEAD_DAYS = 8                # 다음 알람을 찾을 기간


def rewake_level(level: int) -> int:
    return min(int(level) + REWAKE_OFFSET, max(codes.WAKE_LEVELS) + REWAKE_OFFSET)


def first_alarm_on(alarms: list[Alarm], d: date) -> Alarm | None:
    """그날 가장 먼저 울리는 모닝콜 — 기상 볼륨 기록(하루 1행)은 이 알람의 결과로 학습한다."""
    ringing = [(ring_at(a, d), a.alarm_id, a) for a in alarms if rings_on(a, d)]
    return min(ringing)[2] if ringing else None


def next_morning(alarms: list[Alarm], now: datetime) -> tuple[date, Alarm] | None:
    """지금 이후 처음 울릴 알람이 있는 아침과 그날의 첫 알람. 오늘 첫 알람이 이미 울렸어도 남은 알람이 있으면 오늘."""
    now = to_kst(now)
    for i in range(MAX_LOOKAHEAD_DAYS):
        d = date.fromordinal(now.date().toordinal() + i)
        if any(rings_on(a, d) and ring_at(a, d) > now for a in alarms):
            return d, first_alarm_on(alarms, d)
    return None


def sleep_minutes(now: datetime, alarm: Alarm, d: date) -> float:
    """예상 수면 가능 시간(분) = 지금 ~ 그날 첫 알람. AI 정의는 '밤 대화 종료 ~ 알람 받은 시각'이고 결정은 그 전에 하므로 알람 시각으로 어림한다."""
    return max(0.0, (ring_at(alarm, d) - to_kst(now)).total_seconds() / 60)


def is_important_day(events) -> bool:
    """내일 오전 일정(시작 시각이 IMPORTANT_BEFORE 전, 종일 일정 제외)이 있으면 탐색 없이 확실한 단계로."""
    return any(not e.all_day and e.start and e.start < IMPORTANT_BEFORE for e in events)


def previous_level(rows: list[dict], d: date) -> int | None:
    """d 전날까지 가장 최근 아침의 단계: 실제로 울린 시작 단계, 없으면 정해 둔 적용 단계."""
    for r in sorted(rows, key=lambda r: r["session_date"], reverse=True):
        if r["session_date"] < d.isoformat() and (r.get("start_level") or r.get("applied_level")):
            return r.get("start_level") or r.get("applied_level")
    return None


def raise_pending(row: dict | None) -> bool:
    """어제보다 높게 시작할 예정이라 밤 대화로 알려야 하는가."""
    return bool(row and row.get("previous_level") and row.get("applied_level")
                and row["applied_level"] > row["previous_level"] and row.get("source") in RAISE_SOURCES)


def confirmed(s: MorningSession) -> bool:
    """받은 뒤 확인 질문에 답했고 재기상(8초 무응답 음악)이 없었는가. RS_WAKE_LOG.CONFIRMED 기록용이고 볼륨 학습에는 쓰지 않는다.
    11/26 결정(AI B28): 볼륨은 첫 반응 단계만 학습하고, 미루기는 이 값에도 넣지 않는다(미루면 그날 기상 실패라는 지표는 judge_wake)."""
    return s.wake_checked_at is not None and s.rewake_count == 0
