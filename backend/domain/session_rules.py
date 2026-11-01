"""모닝 세션 규칙: 그날 적용할 값(스냅샷)과 세션 단계 만들기 (정의서 "세션 단계를 만드는 규칙")."""
from datetime import date
from typing import Iterable, Protocol

from backend.core.errors import ValidationFailed

from . import codes
from .models import Alarm, NightRecord, SessionSnapshot, StepPlan, TemplateItem


def override_applies(alarm: Alarm, night: NightRecord | None, session_date: date) -> bool:
    """밤 대화에서 동의한 하루 한정 변경이 이 알람의 이 날짜에 해당하는가."""
    return bool(night and night.apply_date == session_date and night.override_alarm_id == alarm.alarm_id)


def resolve_snapshot(alarm: Alarm, night: NightRecord | None, session_date: date) -> SessionSnapshot:
    """알람 설정 + 하루 한정 변경 → 그날 세션에 남길 값. 변경값이 있으면 그 값이 우선한다."""
    wake, coaching = alarm.alarm_time, alarm.coaching_min
    if override_applies(alarm, night, session_date):
        wake = night.override_wake_time or wake
        if night.override_coaching_min is not None:
            coaching = night.override_coaching_min
    if coaching not in codes.SESSION_COACHING_MIN:
        raise ValidationFailed(f"세션 코칭 길이는 0/10/20분이에요: {coaching!r}")
    return SessionSnapshot(
        user_id=alarm.user_id, alarm_id=alarm.alarm_id, session_date=session_date,
        morning_call_type=alarm.morning_call_type, template_id=alarm.template_id, routine_mode=alarm.routine_mode,
        content_type=alarm.content_type, content_option=alarm.content_option,
        base_wake_time=alarm.alarm_time, target_wake_time=wake, coaching_min=coaching,
    )


def version_seconds(item: TemplateItem, coaching_min: int) -> int | None:
    """그날 코칭 길이에 맞는 버전 시간. 하루 한정 10분이면 20분 알람이라도 10분 버전을 쓴다."""
    return {10: item.duration_10_sec, 20: item.duration_20_sec}[coaching_min]


def routine_steps(items: Iterable[TemplateItem], coaching_min: int, routine_mode: str,
                  excluded_item_ids: Iterable[str] = ()) -> list[StepPlan]:
    """루틴 알람의 단계. PRESET = 버전 시간이 있는 항목만, CUSTOM = 그중 사용자가 뺀 항목 제외.

    코칭 0분(수행 안 함)이면 단계를 만들지 않는다. 항목을 빼면 그날 루틴은 버전 시간보다 짧아진다.
    """
    if coaching_min == 0:
        return []
    if routine_mode not in codes.ROUTINE_MODES:
        raise ValidationFailed(f"routine_mode는 PRESET/CUSTOM이에요: {routine_mode!r}")
    excluded = set(excluded_item_ids) if routine_mode == "CUSTOM" else set()
    chosen = [(it, version_seconds(it, coaching_min)) for it in sorted(items, key=lambda it: it.item_order)]
    chosen = [(it, sec) for it, sec in chosen if sec is not None and it.template_item_id not in excluded]
    return [StepPlan(i, it.item_name, it.template_item_id, sec, it.response_type, it.script)
            for i, (it, sec) in enumerate(chosen, 1)]


class ContentStep(Protocol):
    """AI 파트 compose_content_steps()가 돌려주는 단계 (ai.live.inputs.Step과 같은 속성)."""
    name: str
    planned_sec: int | None
    response_type: str
    script: str


def content_steps(steps: Iterable[ContentStep], coaching_min: int) -> list[StepPlan]:
    """콘텐츠 알람의 단계 = AI가 구성한 단계. 프리셋 항목이 아니므로 template_item_id는 비운다."""
    if coaching_min == 0:
        return []
    return [StepPlan(i, s.name, None, s.planned_sec, s.response_type if s.response_type in codes.RESPONSE_TYPES else "QA",
                     s.script or None) for i, s in enumerate(steps, 1)]
