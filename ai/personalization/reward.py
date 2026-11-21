"""보상(Reward) 정의. Bandit이 '결과가 좋았나'를 0~1 숫자로 보는 기준.

기본안(팀 확정 전 가정): (기상 성공 1점 + 단계 수행 비율 1점) ÷ 2.
단계 수행 비율 = DONE 단계 ÷ 전체 단계 (PENDING은 수행하지 않은 것). 코칭을 하지 않은 날(coaching_skipped이거나 단계 없음)은 기상 성공만 본다.
가중치와 재기상 감점은 RewardConfig로 바꾼다.
"""
from dataclasses import dataclass

from .records import SessionRecord


@dataclass(frozen=True)
class RewardConfig:
    wake_weight: float = 1.0
    routine_weight: float = 1.0
    rewake_penalty: float = 0.0     # 재기상 1회당 감점 (기본은 사용 안 함)


def coaching_reward(session: SessionRecord) -> float | None:
    """코칭 강도 Bandit의 보상(11/21: 기상 볼륨과 보상을 분리). 말하는 방식(코칭 강도)이 영향을 줄 수 있는 결과인 '단계 수행 비율'만 본다.
    코칭을 안 한 날과 단계가 없는 날은 None(학습하지 않는다). 기상 성공은 알람 소리의 몫이라 여기에 넣지 않는다(wake_volume.WakeLadder)."""
    steps = session.active_steps
    if session.coaching_skipped or not steps:
        return None
    return session.steps_done / len(steps)


def session_reward(session: SessionRecord, config: RewardConfig = RewardConfig()) -> float:
    wake = 1.0 if session.wake_success else 0.0
    steps = session.active_steps
    if steps and (config.wake_weight + config.routine_weight) > 0:
        routine = session.steps_done / len(steps)
        value = (config.wake_weight * wake + config.routine_weight * routine) / (config.wake_weight + config.routine_weight)
    else:
        value = wake
    value -= config.rewake_penalty * session.rewake_count
    return max(0.0, min(1.0, value))
