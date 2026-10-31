"""전략: 개인화가 정하는 '오늘의 말하는 방식'의 값과, 그 값을 AI 지시문에 붙일 문장으로 바꾸는 부분.

DB를 읽지 않는다. 전략을 누가 정하는지(Rule·Bandit)도 모른다. 정의서에서 개인화가 바꿀 수 있는 것은 말투와 분량뿐이라 값은 세 가지다
(항목 시간 변경은 정의서에 없다).

- 전략이 None이거나 모두 기본값이면 strategy_block()은 빈 문자열이라, 대화가 지금과 똑같이 동작한다.
- 안전 규칙과 사용자가 고른 어조·말투가 전략보다 항상 우선한다(블록 끝에 문구가 붙는다).
"""
import re
from dataclasses import dataclass

INTENSITIES = ("soft", "normal", "firm")        # 코칭 강도
BRIEFING_LENGTHS = ("short", "normal")           # 아침 브리핑 길이
ENCOURAGEMENTS = ("low", "normal", "high")       # 응원·격려 정도

_INTENSITY_TEXT = {
    "soft": "코칭 강도: 부드럽게. 재촉하거나 몰아붙이지 말고, 천천히 해도 괜찮다는 느낌으로 안내한다.",
    "firm": "코칭 강도: 단호하게. 짧고 또렷하게 지금 바로 몸을 움직이도록 안내하되, 비난하거나 다그치는 표현은 쓰지 않는다.",
}
SHORT_BRIEFING_MARK = "브리핑 길이: 짧게"      # 브리핑 프롬프트가 이 표시를 보고 자기 분량 규칙(4~6문장)을 짧은 버전으로 바꾼다
_BRIEFING_TEXT = {
    "short": SHORT_BRIEFING_MARK + ". 전체를 2~3문장으로 줄이고 날씨·일정·어젯밤 목표 중 핵심만 말한다.",
}
_ENCOURAGEMENT_TEXT = {
    "low": "응원 정도: 적게. 응원 표현은 마무리에 한 번만 짧게 쓴다.",
    "high": "응원 정도: 많이. 사용자가 해낸 일을 구체적으로 짚어 따뜻하게 응원한다 (과장하지 않는다).",
}


@dataclass(frozen=True)
class Strategy:
    """오늘 하루의 대화 전략. 기본값은 '아무것도 바꾸지 않음'이다."""
    coaching_intensity: str = "normal"
    briefing_length: str = "normal"
    encouragement: str = "normal"
    strategy_id: str = "default"                       # 로그용 이름 (예: rule / gentle / standard / push)

    def __post_init__(self):
        # 잘못된 값이 프롬프트에 들어가지 않도록 기본값으로 되돌린다
        object.__setattr__(self, "coaching_intensity", self.coaching_intensity if self.coaching_intensity in INTENSITIES else "normal")
        object.__setattr__(self, "briefing_length", self.briefing_length if self.briefing_length in BRIEFING_LENGTHS else "normal")
        object.__setattr__(self, "encouragement", self.encouragement if self.encouragement in ENCOURAGEMENTS else "normal")
        object.__setattr__(self, "strategy_id", re.sub(r"[^\w\-.]", "_", str(self.strategy_id or "default"))[:40])

    @property
    def is_default(self) -> bool:
        return (self.coaching_intensity, self.briefing_length, self.encouragement) == ("normal", "normal", "normal")

    def to_dict(self) -> dict:
        return {"coaching_intensity": self.coaching_intensity, "briefing_length": self.briefing_length,
                "encouragement": self.encouragement, "strategy_id": self.strategy_id}

    @classmethod
    def from_dict(cls, data) -> "Strategy":
        """JSON으로 받은 값에서 만든다. None·빈 값·모르는 키가 와도 안전하다."""
        data = data if isinstance(data, dict) else {}
        return cls(coaching_intensity=data.get("coaching_intensity", "normal"), briefing_length=data.get("briefing_length", "normal"),
                   encouragement=data.get("encouragement", "normal"), strategy_id=data.get("strategy_id", "default"))


DEFAULT_STRATEGY = Strategy()


def coerce(strategy) -> Strategy | None:
    if strategy is None:
        return None
    if isinstance(strategy, Strategy):
        return strategy
    if isinstance(strategy, dict):
        return Strategy.from_dict(strategy)
    if hasattr(strategy, "to_dict"):
        return Strategy.from_dict(strategy.to_dict())
    return None


def strategy_block(strategy, stage: str = "all") -> str:
    """프롬프트 끝에 붙일 전략 블록. 기본 전략이거나 None이면 빈 문자열.

    stage: "briefing"(브리핑 길이·응원) / "coaching"(코칭 강도·응원) / "all"
    """
    s = coerce(strategy)
    if s is None or s.is_default:
        return ""
    lines = []
    if stage in ("coaching", "all") and s.coaching_intensity in _INTENSITY_TEXT:
        lines.append(_INTENSITY_TEXT[s.coaching_intensity])
    if stage in ("briefing", "all") and s.briefing_length in _BRIEFING_TEXT:
        lines.append(_BRIEFING_TEXT[s.briefing_length])
    if s.encouragement in _ENCOURAGEMENT_TEXT:
        lines.append(_ENCOURAGEMENT_TEXT[s.encouragement])
    if not lines:
        return ""
    return "[오늘의 전략]\n" + "\n".join(f"- {line}" for line in lines) + \
        "\n위 전략은 분량과 강도만 조정한다. 사용자가 고른 어조·말투와 안전 규칙, 그 외 모든 규칙이 항상 우선한다."
