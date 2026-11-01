"""Personalization Agent: User Model·Rule·Bandit의 결과를 모아 오늘의 '말하는 방식'을 정하는 연결 함수.

별도의 AI 모델이 아니다. 서버가 아침 브리핑 시작 전에 `decide()`를 한 번 부르고, 하루가 끝나면 `learn()`을 부른다.

    d = decide(user_id, sessions, today, condition="TIRED", speech_style="KIND", mode="bandit", bandit=bandit)
    d.strategy          # 오늘의 전략 (코칭 강도, 브리핑 길이, 응원 정도)
    d.briefing_block    # 브리핑 지시문 끝에 붙일 문장 (기본 전략이면 빈 문자열)
    d.coaching_block    # 단계 세션(코칭·콘텐츠) 지시문 끝에 붙일 문장
    d.log.to_row()      # 전략 로그 한 행

mode: "off"(개인화 안 함) / "rule" / "bandit" / "ab"(사용자·날짜로 정해지는 50:50으로 Rule 또는 Bandit, 같은 날은 같은 결과)
Bandit 상태가 없으면 Rule로 대신한다. 어느 단계에서 오류가 나도 기본 전략으로 돌아가고(source="fallback") 대화는 그대로 이어진다.
피곤한 날과 사용자 말투 설정 KIND에서는 어떤 mode에서도 강한 전략(push)이 나오지 않는다(Rule의 U1, Bandit의 후보 제한).
"""
import hashlib
from dataclasses import dataclass
from datetime import date

from . import rules
from .bandit import ContextualBandit
from .logs import DecisionLog
from .strategy import DEFAULT_STRATEGY, Strategy, strategy_block
from .user_model import compute_stats
from ..live.version import PROMPT_VERSION

MODES = ("off", "rule", "bandit", "ab")


@dataclass(frozen=True)
class AgentDecision:
    strategy: Strategy
    briefing_block: str
    coaching_block: str
    log: DecisionLog


def ab_group(user_id, today: date) -> str:
    """사용자·날짜로 정해지는 결정적 배정(같은 입력이면 항상 같은 쪽). 확률은 0.5."""
    digest = hashlib.sha256(f"{user_id}|{today.isoformat()}".encode()).digest()
    return "bandit" if digest[0] % 2 else "rule"


def _blocks(strategy: Strategy) -> tuple[str, str]:
    return strategy_block(strategy, "briefing"), strategy_block(strategy, "coaching")


def decide(user_id, sessions, today: date, condition: str | None = None, speech_style: str | None = None, mode: str = "rule",
           bandit: ContextualBandit | None = None, rule_config: rules.RuleConfig = rules.RuleConfig(), rng=None) -> AgentDecision:
    log = DecisionLog(user_id=str(user_id), session_date=today.isoformat(), source="default", strategy_id=DEFAULT_STRATEGY.strategy_id,
                      strategy=DEFAULT_STRATEGY.to_dict(), mode=str(mode), prompt_version=PROMPT_VERSION)
    strategy = DEFAULT_STRATEGY
    try:
        if mode not in MODES:
            log.error = f"모르는 mode: {mode!r} (기본 전략 사용)"
        elif mode != "off":
            stats = compute_stats(sessions, today)
            log.stats = stats.to_dict()
            group = mode
            if mode == "ab":
                group = ab_group(user_id, today) if bandit is not None else "rule"
                log.ab_group, log.ab_propensity = group, (0.5 if bandit is not None else 1.0)   # Bandit이 없으면 무작위 배정이 아니다
            if group == "bandit" and bandit is not None:
                pick = bandit.choose(stats, condition, speech_style, weekday=today.weekday(), rng=rng)
                strategy = bandit.arms[pick.arm]
                log.source, log.context_key, log.propensity = "bandit", pick.context, pick.propensity
                log.allowed_arms, log.explored = list(pick.allowed), pick.explored
            else:
                decision = rules.decide(stats, condition=condition, speech_style=speech_style, config=rule_config)
                strategy, log.source, log.rule_ids = decision.strategy, "rule", list(decision.rule_ids)
            log.strategy_id, log.strategy = strategy.strategy_id, strategy.to_dict()
    except Exception as e:   # 어느 단계가 실패해도 대화는 기본 전략으로 이어진다
        strategy = DEFAULT_STRATEGY
        log.source, log.error = "fallback", f"{type(e).__name__}: {e}"[:200]
        log.strategy_id, log.strategy = strategy.strategy_id, strategy.to_dict()
        log.propensity = log.context_key = log.explored = None
        log.allowed_arms, log.rule_ids = [], []
    briefing, coaching = _blocks(strategy)
    return AgentDecision(strategy=strategy, briefing_block=briefing, coaching_block=coaching, log=log)


def learn(bandit: ContextualBandit, log: DecisionLog, reward) -> bool:
    """하루가 끝나 결과 점수(0~1)를 알게 되면 Bandit에 반영한다. Bandit이 고른 날만 학습한다."""
    if log.source != "bandit" or not log.context_key or log.strategy_id not in bandit.arms:
        return False
    return bandit.update(log.context_key, log.strategy_id, reward)
