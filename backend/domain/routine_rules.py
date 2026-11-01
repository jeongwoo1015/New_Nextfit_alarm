"""알람별 루틴 항목 시간 (정의서 11/01: RS_ALARM_ROUTINE_ITEM).

프리셋 기본 시간에서 알람마다 항목 시간을 30초 단위로 바꾸고, 0초면 그 항목을 뺀다. 바꾼 항목만 저장하고
기본 시간으로 되돌리면 행을 지운다. 알람 루틴 합계 ≤ RS_APP_CONFIG.ROUTINE_MAX_SEC, 시간이 있는 항목 ≥ 1.
"""
from dataclasses import dataclass, replace
from typing import Iterable, Mapping

from backend.core.errors import ValidationFailed

from . import codes
from .models import AlarmRoutineItem, TemplateItem


def build_routine(items: Iterable[TemplateItem], overrides: Mapping[str, int]) -> list[AlarmRoutineItem]:
    """프리셋 항목에 알람별 설정을 덮어쓴 목록 (DB 함수 rs_alarm_routine_items와 같은 결과). 메모리 저장소용."""
    return [AlarmRoutineItem(i.template_item_id, i.item_order, i.item_name, i.default_duration_sec,
                             overrides.get(i.template_item_id, i.default_duration_sec), i.response_type, i.script)
            for i in sorted(items, key=lambda i: i.item_order)]


def overrides_of(items: Iterable[AlarmRoutineItem]) -> dict[str, int]:
    """저장할 행 = 기본 시간과 다른 항목만."""
    return {r.template_item_id: r.duration_sec for r in items if r.customized}


def total_sec(items: Iterable[AlarmRoutineItem]) -> int:
    return sum(r.duration_sec for r in items)


def check_routine(items: list[AlarmRoutineItem], max_sec: int) -> None:
    """저장 전 검사. DB 트리거·CHECK와 같은 규칙을 먼저 확인해 읽을 수 있는 오류를 준다 (오류 코드는 backend-db 초안 8절)."""
    bad = [r.item_name for r in items if r.duration_sec < 0 or r.duration_sec % codes.DURATION_STEP_SEC]
    if bad:
        raise ValidationFailed(f"항목 시간은 0 이상, {codes.DURATION_STEP_SEC}초 단위예요: {', '.join(bad)}")
    total = total_sec(items)
    if total > max_sec:
        raise ValidationFailed(f"루틴 합계 {total}초가 상한 {max_sec}초를 넘어요", "ROUTINE_OVER_LIMIT")
    if not any(r.duration_sec > 0 for r in items):
        raise ValidationFailed("루틴 항목을 모두 뺄 수는 없어요", "ROUTINE_ALL_REMOVED")


def _with(items: list[AlarmRoutineItem], durations: Mapping[str, int]) -> list[AlarmRoutineItem]:
    """durations에 있는 항목은 그 시간, 없는 항목은 기본 시간."""
    return [replace(r, duration_sec=durations.get(r.template_item_id, r.default_duration_sec)) for r in items]


def apply_durations(items: Iterable[AlarmRoutineItem], durations: Mapping[str, int], max_sec: int) -> dict[str, int]:
    """알람 세부 설정 저장(전체 교체). durations에 없는 항목은 기본 시간. 반환값 = 저장할 overrides."""
    items = list(items)
    unknown = sorted(set(durations) - {r.template_item_id for r in items})
    if unknown:
        raise ValidationFailed(f"이 알람의 프리셋에 없는 항목이에요: {', '.join(unknown)}", "ROUTINE_ITEM_UNKNOWN")
    result = _with(items, durations)
    check_routine(result, max_sec)
    return overrides_of(result)


@dataclass(frozen=True)
class ConversationChange:
    overrides: dict[str, int]          # 저장할 새 행
    removed: list[str]                 # 실제로 뺀 항목
    restored: list[str]                # 실제로 다시 넣은 항목
    rejected: list[str]                # 규칙에 걸려 반영하지 않은 항목 (모두 빼기, 상한 초과, 모르는 항목)


def apply_conversation(items: Iterable[AlarmRoutineItem], remove: Iterable[str], restore: Iterable[str],
                       max_sec: int) -> ConversationChange:
    """밤 대화에서 "스트레칭 빼줘 / 다시 넣어줘" (AI parse_routine_changes의 remove·restore). items는 현재 알람 항목.

    빼기 = 0초, 다시 넣기 = 기본 시간(행 삭제). 하나씩 적용하며 규칙에 걸리는 요청은 건너뛴다
    (예: 다른 항목을 늘려 둔 상태에서 다시 넣으면 상한을 넘는 경우).
    """
    current = list(items)
    known = {r.template_item_id for r in current}
    removed, restored, rejected = [], [], []
    for item_id, action in [(i, "remove") for i in remove] + [(i, "restore") for i in restore]:
        if item_id not in known:
            rejected.append(item_id)
            continue
        trial = [replace(r, duration_sec=0 if action == "remove" else r.default_duration_sec) if r.template_item_id == item_id else r
                 for r in current]
        try:
            check_routine(trial, max_sec)
        except ValidationFailed:
            rejected.append(item_id)
            continue
        current = trial
        (removed if action == "remove" else restored).append(item_id)
    return ConversationChange(overrides_of(current), removed, restored, rejected)

