"""Rule: 통계로 '오늘의 말하는 방식'을 정하는 if-then 규칙. Bandit과 비교하는 기준(베이스라인)이다.

DB를 읽지 않는다. 서버가 넘긴 값만 쓴다:
  - stats: user_model.compute_stats()의 결과(지난 7일, 오늘 제외)
  - condition: 오늘 아침에 적용되는 어젯밤 기록의 CONDITION_LABEL (RS_NIGHT_RECORD, APPLY_DATE = 오늘). 없으면 None
  - speech_style: RS_USER_SETTING.SPEECH_STYLE (KIND / FRIENDLY / FIRM)

규칙은 위에서 아래로 적용되고, 걸린 규칙 이름(rule_ids)을 함께 돌려준다.
  R0  기록이 부족하다(3세션 미만) → 통계 규칙(R1, R3)은 건너뜀. 컨디션 규칙(R4)은 이력과 무관해서 그대로 적용
  R1  최근 기상 성공률이 낮다 → 코칭을 단호하게
  R3  건너뛴 비율이 높다 → 브리핑을 짧게
  R4  어젯밤 컨디션이 피곤하다(TIRED, STRESSED) → 코칭 부드럽게 + 응원 많이 (R1의 '단호하게'를 덮어씀)
  U1  사용자 말투 설정이 KIND인데 코칭이 단호하게 → 평소 수준으로 낮춤 (사용자 설정 우선)
  R-  기록은 충분한데 걸린 규칙이 없음
(R2는 '항목 시간 줄이기'였고 정의서에 항목별 시간 변경이 없어 없앴다. 번호는 이미 공유돼 그대로 비워 둔다.)

임계값(0.5)은 팀장님 자료의 예시를 가정값으로 쓴 것이며, 실제 데이터를 본 뒤 RuleConfig로 조정한다.
위기 여부는 판단하지 않는다(위기 판정 코드를 만들지 않기로 했다).
"""
from dataclasses import dataclass

from .records import TIRED_CODES
from .strategy import Strategy
from .user_model import UserStats

STRATEGY_ID = "rule"


@dataclass(frozen=True)
class RuleConfig:
    wake_low: float = 0.5           # R1: 이 값 미만이면 단호하게
    skip_high: float = 0.5          # R3: 이 값 이상이면 브리핑을 짧게


@dataclass(frozen=True)
class RuleDecision:
    strategy: Strategy
    rule_ids: tuple


def is_tired(condition) -> bool:
    return str(condition or "").strip().upper() in TIRED_CODES


def decide(stats: UserStats | None, condition: str | None = None, speech_style: str | None = None,
           config: RuleConfig = RuleConfig()) -> RuleDecision:
    intensity, briefing, cheer = "normal", "normal", "normal"
    ids: list[str] = []
    enough = stats is not None and stats.has_enough_data

    if not enough:
        ids.append("R0")
    else:
        if stats.wake_success_rate is not None and stats.wake_success_rate < config.wake_low:
            intensity = "firm"
            ids.append("R1")
        if stats.skip_ratio is not None and stats.skip_ratio >= config.skip_high:
            briefing = "short"
            ids.append("R3")
    if is_tired(condition):
        intensity, cheer = "soft", "high"
        ids.append("R4")
    if intensity == "firm" and str(speech_style or "").strip().upper() == "KIND":
        intensity = "normal"
        ids.append("U1")
    if enough and not ids:
        ids.append("R-")
    return RuleDecision(Strategy(coaching_intensity=intensity, briefing_length=briefing, encouragement=cheer, strategy_id=STRATEGY_ID),
                        tuple(ids))
