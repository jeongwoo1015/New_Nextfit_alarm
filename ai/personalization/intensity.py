"""깨우는 말의 강도 자동 조절(11/13 설계, 회의록 "잘 못 일어나면 개인화로 강도 상승").

서버가 알람 세션을 만들기 전에 today_intensity()를 불러 그날 강도를 정하고, Persona.intensity로 넘기고,
세션 기록에 APPLIED_INTENSITY로 남긴다. 통화 중 재기상 뒤의 +1은 프롬프트가 처리한다(브리핑의 [깨우는 말의 강도]).

규칙 (공유 페이지와 같음)
- 힘들게 일어난 날 = 기상 미확인(wake_success=False) 또는 재기상(rewake_count) 1번 이상.
- 최근 알람 3번 중 2번 이상이 힘들게 일어난 날이면 다음 알람부터 한 단계 올린다(최대 3).
- 연속 3번 바로 일어나면 한 단계씩 사용자가 고른 강도로 돌아간다. 고른 강도보다 낮아지지는 않는다.
- 한 번 바꾼 뒤에는 그 뒤로 알람 3번이 지나야 다시 바꾼다(매일 한 단계씩 치솟지 않게).
- 자동 강도 올리기를 끄면 항상 고른 강도.
"""
from dataclasses import dataclass

MIN_LEVEL, MAX_LEVEL = 1, 3
WINDOW = 3          # 최근 몇 번을 보는지
HARD_NEEDED = 2     # 그중 몇 번 힘들면 올리는지


@dataclass(frozen=True)
class WakeDay:
    """지난 알람 하나의 결과 (RS_MORNING_SESSION 한 행, 오래된 것부터)."""
    hard: bool                      # 힘들게 일어났나 (is_hard_wake)
    applied: int | None = None      # 그날 실제로 쓴 강도(APPLIED_INTENSITY). 기록이 없으면 None


def is_hard_wake(wake_success: bool, rewake_count: int = 0) -> bool:
    return (not wake_success) or (rewake_count or 0) >= 1


def _clamp(n: int) -> int:
    return max(MIN_LEVEL, min(MAX_LEVEL, int(n)))


def today_intensity(base: int, history: list[WakeDay], auto: bool = True) -> int:
    """오늘 알람에 쓸 강도. base는 사용자가 고른 강도(1~3), history는 지난 알람 결과(오래된 것부터)."""
    base = _clamp(base if base is not None else 2)
    if not auto or not history:
        return base
    level, since_change = base, WINDOW          # 처음에는 바로 바꿀 수 있다
    for i, day in enumerate(history):
        # 그날 쓴 강도가 기록돼 있으면 그 값을 따라간다(서버 기록이 진실)
        if day.applied is not None and _clamp(day.applied) != level:
            level, since_change = _clamp(day.applied), 0
        since_change += 1
        recent = history[max(0, i - WINDOW + 1): i + 1]
        if since_change < WINDOW or len(recent) < WINDOW and sum(d.hard for d in recent) < HARD_NEEDED:
            continue
        hard = sum(d.hard for d in recent)
        if hard >= HARD_NEEDED and level < MAX_LEVEL:
            level, since_change = level + 1, 0
        elif len(recent) == WINDOW and hard == 0 and level > base:
            level, since_change = level - 1, 0
    return max(base, level)


def history_from_sessions(sessions) -> list[WakeDay]:
    """ai.personalization.records의 SessionRecord 목록(오래된 것부터)을 WakeDay로. applied_intensity가 없으면 None."""
    return [WakeDay(is_hard_wake(getattr(s, "wake_success", False), getattr(s, "rewake_count", 0)), getattr(s, "applied_intensity", None))
            for s in sessions]
