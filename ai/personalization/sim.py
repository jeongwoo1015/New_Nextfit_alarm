"""가상 사용자 시뮬레이션: 기록 → 통계 → 전략 선택(Rule/Bandit) → 하루 결과 점수 → 학습이 끝까지 이어져 도는지 확인한다.

주의: 여기서 나오는 숫자는 '코드가 의도대로 이어져 동작하는지'를 보는 용도다. 가상 사용자의 반응(아래 USER_TYPES)은 우리가 정한
값이라, 사용자가 실제로 이 전략에 반응하는지는 알 수 없다. 개인화 효과의 근거로 쓰면 안 된다(실제 사용자 데이터로만 평가한다).

가상 사용자 3종: 부드러운 코칭에 잘 반응(soft) / 단호한 코칭에 잘 반응(firm) / 반응 차이 없음(flat).
정책 4종: random(후보 중 무작위), standard(항상 기본), rule(규칙), bandit(학습).
모든 정책이 같은 날씨(같은 컨디션·같은 무작위 값)를 겪도록 환경 난수를 따로 둔다.

실행: python -m ai.personalization.sim
"""
import random
from datetime import date, timedelta

from .agent import decide, learn
from .bandit import ARMS, ContextualBandit, allowed_arms
from .records import DONE, PENDING, SessionRecord, StepRecord
from .reward import session_reward

# 코칭 강도별 기상 성공 확률
USER_TYPES = {
    "soft": {"soft": 0.85, "normal": 0.65, "firm": 0.40},
    "firm": {"soft": 0.50, "normal": 0.65, "firm": 0.85},
    "flat": {"soft": 0.65, "normal": 0.65, "firm": 0.65},
}
POLICIES = ("random", "standard", "rule", "bandit")
TIRED_RATE = 0.2          # 어젯밤 피곤했던 날의 비율
TIRED_PENALTY = 0.1       # 피곤한 날 기상 성공 확률 감소
START = date(2026, 10, 1)


def _day_outcome(user_type: str, intensity: str, tired: bool, u_wake: float, u_steps: list) -> tuple[bool, list]:
    p = max(0.0, USER_TYPES[user_type][intensity] - (TIRED_PENALTY if tired else 0.0))
    wake = u_wake < p
    done_p = 0.9 if wake else 0.3
    return wake, [DONE if u < done_p else PENDING for u in u_steps]


def run_policy(policy: str, user_type: str, days: int = 100, seed: int = 0, speech_style: str | None = None) -> dict:
    """한 정책으로 days일을 돌린다. 반환: 날마다의 점수, 뒤 절반 평균 점수, 점검 값(피곤한 날 push, fallback 등)."""
    if policy not in POLICIES or user_type not in USER_TYPES:
        raise ValueError(f"policy는 {POLICIES}, user_type은 {tuple(USER_TYPES)} 중 하나예요")
    env = random.Random(seed)              # 환경 난수: 정책과 상관없이 같은 날은 같은 값
    pick = random.Random(seed + 1)         # 정책 쪽 난수
    bandit = ContextualBandit()
    sessions: list[SessionRecord] = []
    rewards, arms, push_on_tired, fallbacks, errors = [], [], 0, 0, 0
    for i in range(days):
        today = START + timedelta(days=i)
        tired = env.random() < TIRED_RATE
        u_wake, u_steps = env.random(), [env.random() for _ in range(4)]
        cond = "TIRED" if tired else "GOOD"
        log = None
        if policy == "random":
            strategy = ARMS[pick.choice(allowed_arms(cond, speech_style))]
        elif policy == "standard":
            strategy = ARMS["standard"]
        else:
            d = decide("sim-user", sessions, today, condition=cond, speech_style=speech_style, mode=policy, bandit=bandit, rng=pick)
            strategy, log = d.strategy, d.log
            fallbacks += log.source == "fallback"
            errors += bool(log.error)
        wake, statuses = _day_outcome(user_type, strategy.coaching_intensity, tired, u_wake, u_steps)
        rec = SessionRecord(session_date=today, wake_success=wake, condition_code=cond, call_type="ROUTINE",
                            steps=[StepRecord(step_name=f"s{j}", status=st, step_order=j + 1, template_item_id=f"t{j}") for j, st in enumerate(statuses)])
        reward = session_reward(rec)
        sessions.append(rec)
        if log is not None:
            learn(bandit, log, reward)
        rewards.append(reward)
        arms.append(strategy.strategy_id)
        push_on_tired += tired and strategy.coaching_intensity == "firm"
    half = rewards[days // 2:]
    return {"policy": policy, "user_type": user_type, "days": days, "rewards": rewards, "arms": arms,
            "back_half_avg": sum(half) / len(half), "push_on_tired": push_on_tired, "fallbacks": fallbacks, "errors": errors}


def run_all(days: int = 100, seeds=(0, 1, 2, 3, 4)) -> dict:
    """{user_type: {policy: 여러 시드 평균의 뒤 절반 평균 점수}}"""
    return {u: {p: sum(run_policy(p, u, days, s)["back_half_avg"] for s in seeds) / len(seeds) for p in POLICIES} for u in USER_TYPES}


def format_table(result: dict) -> str:
    lines = ["사용자 유형 | " + " | ".join(f"{p:>8}" for p in POLICIES)]
    for u, row in result.items():
        lines.append(f"{u:>11} | " + " | ".join(f"{row[p]:8.3f}" for p in POLICIES))
    return "\n".join(lines)


if __name__ == "__main__":
    print(format_table(run_all()))
    print("\n※ 가상 사용자의 반응은 우리가 정한 값이라 개인화 효과의 근거가 아닙니다(코드가 이어져 도는지 확인하는 용도).")
