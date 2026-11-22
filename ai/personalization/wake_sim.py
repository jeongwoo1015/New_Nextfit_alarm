"""기상 볼륨 개인화 가상 사용자 시뮬레이션(11/21). 사용자 데이터가 적은 한계를 보완해 "알고리즘이 사용자별 시작 단계를 찾아가는가"를 보고, 설정값(임계 확률·prior 강도)을 고른다.

주의: 가상 사용자의 반응(단계별로 받을 확률)은 우리가 정한 가정값이다. 여기서 나오는 숫자는 알고리즘이 학습·적응할 수 있는지와 설정값의 안정성을 보는 용도이고,
실제 사용자에게 효과가 있다는 근거로 쓰면 안 된다. 설정값을 고르는 가상 사용자(SET_TYPES)와 검증하는 가상 사용자(VAL_TYPES)를 나눠 과적합을 막는다.

사용법: python -m ai.personalization.wake_sim
"""
import random

from .wake_volume import CANDIDATES, LEVELS, SHORT_SLEEP_MIN, START_CAP, THRESHOLD, WakeLadder, decide_start_level, learn_wake, sleep_bucket

# 알람이 그 단계까지 올라와 있을 때 그 단계에서 받을 확률(앞 단계에서 안 받았다고 가정). 1~5단계
LADDERS = {
    "easy":      (0.90, 0.95, 0.99, 0.99, 0.99),
    "normal":    (0.30, 0.75, 0.90, 0.99, 0.99),
    "deep":      (0.10, 0.30, 0.80, 0.99, 0.99),
    "very_deep": (0.05, 0.10, 0.40, 0.90, 0.99),
    "no_effect": (0.60, 0.60, 0.60, 0.60, 0.60),            # 단계와 상관없이 같다(억지로 차이를 만들면 안 된다)
}
SET_TYPES = ("easy", "normal", "deep", "short_hard")                      # 설정값을 고를 때 쓰는 가상 사용자
VAL_TYPES = ("very_deep", "habit_change", "no_effect", "new_user")        # 고른 설정값을 마지막에 검증하는 가상 사용자(설정에 쓰지 않는다)
POLICIES = ("fixed1", "fixed3", "random", "rule", "bandit", "bandit_global")
SHORT_RATE = 0.4                  # 수면이 짧은 날의 비율
CHANGE_DAY = 30                   # habit_change가 바뀌는 날
EVAL_FROM = 21                    # 이 날부터를 평가한다(앞은 학습 기간)


def ladder_of(user_type: str, day: int, short: bool) -> tuple:
    if user_type == "short_hard":
        return LADDERS["deep"] if short else LADDERS["normal"]       # 수면이 짧은 날만 깊이 잔다
    if user_type == "habit_change":
        return LADDERS["normal"] if day < CHANGE_DAY else LADDERS["deep"]
    if user_type == "new_user":
        return LADDERS["normal"]
    return LADDERS[user_type]


def correct_start(probs: tuple, candidates=CANDIDATES, cap: int = START_CAP, threshold: float = THRESHOLD) -> int:
    """정답 시작 단계: 받을 확률이 threshold 이상인 가장 낮은 후보. 없으면 확률이 가장 높은 후보(0.05 안쪽 차이는 낮은 쪽)."""
    cands = sorted(c for c in candidates if c <= cap)
    for c in cands:
        if probs[c - 1] >= threshold:
            return c
    top = max(probs[c - 1] for c in cands)
    return next(c for c in cands if probs[c - 1] >= top - 0.05)


def run_policy(policy: str, user_type: str, days: int = 60, seed: int = 0, global_ladder: WakeLadder | None = None, threshold: float = THRESHOLD,
               prior_strength: float = 2.0) -> dict:
    """한 정책으로 days일을 돌린다. 모든 정책이 같은 환경 난수(수면 길이·받을 운)를 겪는다."""
    if policy not in POLICIES or (user_type not in LADDERS and user_type not in ("short_hard", "habit_change", "new_user")):
        raise ValueError(f"policy는 {POLICIES}, user_type은 {SET_TYPES + VAL_TYPES} 중 하나예요")
    env, pick = random.Random(seed), random.Random(seed + 1)
    ladder = WakeLadder()
    starts, correct, received, immediate = [], [], [], []
    for day in range(days):
        short = env.random() < SHORT_RATE
        luck = [env.random() for _ in LEVELS]
        probs = ladder_of(user_type, day, short)
        minutes = 300 if short else 450
        ctx = sleep_bucket(minutes)
        if policy == "fixed1":
            k = 1
        elif policy == "fixed3":
            k = 3
        elif policy == "random":
            k = pick.choice(CANDIDATES)
        elif policy == "rule":
            k = 3 if short else 1                                       # 규칙: 수면이 짧으면 크게
        else:
            use_global = global_ladder if policy == "bandit_global" else None
            k = decide_start_level(ladder, use_global, minutes, rng=pick, threshold=threshold, prior_strength=prior_strength, mc_samples=0).applied
        got = next((lv for lv in range(k, max(LEVELS) + 1) if luck[lv - 1] < probs[lv - 1]), None)
        if policy in ("bandit", "bandit_global"):
            learn_wake(ladder, ctx, k, got, True)
        starts.append(k)
        correct.append(k == correct_start(probs, threshold=threshold))
        received.append(got if got is not None else max(LEVELS))
        immediate.append(got == k)
    ev = slice(EVAL_FROM, days)
    n = days - EVAL_FROM

    def conv() -> int | None:
        return next((i for i in range(0, days - 9) if sum(correct[i:i + 10]) >= 8), None)
    return {"policy": policy, "user_type": user_type, "days": days, "starts": starts, "ladder": ladder,
            "correct_rate": sum(correct[ev]) / n, "avg_start": sum(starts[ev]) / n, "immediate_rate": sum(immediate[ev]) / n,
            "avg_received": sum(received[ev]) / n, "converge_day": conv()}


def build_global(days: int = 60, seeds=(100, 101, 102), threshold: float = THRESHOLD) -> WakeLadder:
    """설정용 가상 사용자(SET_TYPES)들이 학습한 기록을 합쳐 전체 사용자 prior를 만든다(검증용 가상 사용자는 쓰지 않는다)."""
    return WakeLadder.merge(*[run_policy("bandit", t, days, s, None, threshold)["ladder"] for t in SET_TYPES for s in seeds])


def run_all(types=SET_TYPES + VAL_TYPES, policies=POLICIES, days: int = 60, reps: int = 20, threshold: float = THRESHOLD, prior_strength: float = 2.0) -> dict:
    """{user_type: {policy: {지표: 반복 평균}}}. 지표: correct_rate(정답 시작 단계 선택 비율), avg_start(낮을수록 부드러움), immediate_rate(시작 단계에서 바로 받음), avg_received."""
    glob = build_global(days, threshold=threshold)
    out = {}
    for t in types:
        out[t] = {}
        for p in policies:
            runs = [run_policy(p, t, days, 1000 + r, glob, threshold, prior_strength) for r in range(reps)]
            out[t][p] = {m: sum(r[m] for r in runs) / reps for m in ("correct_rate", "avg_start", "immediate_rate", "avg_received")}
            conv = [r["converge_day"] for r in runs if r["converge_day"] is not None]
            out[t][p]["converge_day"] = sum(conv) / len(conv) if conv else None
    return out


def format_table(result: dict, metric: str = "correct_rate", policies=POLICIES) -> str:
    lines = [f"[{metric}] 사용자 유형 | " + " | ".join(f"{p:>13}" for p in policies)]
    for t, row in result.items():
        cells = []
        for p in policies:
            v = row[p][metric]
            cells.append(f"{'-':>13}" if v is None else f"{v:13.2f}")
        lines.append(f"{t:>14} | " + " | ".join(cells))
    return "\n".join(lines)


if __name__ == "__main__":
    res = run_all()
    for m in ("correct_rate", "avg_start", "immediate_rate", "avg_received", "converge_day"):
        print(format_table(res, m) + "\n")
    print(f"※ 평가는 {EVAL_FROM}일째부터(앞은 학습 기간). 설정용={SET_TYPES} / 검증용={VAL_TYPES}. 가상 사용자의 반응은 우리가 정한 값이라 효과의 근거가 아니다.")
