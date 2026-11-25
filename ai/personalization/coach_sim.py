"""코칭 강도 개인화 가상 사용자 시뮬레이션(11/25). 기상 볼륨(`wake_sim.py`)과 따로 배우는 두 번째 개인화다.

sim.py(옛 구조)와 다른 점: 코칭 강도가 영향을 줄 수 있는 결과만 본다.
  - 결과 = 그날 코칭 단계를 얼마나 해냈는가(단계 수행 비율). 보상은 서버가 쓰는 `reward.coaching_reward`와 같다.
  - 기상 성공은 알람 소리의 몫이라 보상에 넣지 않는다(11/21 결정). 기상 성공은 '최근 기상 성공률' 상황 칸을 나누는 데만 쓰고, 코칭과 무관하게 정해진다.
  - 설정용 가상 사용자(SET_TYPES)와 검증용(VAL_TYPES)을 나눈다.

주의: 가상 사용자의 반응(전략별 수행 확률)은 우리가 정한 가정값이다. 알고리즘이 사용자별로 갈라 배우는지와 설정값의 안정성만 본다. 효과의 근거가 아니다.

사용법: python -m ai.personalization.coach_sim
"""
import random
from datetime import date, timedelta

from .agent import decide, learn
from .bandit import ARMS, WAKE_LOW, ContextualBandit, allowed_arms
from .records import DONE, PENDING, SessionRecord, StepRecord
from .reward import coaching_reward
from .user_model import compute_stats

ARM_NAMES = tuple(ARMS)                      # standard, gentle, push
INTENSITY_ARM = {"normal": "standard", "soft": "gentle", "firm": "push"}
# 전략별 단계 수행 확률: (좋은 날, 피곤한 날). 피곤한 날은 push가 후보에서 빠지므로 push 값은 쓰이지 않는다.
RESPONSE = {
    "soft_lover":   {"standard": (0.65, 0.55), "gentle": (0.85, 0.75), "push": (0.40, 0.30)},   # 부드러운 말에 잘 반응
    "firm_lover":   {"standard": (0.65, 0.55), "gentle": (0.50, 0.40), "push": (0.85, 0.75)},   # 단호한 말에 잘 반응
    "standard_best": {"standard": (0.80, 0.70), "gentle": (0.65, 0.55), "push": (0.60, 0.50)},  # 기본이 가장 잘 맞음
    "tired_split":  {"standard": (0.65, 0.50), "gentle": (0.55, 0.80), "push": (0.85, 0.30)},   # 좋은 날은 단호, 피곤한 날은 부드럽게(상황 학습)
    "flat":         {"standard": (0.65, 0.55), "gentle": (0.65, 0.55), "push": (0.65, 0.55)},   # 반응 차이 없음(대조군)
    "lowwake_soft": {"standard": (0.80, 0.70), "gentle": (0.65, 0.55), "push": (0.60, 0.50)},   # 평소엔 기본이 맞고(standard_best와 같음), 최근 기상이 잘 안 되는 때(상황 칸 'low')만 부드러운 말이 맞음
}
LOW_WAKE_RESPONSE = {"standard": (0.65, 0.55), "gentle": (0.85, 0.75), "push": (0.40, 0.30)}      # lowwake_soft가 기상이 잘 안 되는 때의 반응
SET_TYPES = ("soft_lover", "firm_lover", "standard_best", "tired_split")
VAL_TYPES = ("flat", "habit_change", "kind_firm", "lowwake_soft")
ALL_TYPES = SET_TYPES + VAL_TYPES
POLICIES = ("random", "standard", "rule", "bandit", "bandit_eps")      # bandit = Thompson(서버 기본), bandit_eps = ε-greedy(옛 방식)
TIRED_RATE = 0.2                 # 어젯밤 피곤했던 날의 비율
WAKE_RATE = 0.65                 # 기상 성공 비율(코칭과 무관, 상황 칸을 나누는 데만 씀)
STEPS = 4                        # 하루 코칭 단계 수
CHANGE_DAY = 30                  # habit_change가 바뀌는 날
EVAL_FROM = 21                   # 이 날부터 평가한다(앞은 학습 기간)
START = date(2026, 10, 1)


def response_of(user_type: str, day: int, low_wake: bool = False) -> dict:
    if user_type == "lowwake_soft" and low_wake:
        return LOW_WAKE_RESPONSE
    if user_type == "habit_change":
        return RESPONSE["soft_lover"] if day < CHANGE_DAY else RESPONSE["firm_lover"]
    if user_type == "kind_firm":
        return RESPONSE["firm_lover"]                 # 말투 설정이 KIND라 push가 후보에서 빠진다
    return RESPONSE[user_type]


def best_arm(resp: dict, tired: bool, speech_style: str | None) -> str:
    """정답 전략: 오늘 쓸 수 있는 전략 중 수행 확률이 가장 높은 것(같으면 앞쪽 = 기본 전략)."""
    pool = allowed_arms("TIRED" if tired else "GOOD", speech_style)
    return max(pool, key=lambda a: (resp[a][1 if tired else 0], -ARM_NAMES.index(a)))


def run_policy(policy: str, user_type: str, days: int = 60, seed: int = 0, prior_weight: float = 2.0, features=("tired",)) -> dict:
    if policy not in POLICIES or user_type not in ALL_TYPES:
        raise ValueError(f"policy는 {POLICIES}, user_type은 {ALL_TYPES} 중 하나예요")
    speech = "KIND" if user_type == "kind_firm" else None
    env, pick = random.Random(seed), random.Random(seed + 1)
    bandit = ContextualBandit(policy="epsilon" if policy == "bandit_eps" else "thompson", prior_weight=prior_weight, features=features, mc_samples=0)
    sessions: list[SessionRecord] = []
    arms, correct, done_rate, oracle_rate, push_on_tired, fallbacks = [], [], [], [], 0, 0
    for i in range(days):
        today = START + timedelta(days=i)
        tired = env.random() < TIRED_RATE
        wake = env.random() < WAKE_RATE
        u_steps = [env.random() for _ in range(STEPS)]            # 정책과 상관없이 같은 날은 같은 운
        cond = "TIRED" if tired else "GOOD"
        stats = compute_stats(sessions, today)
        low_wake = stats.has_enough_data and stats.wake_success_rate is not None and stats.wake_success_rate < WAKE_LOW     # bandit의 상황 칸 'wake'와 같은 기준
        resp = response_of(user_type, i, low_wake)
        log = None
        if policy == "random":
            arm = pick.choice(allowed_arms(cond, speech))
        elif policy == "standard":
            arm = "standard"
        else:
            d = decide("sim-user", sessions, today, condition=cond, speech_style=speech, mode="rule" if policy == "rule" else "bandit", bandit=bandit, rng=pick)
            arm, log = INTENSITY_ARM[d.strategy.coaching_intensity], d.log     # 규칙은 이름이 다른 전략(rule)을 만들 수 있어 코칭 강도로 가장 가까운 전략에 맞춘다
            fallbacks += log.source == "fallback"
        p = resp[arm][1 if tired else 0]
        statuses = [DONE if u < p else PENDING for u in u_steps]
        rec = SessionRecord(session_date=today, wake_success=wake, condition_code=cond, call_type="ROUTINE",
                            steps=[StepRecord(step_name=f"s{j}", status=st, step_order=j + 1, template_item_id=f"t{j}") for j, st in enumerate(statuses)])
        sessions.append(rec)
        reward = coaching_reward(rec)
        if log is not None and reward is not None:
            learn(bandit, log, reward)
        target = best_arm(resp, tired, speech)
        arms.append(arm)
        correct.append(arm == target)
        done_rate.append(reward)
        oracle_rate.append(resp[target][1 if tired else 0])
        push_on_tired += tired and arm == "push"
    ev = slice(EVAL_FROM, days)
    n = days - EVAL_FROM

    def conv() -> int | None:
        return next((i for i in range(0, days - 9) if sum(correct[i:i + 10]) >= 8), None)
    return {"policy": policy, "user_type": user_type, "days": days, "arms": arms, "correct_rate": sum(correct[ev]) / n,
            "avg_done": sum(done_rate[ev]) / n, "oracle_done": sum(oracle_rate[ev]) / n, "push_on_tired": push_on_tired,
            "fallbacks": fallbacks, "converge_day": conv(), "bandit": bandit}


def run_all(types=ALL_TYPES, policies=POLICIES, days: int = 60, reps: int = 20, prior_weight: float = 2.0, features=("tired",)) -> dict:
    """{user_type: {policy: {지표: 반복 평균}}}. 지표: correct_rate(정답 전략 선택 비율), avg_done(평균 단계 수행 비율), oracle_done(정답만 골랐을 때의 값), converge_day."""
    out = {}
    for t in types:
        out[t] = {}
        for p in policies:
            runs = [run_policy(p, t, days, 1000 + r, prior_weight, features) for r in range(reps)]
            row = {m: sum(r[m] for r in runs) / reps for m in ("correct_rate", "avg_done", "oracle_done", "push_on_tired", "fallbacks")}
            conv = [r["converge_day"] for r in runs if r["converge_day"] is not None]
            row["converge_day"] = sum(conv) / len(conv) if conv else None
            out[t][p] = row
    return out


def format_table(result: dict, metric: str = "correct_rate", policies=POLICIES) -> str:
    lines = [f"[{metric}] 사용자 유형 | " + " | ".join(f"{p:>10}" for p in policies)]
    for t, row in result.items():
        cells = [f"{'-':>10}" if row[p][metric] is None else f"{row[p][metric]:10.2f}" for p in policies]
        lines.append(f"{t:>14} | " + " | ".join(cells))
    return "\n".join(lines)


if __name__ == "__main__":
    res = run_all()
    for m in ("correct_rate", "avg_done", "converge_day"):
        print(format_table(res, m) + "\n")
    print(f"※ 평가는 {EVAL_FROM}일째부터. 설정용={SET_TYPES} / 검증용={VAL_TYPES}. 가상 사용자의 반응은 우리가 정한 값이라 효과의 근거가 아니다.")
