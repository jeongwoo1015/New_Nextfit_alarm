"""깨우는 말의 강도 자동 조절(11/13 설계, 11/26 회의 4-5로 규칙 변경).

서버가 알람 통화(브리핑)를 시작하기 전에 today_intensity()를 불러 그 통화의 강도를 정하고, Persona.intensity로 넘기고,
세션 기록에 APPLIED_INTENSITY로 남긴다. 통화 중에는 강도를 바꾸지 않는다.

규칙 (11/26 회의: 당일 상태 기준)
- 기본은 사용자가 고른 강도(1~3).
- 그날 미루기나 재기상이 한 번이라도 있었으면, 그날 다음 통화부터 한 단계 올린다(최대 3). 횟수가 늘어도 한 단계까지만.
- 다음 날은 다시 사용자가 고른 강도로 시작한다(지난날 기록을 쌓아 올리지 않는다).
- 같은 통화 안에서 재기상 뒤에 올리지 않는다. 프롬프트의 조건 규칙으로 해 봤으나 실제 모델이 6번 중 2번만 따라서(11/26 시험) 뺐다.
- 자동 강도 올리기를 끄면 항상 고른 강도.
"""
from dataclasses import dataclass

MIN_LEVEL, MAX_LEVEL = 1, 3


@dataclass(frozen=True)
class WakeDay:
    """지난 알람 하나의 결과(옛 규칙용, 11/26부터 today_intensity는 쓰지 않는다. 서버 호환을 위해 남긴다)."""
    hard: bool
    applied: int | None = None


def is_hard_wake(wake_success: bool, rewake_count: int = 0) -> bool:
    return (not wake_success) or (rewake_count or 0) >= 1


def _clamp(n: int) -> int:
    return max(MIN_LEVEL, min(MAX_LEVEL, int(n)))


def today_intensity(base: int, history=None, auto: bool = True, snoozes_today: int = 0, rewakes_today: int = 0) -> int:
    """이번 통화에 쓸 강도. base는 사용자가 고른 강도(1~3). snoozes_today·rewakes_today는 오늘 지금까지의 미루기·재기상 횟수(버튼·말 합산).
    history는 옛 호출과 맞추려고 받기만 하고 쓰지 않는다. 서버가 오늘 횟수를 넘기지 않으면 고른 강도 그대로다."""
    base = _clamp(base if base is not None else 2)
    if not auto:
        return base
    if (snoozes_today or 0) > 0 or (rewakes_today or 0) > 0:
        return _clamp(base + 1)
    return base


def today_counts(sessions, today) -> tuple[int, int]:
    """ai.personalization.records의 SessionRecord 목록에서 오늘 세션의 (미루기 횟수, 재기상 횟수) 합. 서버가 today_intensity에 넘길 값을 만들 때 쓴다."""
    snoozes = rewakes = 0
    for s in sessions or ():
        if getattr(s, "session_date", None) == today:
            snoozes += int(getattr(s, "snooze_count", 0) or 0)
            rewakes += int(getattr(s, "rewake_count", 0) or 0)
    return snoozes, rewakes


def history_from_sessions(sessions) -> list[WakeDay]:
    """ai.personalization.records의 SessionRecord 목록(오래된 것부터)을 WakeDay로. applied_intensity가 없으면 None."""
    return [WakeDay(is_hard_wake(getattr(s, "wake_success", False), getattr(s, "rewake_count", 0)), getattr(s, "applied_intensity", None))
            for s in sessions]
