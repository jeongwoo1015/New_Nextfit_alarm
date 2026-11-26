"""기상 알람 볼륨 개인화(11/20 회의 2, 11/21 설계). 알람이 울릴 때 **시작 볼륨 단계**를 사용자별로 정한다.

배경: 기상 성공은 AI 대화가 아니라 알람 소리가 좌우한다(알람 → 전화 받기 → AI 첫 대답). 지금 앱은 알람 볼륨이 1단계에서 시작해 10초마다 한 단계씩
5단계까지 오르는 고정 구조라서, 그 시작 단계를 사용자별로 정하는 것이 가장 작은 변경이다(재기상 알람은 기상 알람보다 한 단계 큰 2~6단계).

구조
- 행동: 시작 단계 후보 CANDIDATES(1단계/3단계), 상한 START_CAP(3). 올라가는 방식은 지금 그대로다.
- 학습(사다리, 11/26 회의로 보상 분리): 사용자가 그날 첫 알람에서 **자발적으로 처음 반응한 단계**(받기·미루기·끄기 버튼을 누른 순간의 단계)만 본다.
  시작 단계 k에서 w단계에 처음 반응했으면 k~w-1단계는 실패, w단계는 성공으로 센다(볼륨이 점점 커지므로 단계가 세지는 성질이 성립한다).
  반응한 뒤의 미루기·재기상·AI 확인 질문 답변은 볼륨 학습에 넣지 않는다(그것은 AI 깨우는 말 강도의 몫이다). 3단계에서 미루기를 눌렀다면 3단계 반응 성공이다.
  끝까지 반응하지 않았으면 시작~마지막 단계가 모두 실패다. 3번 미룬 뒤 자동 연결은 자발적 반응이 아니므로 서버가 이 함수를 부르지 않는다(학습 제외).
- 선택(Thompson Sampling): 후보 단계마다 Beta(성공, 실패)에서 성공 확률을 하나 뽑아, THRESHOLD 이상이 나온 가장 낮은 후보로 시작한다(없으면 가장 높아 보이는 값과 비슷한 후보 중 가장 낮은 단계).
  기록이 적으면 값이 크게 흔들려 가끔 낮은 단계를 시험하고(탐색), 쌓이면 확실한 단계로 굳는다. 전체 사용자 기록(global)은 약한 prior로만 쓴다.
- 상황(Context): 추정 수면 가능 시간 한 가지(짧음 6시간 미만 / 보통 / 모름). 방향(짧게 자면 크게)은 가정하지 않고 칸별 기록이 정한다.
- 사용자 우선(11/20 회의 3): 일시 변경(다음 알람만, update_setting scope=TODAY) > 지속 상한(max_start_level, scope=ALWAYS) > Bandit 추천.
  Bandit은 추천값(recommended)을 계산하고, 실제 적용값(applied)은 사용자 설정을 반영해 따로 정한다. 사용자가 바꾼 값으로 울린 날도 결과를 학습한다(탐색 확률은 source로 구분해 기록).
- 안전: 시작 단계는 START_CAP을 넘기지 않는다(사용자가 직접 정한 값은 예외). 밤 대화로 알리지 못한 날은 올리지 않고 이전 단계를 유지한다(상승 보류). 중요한 날은 탐색하지 않는다.

이 모듈은 DB를 읽지 않는다. 서버가 사용자별 사다리(`rs_bandit_state`의 'wake:user:{id}')와 전체 합산('wake:global')을 넘기고(코칭 강도 Bandit의 'user:{id}'·'global'과는 키가 분리돼 있다), 결과를 learn_wake로 반영해 다시 저장한다.
"""
import random
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

LEVELS = (1, 2, 3, 4, 5)           # 기상 알람 볼륨 단계(앱 AlarmSoundPlayer.MAX_LEVEL = 5)
REWAKE_OFFSET = 1                  # 재기상 알람 = 기상 알람 + 1단계 (2~6단계)
CANDIDATES = (1, 3)                # 시작 단계 후보(회의에서 2개로 줄이기로 함)
START_CAP = 3                      # Bandit이 고르는 시작 단계의 상한
THRESHOLD = 0.7                    # 그 단계에서 받을 확률이 이 값 이상이면 충분히 확실하다고 본다(가정값, 시뮬레이션으로 정한다)
TIE_MARGIN = 0.1                   # 확실한 후보가 없을 때, 가장 좋아 보이는 값과 이만큼 이내로 비슷하면 낮은 단계를 고른다(가정값)
SHORT_SLEEP_MIN = 360              # 추정 수면 가능 시간이 6시간 미만이면 '짧음'(가정값)
PRIOR_STRENGTH = 2.0               # 전체 사용자 평균을 몇 번의 관측만큼으로 볼지(약하게)
MIN_OBSERVATIONS = 6               # 후보 단계들의 관측이 이보다 적으면 '아직 찾는 중'

STATE_RECOMMENDED, STATE_EXPLORING, STATE_USER = "RECOMMENDED", "EXPLORING", "USER"       # inputs.SettingsView.wake_state와 같은 값


def rewake_level(level: int) -> int:
    """재기상 알람 단계: 기상 알람보다 한 단계 크게(최대 6)."""
    return min(int(level) + REWAKE_OFFSET, max(LEVELS) + REWAKE_OFFSET)


def sleep_bucket(sleep_minutes) -> str:
    """추정 수면 가능 시간(밤 대화 종료 ~ 알람 받은 시각)을 상황 칸으로. 모르면 'unknown'."""
    try:
        m = float(sleep_minutes)
    except (TypeError, ValueError):
        return "unknown"
    return "unknown" if m < 0 or m != m else ("short" if m < SHORT_SLEEP_MIN else "ok")


# ---------------- 사다리 기록 ----------------
class WakeLadder:
    """(상황, 단계)마다 [성공, 실패] 횟수. to_dict/from_dict로 저장하고 merge로 여러 사용자를 합친다."""

    def __init__(self, counts: dict | None = None):
        self.counts: dict[str, list[int]] = {}
        for k, v in (counts or {}).items():
            if isinstance(v, (list, tuple)) and len(v) == 2 and all(isinstance(x, int) and not isinstance(x, bool) and x >= 0 for x in v):
                self.counts[str(k)] = [v[0], v[1]]

    @staticmethod
    def _k(ctx: str, level: int) -> str:
        return f"{ctx}|{int(level)}"

    def get(self, ctx: str, level: int) -> tuple[int, int]:
        s, f = self.counts.get(self._k(ctx, level), (0, 0))
        return s, f

    def add(self, ctx: str, level: int, success: bool) -> None:
        cell = self.counts.setdefault(self._k(ctx, level), [0, 0])
        cell[0 if success else 1] += 1

    def learn(self, ctx: str, start_level: int, received_level: int | None, confirmed=None) -> bool:
        """아침 한 번의 결과를 반영한다. received_level = 첫 알람에서 자발적으로 처음 반응한 단계(받기·미루기·끄기 모두), None이면 끝까지 반응하지 않은 날.
        잘못된 값이면 False. confirmed는 11/26 이전 호출과 맞추려고 받기만 하고 쓰지 않는다(미루기·재기상·확인 답변은 볼륨 학습에 영향이 없다)."""
        if start_level not in LEVELS or (received_level is not None and (received_level not in LEVELS or received_level < start_level)):
            return False
        last = received_level if received_level is not None else max(LEVELS)
        for lv in range(start_level, last):
            self.add(ctx, lv, False)
        self.add(ctx, last, received_level is not None)
        return True

    def observations(self, ctx: str, levels=CANDIDATES) -> int:
        return sum(sum(self.get(ctx, lv)) for lv in levels)

    def to_dict(self) -> dict:
        return {"version": 1, "counts": {k: list(v) for k, v in self.counts.items()}}

    @classmethod
    def from_dict(cls, data) -> "WakeLadder":
        """저장된 상태에서 복원한다. None이나 깨진 값이면 새로 시작한다."""
        return cls(data.get("counts")) if isinstance(data, dict) and isinstance(data.get("counts"), dict) else cls()

    @classmethod
    def merge(cls, *ladders: "WakeLadder") -> "WakeLadder":
        """여러 사용자의 기록을 합친 새 사다리(전체 사용자 prior용)."""
        out = cls()
        for lad in ladders:
            for k, (s, f) in lad.counts.items():
                cell = out.counts.setdefault(k, [0, 0])
                cell[0] += s
                cell[1] += f
        return out


def beta_for(personal: WakeLadder, global_: WakeLadder | None, ctx: str, level: int, prior_strength: float = PRIOR_STRENGTH) -> tuple[float, float]:
    """그 사람의 (성공, 실패) + 전체 사용자 평균을 약하게 섞은 prior. 전체 기록이 없으면 평균 0.5."""
    gs, gf = global_.get(ctx, level) if global_ is not None else (0, 0)
    mean = (gs + 1) / (gs + gf + 2)
    ps, pf = personal.get(ctx, level)
    return prior_strength * mean + ps, prior_strength * (1 - mean) + pf


# ---------------- 결정 ----------------
@dataclass(frozen=True)
class UserWakePrefs:
    """사용자가 대화(update_setting wake_volume)로 정한 값. 항상 Bandit 추천보다 우선한다."""
    max_start_level: int | None = None      # 지속 변경: 앞으로 이 단계보다 높게 시작하지 않는다(scope=ALWAYS)
    next_alarm_level: int | None = None     # 일시 변경: 다음 알람 한 번만 이 단계로 시작한다(scope=TODAY). 상승에 반대해 이전 단계를 유지하는 것도 이 값이다


@dataclass(frozen=True)
class WakeDecision:
    recommended: int           # Bandit의 추천 시작 단계
    applied: int               # 실제로 쓸 시작 단계(사용자 설정 반영 후)
    source: str                # bandit / user_today / user_max / hold_no_notice / important_day / default
    context_key: str
    explored: bool             # 점수가 가장 좋은 후보가 아니라 무작위 시도로 골랐는가
    propensity: float | None   # source가 bandit일 때 추천값을 고를 확률(추정). 사용자가 정한 날은 None
    state: str                 # RECOMMENDED / EXPLORING / USER
    raise_pending: bool        # 이전보다 높게 시작할 예정이라 밤 대화에서 알리고 반대 여부를 확인해야 한다
    reason: str


def _pick(draw: dict, cands: list, threshold: float) -> int:
    """오름차순 후보 중 값이 threshold 이상인 첫 번째. 확실한 후보가 없으면 가장 높아 보이는 값과 TIE_MARGIN 안쪽으로 비슷한 후보 중 가장 낮은 단계(차이가 없으면 부드러운 쪽)."""
    for c in cands:
        if draw[c] >= threshold:
            return c
    top = max(draw[c] for c in cands)
    return next(c for c in cands if draw[c] >= top - TIE_MARGIN)


def decide_start_level(personal: WakeLadder | None, global_: WakeLadder | None, sleep_minutes=None, prefs: UserWakePrefs = UserWakePrefs(),
                       previous_level: int | None = None, notified: bool = True, important_day: bool = False, rng=None,
                       candidates=CANDIDATES, cap: int = START_CAP, threshold: float = THRESHOLD, prior_strength: float = PRIOR_STRENGTH,
                       mc_samples: int = 200) -> WakeDecision:
    """내일 아침 알람의 시작 단계. previous_level은 직전 알람의 적용 단계(모르면 None), notified는 밤 대화로 이 변경을 알렸는지."""
    rng = rng or random
    personal = personal or WakeLadder()
    ctx = sleep_bucket(sleep_minutes)
    cands = sorted(c for c in candidates if c in LEVELS and c <= cap) or [min(candidates)]
    params = {c: beta_for(personal, global_, ctx, c, prior_strength) for c in cands}
    means = {c: params[c][0] / (params[c][0] + params[c][1]) for c in cands}
    best = _pick(means, cands, threshold)                                          # 점수만 보고 고른 값(탐색이 없었다면 이 값)
    if important_day:                                                              # 중요한 날은 탐색하지 않는다
        recommended, explored = best, False
    else:
        draw = {c: rng.betavariate(*params[c]) for c in cands}
        recommended = _pick(draw, cands, threshold)
        explored = recommended != best
    propensity = None
    if len(cands) == 1:
        propensity = 1.0
    elif mc_samples > 0 and not important_day:
        mrng = random.Random(f"{ctx}|{cands}|{sorted(personal.counts.items())}")  # 같은 상태면 같은 추정
        hits = sum(1 for _ in range(mc_samples) if _pick({c: mrng.betavariate(*params[c]) for c in cands}, cands, threshold) == recommended)
        propensity = (hits + 1) / (mc_samples + len(cands))
    exploring = personal.observations(ctx, cands) < MIN_OBSERVATIONS

    source, applied, reason = "bandit", recommended, f"{ctx} 상황의 기록으로 시작 {recommended}단계를 추천"
    if important_day:
        source, reason = "important_day", f"중요한 날이라 탐색 없이 {recommended}단계"
    if prefs.next_alarm_level:
        applied = max(1, min(int(prefs.next_alarm_level), max(LEVELS)))
        source, propensity, reason = "user_today", None, f"사용자가 다음 알람을 {applied}단계로 정함(추천은 {recommended}단계)"
    elif prefs.max_start_level and recommended > prefs.max_start_level:
        applied = max(1, int(prefs.max_start_level))
        source, propensity, reason = "user_max", None, f"사용자가 시작 단계 상한을 {applied}단계로 정함(추천은 {recommended}단계)"
    raise_pending = bool(previous_level and applied > previous_level and source in ("bandit", "important_day"))
    if raise_pending and not notified:                                             # 밤 대화로 알리지 못한 날은 올리지 않는다
        applied, source, propensity = previous_level, "hold_no_notice", None
        reason, raise_pending = f"밤 대화로 알리지 못해 {previous_level}단계를 유지(추천은 {recommended}단계)", False
    state = STATE_USER if source.startswith("user") else (STATE_EXPLORING if exploring else STATE_RECOMMENDED)
    return WakeDecision(recommended=recommended, applied=applied, source=source, context_key=ctx, explored=explored, propensity=propensity,
                        state=state, raise_pending=raise_pending, reason=reason)


def raise_announced(ai_text: str, new_level: int) -> bool:
    """밤 대화에서 AI가 내일 시작 단계(new_level)로 올린다고 실제로 말했는지 대화 글에서 확인한다. AI가 알림을 빠뜨리는 날(실제 모델 시험에서 5번 중 1~2번)을 걸러
    서버가 `notified`를 정할 때 쓴다. False면 올리지 않고 이전 단계를 유지한다."""
    text = str(ai_text or "")
    return bool(re.search(rf"(?<!\d){int(new_level)}\s*단계", text) and re.search(r"소리|볼륨|알람", text))


def learn_wake(personal: WakeLadder, ctx: str, start_level: int, received_level: int | None, confirmed=None) -> bool:
    """아침 결과 반영(첫 반응 단계만, 11/26). 시작 단계를 사용자가 정한 날(source가 user_*)도 그대로 학습한다(그 선택도 새 데이터다).
    confirmed는 옛 호출 호환용이고 쓰지 않는다. 자동 연결로 시작한 날은 부르지 않는다."""
    return personal.learn(ctx, start_level, received_level)


@dataclass
class WakeLog:
    """전략 로그 한 줄(서버가 저장). 추천값과 적용값을 따로 남겨 사용자가 바꾼 날도 구분한다."""
    user_id: str
    session_date: str
    context_key: str
    recommended_level: int
    applied_level: int
    source: str
    explored: bool
    propensity: float | None
    state: str
    reason: str
    previous_level: int | None = None
    prefs: dict = field(default_factory=dict)
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat(timespec="seconds"))

    def to_row(self) -> dict:
        return dict(self.__dict__)


def make_log(d: WakeDecision, user_id, session_date: str, prefs: UserWakePrefs = UserWakePrefs(), previous_level: int | None = None) -> WakeLog:
    return WakeLog(user_id=str(user_id), session_date=session_date, context_key=d.context_key, recommended_level=d.recommended, applied_level=d.applied,
                   source=d.source, explored=d.explored, propensity=d.propensity, state=d.state, reason=d.reason, previous_level=previous_level,
                   prefs={"max_start_level": prefs.max_start_level, "next_alarm_level": prefs.next_alarm_level})
