"""모닝 세션 규칙: 그날 적용할 값(스냅샷)과 세션 단계 만들기 (정의서 11/01 "세션 단계를 만드는 규칙")."""
from datetime import date
from typing import Iterable, Protocol

from . import codes
from .models import Alarm, AlarmRoutineItem, AppConfig, MorningSession, NightRecord, SessionSnapshot, StepPlan


def override_applies(alarm: Alarm, night: NightRecord | None, session_date: date) -> bool:
    """밤 대화에서 동의한 하루 한정 변경이 이 알람의 이 날짜에 해당하는가."""
    return bool(night and night.apply_date == session_date and night.override_alarm_id == alarm.alarm_id)


def resolve_snapshot(alarm: Alarm, night: NightRecord | None, session_date: date) -> SessionSnapshot:
    """알람 설정 + 하루 한정 변경(알람 시각, 내일 코칭 안 하기) → 그날 세션에 남길 값."""
    wake, skipped = alarm.alarm_time, False
    if override_applies(alarm, night, session_date):
        wake = night.override_wake_time or wake
        skipped = night.override_skip_coaching
    return SessionSnapshot(
        user_id=alarm.user_id, alarm_id=alarm.alarm_id, session_date=session_date,
        morning_call_type=alarm.morning_call_type, template_id=alarm.template_id,
        content_type=alarm.content_type, content_option=alarm.content_option,
        base_wake_time=alarm.alarm_time, target_wake_time=wake, coaching_skipped=skipped,
    )


def routine_steps(items: Iterable[AlarmRoutineItem], coaching_skipped: bool = False) -> list[StepPlan]:
    """루틴 알람의 단계 = 알람의 항목 중 시간이 0보다 큰 것, 그 시간으로. 코칭을 건너뛰는 날은 단계가 없다."""
    if coaching_skipped:
        return []
    chosen = [r for r in sorted(items, key=lambda r: r.item_order) if r.duration_sec > 0]
    return [StepPlan(n, r.item_name, r.template_item_id, r.duration_sec, r.response_type, r.script)
            for n, r in enumerate(chosen, 1)]


def content_duration_sec(config: AppConfig) -> int:
    """콘텐츠 고정 시간. 정의서에서 값이 정해지기 전(NULL)에는 기본안 600초."""
    return config.content_duration_sec or codes.DEFAULT_CONTENT_DURATION_SEC


class ContentStep(Protocol):
    """AI 파트 compose_content_steps()가 돌려주는 단계 (ai.live.inputs.Step과 같은 속성)."""
    name: str
    planned_sec: int | None
    response_type: str
    script: str


def content_steps(steps: Iterable[ContentStep], coaching_skipped: bool = False) -> list[StepPlan]:
    """콘텐츠 알람의 단계 = AI가 구성한 단계. 프리셋 항목이 아니므로 template_item_id는 비우고,
    재연결 때 같은 내용으로 이어 가도록 응답 유형·내용을 step_detail에 담는다."""
    if coaching_skipped:
        return []
    plans = []
    for n, s in enumerate(steps, 1):
        rtype = s.response_type if s.response_type in codes.RESPONSE_TYPES else "QA"
        script = s.script or None
        plans.append(StepPlan(n, s.name, None, s.planned_sec or None, rtype, script,
                              {"response_type": rtype, "script": script}))
    return plans


def planned_total_sec(steps: Iterable[StepPlan]) -> int:
    """RS_MORNING_SESSION.PLANNED_TOTAL_SEC (그날 만든 단계 시간 합계)."""
    return sum(s.planned_duration_sec or 0 for s in steps)


def judge_wake(s: MorningSession) -> bool:
    """기상 성공 (11/01 팀 확정): 브리핑 전 확인 멘트(2~3개)에 모두 응답했고, 그때까지
    ① 스누즈("5분 뒤 다시 울리기")를 한 번도 하지 않았고 ② 무응답으로 AI 재기상 음악이 한 번도 나오지 않았다.
    확인 멘트를 마친 시점(wake_checked_at)에 부른다. 그 뒤의 재기상·스누즈는 판정을 바꾸지 않는다."""
    return s.first_response_at is not None and s.snooze_count == 0 and s.rewake_count == 0
