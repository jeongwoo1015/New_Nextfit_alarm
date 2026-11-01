"""알람별 루틴 항목 시간 (정의서 11/01: RS_ALARM_ROUTINE_ITEM).

프리셋 기본 시간에서 알람마다 항목 시간을 30초 단위로 바꾸고, 0초면 그 항목을 뺀다. 바꾼 항목만 저장하고
기본 시간으로 되돌리면 행을 지운다. 알람 루틴 합계 ≤ RS_APP_CONFIG.ROUTINE_MAX_SEC, 시간이 있는 항목 ≥ 1.
"""
from dataclasses import dataclass
from typing import Iterable, Mapping

from backend.core.errors import ValidationFailed

from . import codes
from .models import RoutineItem, TemplateItem


def routine_items(items: Iterable[TemplateItem], overrides: Mapping[str, int]) -> list[RoutineItem]:
    """프리셋 항목에 알람별 설정을 덮어쓴 목록 (DB 함수 rs_alarm_routine_items와 같은 결과)."""
    return [RoutineItem(i, overrides.get(i.template_item_id, i.default_duration_sec))
            for i in sorted(items, key=lambda i: i.item_order)]


def total_sec(items: Iterable[RoutineItem]) -> int:
    return sum(r.duration_sec for r in items)


def check_routine(items: list[RoutineItem], max_sec: int) -> None:
    """저장 전 검사. DB 트리거와 같은 규칙을 먼저 확인해 사용자에게 읽을 수 있는 오류를 준다."""
    errors = []
    bad = [r.item.item_name for r in items if r.duration_sec < 0 or r.duration_sec % codes.DURATION_STEP_SEC]
    if bad:
        errors.append(f"항목 시간은 0 이상, {codes.DURATION_STEP_SEC}초 단위예요: {', '.join(bad)}")
    total = total_sec(items)
    if total > max_sec:
        errors.append(f"루틴 합계 {total}초가 상한 {max_sec}초를 넘어요")
    if not any(r.duration_sec > 0 for r in items):
        errors.append("루틴 항목을 모두 뺄 수는 없어요")
    if errors:
        raise ValidationFailed(" / ".join(errors), "ROUTINE_TIME_INVALID")


def apply_durations(items: Iterable[TemplateItem], durations: Mapping[str, int], max_sec: int) -> dict[str, int]:
    """알람 세부 설정 저장(전체 교체). durations에 없는 항목은 기본 시간.

    반환값은 저장할 행(기본 시간과 다른 항목만) = 새 overrides.
    """
    items = list(items)
    known = {i.template_item_id for i in items}
    unknown = sorted(set(durations) - known)
    if unknown:
        raise ValidationFailed(f"이 알람의 프리셋에 없는 항목이에요: {', '.join(unknown)}", "ROUTINE_ITEM_UNKNOWN")
    result = routine_items(items, durations)
    check_routine(result, max_sec)
    return {r.item.template_item_id: r.duration_sec for r in result if r.customized}


@dataclass(frozen=True)
class ConversationChange:
    overrides: dict[str, int]          # 저장할 새 행
    removed: list[str]                 # 실제로 뺀 항목
    restored: list[str]                # 실제로 다시 넣은 항목
    rejected: list[str]                # 규칙에 걸려 반영하지 않은 항목 (모두 빼기, 상한 초과)


def apply_conversation(items: Iterable[TemplateItem], overrides: Mapping[str, int], remove: Iterable[str],
                       restore: Iterable[str], max_sec: int) -> ConversationChange:
    """밤 대화에서 "스트레칭 빼줘 / 다시 넣어줘" (AI parse_routine_changes의 remove·restore).

    빼기 = 0초, 다시 넣기 = 기본 시간(행 삭제). 하나씩 적용하며 규칙에 걸리는 요청은 건너뛴다
    (예: 다른 항목을 늘려 둔 상태에서 다시 넣으면 상한을 넘는 경우).
    """
    items = list(items)
    defaults = {i.template_item_id: i.default_duration_sec for i in items}
    current = dict(overrides)
    removed, restored, rejected = [], [], []
    for item_id, action in [(i, "remove") for i in remove] + [(i, "restore") for i in restore]:
        if item_id not in defaults:
            rejected.append(item_id)
            continue
        trial = dict(current)
        if action == "remove":
            trial[item_id] = 0
        else:
            trial.pop(item_id, None)
        try:
            check_routine(routine_items(items, trial), max_sec)
        except ValidationFailed:
            rejected.append(item_id)
            continue
        current = {k: v for k, v in trial.items() if v != defaults[k]}
        (removed if action == "remove" else restored).append(item_id)
    return ConversationChange(current, removed, restored, rejected)


def safe_write_order(items: Iterable[TemplateItem], current: Mapping[str, int], target: Mapping[str, int]) -> list[tuple[str, int | None]]:
    """overrides를 current → target으로 한 행씩 바꿀 때의 순서 [(item_id, 새 시간 또는 None=행 삭제)].

    DB 트리거가 행마다 합계 상한·최소 1개를 검사하므로, 트랜잭션 함수(RPC) 없이 쓸 때 실패를 줄이려고
    줄어드는 항목을 먼저 쓰고 늘어나는 항목을 나중에 쓴다. 0초로 줄이는 항목은 늘리는 항목 뒤로 미뤄
    중간에 켜진 항목이 0개가 되지 않게 한다. (극단적인 경우는 RPC로만 해결된다)
    """
    defaults = {i.template_item_id: i.default_duration_sec for i in items}
    eff = lambda o, k: o.get(k, defaults[k])
    ops = []
    for k in set(current) | set(target):
        new = target.get(k)
        delta = eff(target, k) - eff(current, k)
        if delta == 0 and (k in current) == (k in target):
            continue
        to_zero = eff(target, k) == 0
        ops.append(((2 if to_zero else 0 if delta <= 0 else 1), delta, k, new))
    return [(k, new) for _, _, k, new in sorted(ops)]
