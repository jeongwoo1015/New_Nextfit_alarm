"""Contextual Bandit: 상황(Context)을 보고 말하는 방식(전략)을 고르고, 결과(Reward)로 학습한다.

구현: 상황을 몇 칸으로 나누고(표 방식), 칸마다 전략별 평균 점수를 쌓는 ε-greedy.
  - 대부분은 평균 점수가 가장 높은 전략을 고르고, ε(기본 15%)는 후보 중 무작위로 시도한다.
  - 선택 확률(propensity)을 정확히 계산해서 돌려준다 → 나중에 효과를 평가(off-policy)할 때 필요하다.
  - 피곤한 날과 사용자 말투 설정이 KIND인 사용자에게는 강한 전략(push)을 후보에서 뺀다.
  - 상태는 to_dict()/from_dict()로 JSON 저장·복원한다. 여러 사용자의 상태는 merge()로 합칠 수 있다(데이터가 적을 때를 위해).
표준 라이브러리만 쓴다. DB를 읽지 않는다. 서버가 stats(User Model), 컨디션, 말투 설정을 넘긴다.

상황 칸(기본 4칸): 최근 기상 성공률(낮음/그 외) × 어젯밤 컨디션(피곤/그 외).
  사용자 한 명이 4주에 세션 28개를 쌓는다고 보면, 칸이 많을수록 칸마다 몇 번 못 봐서 배울 수 없다. 그래서 적게 시작한다.
  특징을 늘리려면 CONTEXT_FEATURES에 이름만 더하면 된다(예: ("wake", "tired", "day")는 8칸, 요일 추가).
"""
import math
import random
from dataclasses import dataclass

from .rules import is_tired
from .strategy import Strategy
from .user_model import UserStats

STATE_VERSION = 1
WAKE_LOW = 0.5        # 최근 기상 성공률이 이 값 미만이면 '낮음' (Rule의 R1과 같은 가정값)

# 순서가 중요하다: 점수가 같으면(처음 시작할 때) 앞쪽 전략이 이기므로 기본 전략(standard)을 맨 앞에 둔다.
ARMS: dict[str, Strategy] = {
    "standard": Strategy(strategy_id="standard"),
    "gentle": Strategy(coaching_intensity="soft", encouragement="high", strategy_id="gentle"),
    "push": Strategy(coaching_intensity="firm", briefing_length="short", strategy_id="push"),
}
SAFE_ARMS = ("standard", "gentle")        # 피곤한 날과 KIND 사용자에게 허용하는 전략
FALLBACK_ARM = "standard"                 # 후보가 비면 항상 기본 전략


# ---------------- 상황(Context) ----------------
def _wake(stats: UserStats | None, condition, weekday) -> str:
    rate = stats.wake_success_rate if stats is not None and stats.has_enough_data else None
    return "low" if rate is not None and rate < WAKE_LOW else "ok"


def _tired(stats, condition, weekday) -> str:
    return "tired" if is_tired(condition) else "ok"      # 오늘 아침에 적용되는 어젯밤 기록만 쓴다(옛 세션의 컨디션을 대신 쓰지 않는다)


def _day(stats, condition, weekday) -> str:
    return "wkd" if weekday is not None and weekday >= 5 else "wk"


FEATURES = {"wake": _wake, "tired": _tired, "day": _day}
CONTEXT_FEATURES = ("wake", "tired")       # 기본 4칸. ("wake", "tired", "day")로 바꾸면 8칸


def context_key(stats: UserStats | None, condition: str | None = None, weekday: int | None = None, features=CONTEXT_FEATURES) -> str:
    """상황을 칸 이름으로 바꾼다. weekday: 월=0 … 일=6 (day 특징을 쓸 때만 필요)."""
    unknown = [f for f in features if f not in FEATURES]
    if unknown:
        raise ValueError(f"알 수 없는 상황 특징: {unknown} (사용 가능: {sorted(FEATURES)})")
    return "|".join(FEATURES[f](stats, condition, weekday) for f in features)


def allowed_arms(condition: str | None = None, speech_style: str | None = None, arms=ARMS) -> tuple:
    """오늘 고를 수 있는 전략. 피곤한 날이거나 사용자 말투 설정이 KIND이면 push를 뺀다."""
    kind = str(speech_style or "").strip().upper() == "KIND"
    if is_tired(condition) or kind:
        return tuple(a for a in SAFE_ARMS if a in arms)
    return tuple(arms)


@dataclass(frozen=True)
class BanditDecision:
    arm: str
    propensity: float           # 이 전략을 고를 확률 (로그에 남겨 나중에 효과를 평가한다)
    explored: bool              # 점수가 가장 좋은 전략이 아니라 무작위 시도로 골랐는가
    allowed: tuple              # 이번에 후보였던 전략들
    values: dict                # 후보별 예상 점수
    probs: dict                 # 후보별 선택 확률 (합 = 1)
    context: str = ""


class ContextualBandit:
    def __init__(self, arms: dict | None = None, epsilon: float = 0.15, prior_mean: float = 0.5, prior_weight: float = 2.0,
                 features=CONTEXT_FEATURES):
        self.arms = dict(arms or ARMS)
        self.epsilon = min(1.0, max(0.0, float(epsilon)))
        self.prior_mean, self.prior_weight = float(prior_mean), float(prior_weight)
        self.features = tuple(features)
        context_key(None, None, None, self.features)      # 특징 이름이 틀렸으면 만들 때 바로 알린다
        self.counts: dict[str, int] = {}
        self.sums: dict[str, float] = {}

    @staticmethod
    def _k(ctx: str, arm: str) -> str:
        return f"{ctx}::{arm}"

    def value(self, ctx: str, arm: str) -> float:
        """칸·전략의 예상 점수. 기록이 적을수록 prior_mean 쪽으로 당겨진다."""
        n, s = self.counts.get(self._k(ctx, arm), 0), self.sums.get(self._k(ctx, arm), 0.0)
        return (self.prior_mean * self.prior_weight + s) / (self.prior_weight + n)

    def context(self, stats, condition=None, weekday=None) -> str:
        return context_key(stats, condition, weekday, self.features)

    def select(self, ctx: str, allowed=None, rng=None) -> BanditDecision:
        rng = rng or random
        wanted = set(allowed) if allowed is not None else set(self.arms)
        pool = [a for a in self.arms if a in wanted]                       # 항상 arms의 순서(기본 전략이 앞)를 따른다
        if not pool:                                                         # 후보가 비면 안전한 기본 전략으로 고정한다
            pool = [FALLBACK_ARM if FALLBACK_ARM in self.arms else next(iter(self.arms))]
        values = {a: self.value(ctx, a) for a in pool}
        best = max(pool, key=lambda a: (values[a], -pool.index(a)))         # 동점이면 앞쪽 전략
        k = len(pool)
        probs = {a: self.epsilon / k + ((1 - self.epsilon) if a == best else 0.0) for a in pool}
        arm = best if rng.random() >= self.epsilon else rng.choice(pool)
        return BanditDecision(arm=arm, propensity=probs[arm], explored=(arm != best), allowed=tuple(pool), values=values,
                              probs=probs, context=ctx)

    def choose(self, stats, condition=None, speech_style=None, weekday=None, rng=None) -> BanditDecision:
        """오늘의 상황과 안전 규칙을 모두 적용해 전략을 고른다 (Agent가 부르는 함수)."""
        return self.select(self.context(stats, condition, weekday), allowed_arms(condition, speech_style, self.arms), rng)

    def update(self, ctx: str, arm: str, reward) -> bool:
        """결과 점수(0~1)를 기록한다. 모르는 전략이나 숫자가 아닌 점수는 무시하고 False를 돌려준다."""
        if arm not in self.arms or isinstance(reward, bool):
            return False
        try:
            r = float(reward)
        except (TypeError, ValueError):
            return False
        if math.isnan(r):
            return False
        key = self._k(ctx, arm)
        self.counts[key] = self.counts.get(key, 0) + 1
        self.sums[key] = self.sums.get(key, 0.0) + min(1.0, max(0.0, r))
        return True

    # ---------------- 저장·복원·합치기 ----------------
    def to_dict(self) -> dict:
        return {"version": STATE_VERSION, "features": list(self.features), "epsilon": self.epsilon, "prior_mean": self.prior_mean,
                "prior_weight": self.prior_weight, "counts": dict(self.counts), "sums": dict(self.sums)}

    @classmethod
    def from_dict(cls, data) -> "ContextualBandit":
        """저장된 상태에서 복원한다. None이나 깨진 값이면 새로 시작한다."""
        if not isinstance(data, dict):
            return cls()
        try:
            b = cls(epsilon=data.get("epsilon", 0.15), prior_mean=data.get("prior_mean", 0.5), prior_weight=data.get("prior_weight", 2.0),
                    features=tuple(data.get("features") or CONTEXT_FEATURES))
        except (TypeError, ValueError):
            return cls()
        counts, sums = data.get("counts"), data.get("sums")
        for k, v in (counts.items() if isinstance(counts, dict) else ()):
            if isinstance(v, int) and not isinstance(v, bool) and v >= 0:
                b.counts[str(k)] = v
        for k, v in (sums.items() if isinstance(sums, dict) else ()):
            if isinstance(v, (int, float)) and not isinstance(v, bool) and not math.isnan(v) and v >= 0:
                b.sums[str(k)] = float(v)
        return b

    @classmethod
    def merge(cls, *bandits: "ContextualBandit") -> "ContextualBandit":
        """여러 사용자의 기록을 합친 새 Bandit. 사용자 한 명의 기록이 너무 적을 때 함께 학습하는 용도. 상황 특징이 같아야 한다."""
        if not bandits:
            return cls()
        first = bandits[0]
        if any(b.features != first.features for b in bandits):
            raise ValueError("상황 특징이 다른 Bandit은 합칠 수 없어요")
        out = cls(arms=first.arms, epsilon=first.epsilon, prior_mean=first.prior_mean, prior_weight=first.prior_weight, features=first.features)
        for b in bandits:
            for k, v in b.counts.items():
                out.counts[k] = out.counts.get(k, 0) + v
            for k, v in b.sums.items():
                out.sums[k] = out.sums.get(k, 0.0) + v
        return out
