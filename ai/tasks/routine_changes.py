"""밤 대화 원문 → 루틴 항목 빼기/다시 넣기. 결과는 RS_USER_ROUTINE_ITEM에 반영된다(뺀 항목만 IS_ENABLED=FALSE로 저장, 다시 넣으면 행 삭제).

개인화는 '항목 빼기'뿐이다(정의서). 항목 추가·이름·순서·시간 변경은 받지 않는다.
"""
from dataclasses import dataclass, field

from . import transcript as tr
from .llm import LLMError

SYSTEM = """아래는 사용자의 아침 루틴 항목 목록과 밤 대화 기록입니다. 사용자가 이 루틴에서 빼 달라고 한 항목과, 이미 뺀 항목을 다시 넣어 달라고 한 항목을 찾으세요.
JSON 객체 하나로만 출력: {"remove": [항목 번호...], "restore": [항목 번호...]}
규칙:
- 사용자가 직접, 분명하게 요청한 것만 담는다. AI가 먼저 제안만 했거나 사용자가 거절·무시했으면 담지 않는다.
- remove에는 현재 '사용 중'인 항목만, restore에는 현재 '뺀 상태'인 항목만 담는다. 번호는 목록에 있는 번호만 쓴다.
- 항목 추가, 이름·순서·시간 변경 요청은 무시한다. 요청이 없으면 두 배열 모두 []로 둔다.
- 받아쓰기 오류로 보이는 항목 이름은 문맥으로 가장 가까운 항목에 맞춘다. 확신이 없으면 담지 않는다."""


@dataclass(frozen=True)
class RoutineItem:
    template_item_id: str
    name: str
    enabled: bool = True   # 현재 사용 중인가 (RS_USER_ROUTINE_ITEM에 뺀 행이 없으면 True)


@dataclass
class RoutineChanges:
    remove: list[str] = field(default_factory=list)    # 새로 뺄 TEMPLATE_ITEM_ID (행 추가: IS_ENABLED=FALSE)
    restore: list[str] = field(default_factory=list)   # 다시 넣을 TEMPLATE_ITEM_ID (행 삭제)
    status: str = "DONE"                               # DONE / FAILED
    note: str = ""                                     # 막은 이유나 실패 사유


def _indices(v, valid: set[int]) -> list[int]:
    if v is None:
        return []
    if not isinstance(v, list):
        raise ValueError("번호 목록은 배열이어야 해요")
    out = []
    for x in v:
        if isinstance(x, bool):
            continue
        try:
            n = int(x)
        except (TypeError, ValueError):
            continue
        if n in valid and n not in out:
            out.append(n)
    return out


def parse_routine_changes(transcript, items: list[RoutineItem], llm, retries: int = 1) -> RoutineChanges:
    """items: ROUTINE_MODE=CUSTOM 루틴 알람의 프리셋 항목(현재 사용 여부 포함). 항목이 없으면 AI를 부르지 않는다."""
    turns = tr.normalize(transcript)
    if not items or not tr.has_user_speech(turns):
        return RoutineChanges()
    listing = "\n".join(f"{i}. {it.name} ({'사용 중' if it.enabled else '뺀 상태'})" for i, it in enumerate(items, 1))
    user = f"[루틴 항목]\n{listing}\n\n[밤 대화 기록]\n{tr.to_text(turns)}"
    last = ""
    for _ in range(retries + 1):
        try:
            data = llm.generate_json(SYSTEM, user)
            rem = [n for n in _indices(data.get("remove"), set(range(1, len(items) + 1))) if items[n - 1].enabled]
            res = [n for n in _indices(data.get("restore"), set(range(1, len(items) + 1))) if not items[n - 1].enabled]
            note = ""
            enabled_after = {n for n, it in enumerate(items, 1) if it.enabled} - set(rem) | set(res)
            if not enabled_after:   # 모든 항목을 빼면 루틴이 비므로 빼기를 적용하지 않는다
                rem, note = [], "모든 항목을 빼는 요청이라 적용하지 않았어요"
            return RoutineChanges([items[n - 1].template_item_id for n in rem], [items[n - 1].template_item_id for n in res], "DONE", note)
        except (LLMError, ValueError) as e:
            last = str(e)
    return RoutineChanges(status="FAILED", note=last)
