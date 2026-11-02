"""밤 대화 원문 → 루틴 항목별 시간 변경. 빼기(0초)·시간 줄이기/늘리기·되돌리기(null)와 적용 범위(내일만/앞으로 계속)를 돌려준다.

항목 추가·이름·순서 변경은 받지 않는다(정의서 11/01). 시간은 30초 단위이고 합계 상한(15분)은 서버가 검사한다.
"""
from dataclasses import dataclass, field

from . import transcript as tr
from .llm import LLMError

SCOPES = ("TOMORROW", "ALWAYS")   # 내일 하루만(밤 대화 기본) / 앞으로 계속(알람 설정을 바꿈)
UNIT_SEC = 30
MAX_SEC = 900

SYSTEM = """아래는 사용자의 아침 루틴 항목 목록(번호, 이름, 현재 시간)과 밤 대화 기록입니다. 사용자가 루틴 항목을 빼거나, 시간을 줄이거나 늘려 달라고 한 것, 바꾼 것을 원래대로 되돌려 달라고 한 것을 찾아 주세요.
JSON 객체 하나로만 출력: {"changes": [{"item": 항목 번호, "seconds": 정수 또는 null, "scope": "TOMORROW" 또는 "ALWAYS"}]}
규칙:
- 사용자가 직접, 분명하게 요청했거나 AI의 확인에 분명히 동의한 것만 담는다. AI가 먼저 제안만 했거나 사용자가 거절·무시했으면 담지 않는다.
- seconds: 빼기는 0, 시간 변경은 사용자가 말한 시간을 초로 바꾼 값(예: 3분 → 180, 1분 30초 → 90), 원래대로 되돌리기는 null.
- 몇 분으로 할지 말하지 않은 채 '줄여 줘'라고만 한 요청은 담지 않는다.
- scope: 기본은 "TOMORROW"(내일 하루만. '내일', '오늘만', 기간을 말하지 않은 경우 모두 포함). 사용자가 '앞으로', '계속', '매일', '다음부터 쭉'처럼 기본 설정 자체를 바꾸겠다고 말했을 때만 "ALWAYS".
- 같은 항목을 여러 번 바꿨으면 범위마다 마지막에 확정된 것 하나만 담는다.
- 항목 추가, 이름·순서 변경 요청은 무시한다. 번호는 목록에 있는 번호만 쓴다. 요청이 없으면 {"changes": []}.
- 받아쓰기 오류로 보이는 항목 이름은 문맥으로 가장 가까운 항목에 맞춘다. 확신이 없으면 담지 않는다."""


@dataclass(frozen=True)
class RoutineItem:
    template_item_id: str
    name: str
    enabled: bool = True              # 현재 사용 중인가 (시간이 0이면 False)
    duration_sec: int | None = None   # 내일 진행할 현재 시간(초). 서버가 알면 채운다 (없어도 동작)


@dataclass(frozen=True)
class ItemChange:
    template_item_id: str
    duration_sec: int | None          # 0 = 빼기, None = 되돌리기(내일만이면 알람 설정 시간, 계속이면 프리셋 기본 시간), 양수 = 새 시간(30초 단위)
    scope: str = "TOMORROW"           # TOMORROW / ALWAYS


@dataclass
class RoutineChanges:
    changes: list[ItemChange] = field(default_factory=list)
    status: str = "DONE"              # DONE / FAILED
    note: str = ""                    # 막은 이유나 실패 사유


def _active(it: RoutineItem) -> bool:
    return it.enabled and (it.duration_sec is None or it.duration_sec > 0)


def _label(it: RoutineItem) -> str:
    if not _active(it):
        return "뺀 상태"
    if not it.duration_sec:
        return "사용 중"
    m, s = divmod(it.duration_sec, 60)
    return f"{m}분{f' {s}초' if s else ''}" if m else f"{s}초"


def _seconds(v):
    """AI가 준 값 → 0 / 30초 단위 양수 / None(되돌리기). 쓸 수 없는 값이면 ValueError."""
    if v is None:
        return None
    if isinstance(v, bool):
        raise ValueError("seconds가 숫자가 아니에요")
    try:
        n = int(round(float(v)))
    except (TypeError, ValueError):
        raise ValueError("seconds가 숫자가 아니에요") from None
    if n < 0 or n > MAX_SEC:
        raise ValueError("seconds 범위를 벗어났어요")
    return 0 if n == 0 else max(UNIT_SEC, round(n / UNIT_SEC) * UNIT_SEC)


def _rank(c: ItemChange, items_by_id: dict) -> int:
    """빼기 → 줄이기 → 되돌리기·늘리기 순으로 적용해야 합계 상한에 덜 걸린다."""
    if c.duration_sec == 0:
        return 0
    cur = items_by_id[c.template_item_id].duration_sec
    return 1 if c.duration_sec is not None and cur is not None and c.duration_sec < cur else 2


def parse_routine_changes(transcript, items: list[RoutineItem], llm, retries: int = 1) -> RoutineChanges:
    """items: 내일 진행할 루틴 항목(알람 설정 + 내일만 바꾼 값). 항목이 없거나 사용자 발화가 없으면 AI를 부르지 않는다."""
    turns = tr.normalize(transcript)
    if not items or not tr.has_user_speech(turns):
        return RoutineChanges()
    listing = "\n".join(f"{i}. {it.name} ({_label(it)})" for i, it in enumerate(items, 1))
    user = f"[루틴 항목]\n{listing}\n\n[밤 대화 기록]\n{tr.to_text(turns)}"
    last = ""
    for _ in range(retries + 1):
        try:
            return _build(llm.generate_json(SYSTEM, user), items)
        except (LLMError, ValueError) as e:
            last = str(e)
    return RoutineChanges(status="FAILED", note=last)


def _build(data: dict, items: list[RoutineItem]) -> RoutineChanges:
    raw = data.get("changes")
    if raw is None:
        raw = []
    if not isinstance(raw, list):
        raise ValueError("changes는 배열이어야 해요")
    picked: dict[tuple[str, str], ItemChange] = {}
    for c in raw:
        if not isinstance(c, dict) or isinstance(c.get("item"), bool) or "seconds" not in c:   # seconds가 없으면 되돌리기(null)가 아니라 모르는 요청이다
            continue
        try:
            n = int(c.get("item"))
            sec = _seconds(c.get("seconds"))
        except (TypeError, ValueError):
            continue
        if not 1 <= n <= len(items):
            continue
        it = items[n - 1]
        scope = str(c.get("scope") or "TOMORROW").upper()
        scope = scope if scope in SCOPES else "TOMORROW"
        if sec == 0 and not _active(it):          # 이미 뺀 항목은 또 뺄 수 없다
            continue
        if sec and sec == it.duration_sec and scope == "TOMORROW":   # 지금과 같은 시간이면 바꿀 것이 없다
            continue
        picked[(it.template_item_id, scope)] = ItemChange(it.template_item_id, sec, scope)
    changes = list(picked.values())
    note = ""
    removed = {c.template_item_id for c in changes if c.duration_sec == 0}
    kept = {it.template_item_id for it in items if _active(it)} - removed | {c.template_item_id for c in changes if c.duration_sec}
    if removed and not kept:   # 모든 항목을 빼면 루틴이 비므로 빼기를 적용하지 않는다
        changes = [c for c in changes if c.duration_sec != 0]
        note = "모든 항목을 빼는 요청이라 적용하지 않았어요"
    by_id = {it.template_item_id: it for it in items}
    changes.sort(key=lambda c: _rank(c, by_id))
    return RoutineChanges(changes, "DONE", note)
