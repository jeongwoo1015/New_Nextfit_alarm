"""알람 규칙: 저장 전 검사, 울리는 요일, 하루 한정 변경을 적용할 알람 고르기."""
import re
from datetime import date, datetime, timedelta

from backend.core.errors import ValidationFailed
from backend.core.timeutil import KST, apply_date, record_date, to_kst

from . import codes
from .models import Alarm

REPEAT_DAYS = re.compile(r"^[01]{7}$")
LABEL_MAX = 20
# 사용자가 정하는 RS_ALARM 컬럼 (id·user_id·시각 기록 제외)
ALARM_FIELDS = ("alarm_time", "repeat_days", "label", "alarm_mode", "morning_call_type", "template_id",
                "content_type", "content_option", "enabled")


def validate_alarm(a: Alarm) -> None:
    """정의서 RS_ALARM 제약. 어긋나면 ValidationFailed."""
    errors = []
    if not REPEAT_DAYS.match(a.repeat_days or ""):
        errors.append("repeat_days는 월~일 7자리 0/1이에요 (예: '1111100')")
    if a.label is not None and len(a.label) > LABEL_MAX:
        errors.append(f"label은 {LABEL_MAX}자 이내예요")
    if a.alarm_mode not in codes.ALARM_MODES:
        errors.append(f"alarm_mode는 {'/'.join(codes.ALARM_MODES)} 중 하나예요")

    if a.morning_call_type == "ROUTINE":
        if not a.template_id:
            errors.append("루틴 알람은 template_id가 필요해요")
        if a.content_type or a.content_option:
            errors.append("루틴 알람은 content_type·content_option을 비워요")
    elif a.morning_call_type == "CONTENT":
        if a.content_type not in codes.CONTENT_TYPES:
            errors.append(f"콘텐츠 알람은 content_type({'/'.join(codes.CONTENT_TYPES)})이 필요해요")
        elif a.content_option is not None and a.content_option not in codes.CONTENT_OPTIONS[a.content_type]:
            errors.append(f"{a.content_type}의 content_option은 {'/'.join(codes.CONTENT_OPTIONS[a.content_type])} 중 하나예요")
        if a.template_id:
            errors.append("콘텐츠 알람은 template_id를 비워요")
    else:
        errors.append(f"morning_call_type은 {'/'.join(codes.MORNING_CALL_TYPES)} 중 하나예요")

    if errors:
        raise ValidationFailed(" / ".join(errors))


def rings_on(a: Alarm, d: date) -> bool:
    """켜져 있고 그 요일이 1인가. REPEAT_DAYS는 월~일 순서라 date.weekday()(월=0)와 같다."""
    return a.enabled and a.repeat_days[d.weekday()] == "1"


def ring_at(a: Alarm, d: date) -> datetime:
    return datetime.combine(d, a.alarm_time, KST)


def next_ring(alarms: list[Alarm], after: datetime) -> tuple[Alarm, datetime] | None:
    """after 이후 가장 먼저 울릴 알람과 그 시각 (7일 안에 없으면 None)."""
    after = to_kst(after)
    best = None
    for i in range(8):
        d = after.date() + timedelta(days=i)
        for a in alarms:
            at = ring_at(a, d)
            if at > after and rings_on(a, d) and (best is None or (at, a.alarm_id) < (best[1], best[0].alarm_id)):
                best = (a, at)
        if best:
            return best
    return None


def pick_override_alarm(alarms: list[Alarm], saved_at: datetime) -> Alarm | None:
    """하루 한정 변경(OVERRIDE_*)과 대화로 바꾼 루틴 항목을 적용할 알람 — 서버가 정한다(AI는 알람을 고르지 않음).

    기본안 "다음에 울릴 알람"을, 밤 기록이 적용되는 아침(APPLY_DATE)에 울리는 알람으로 좁힌다.
    그날 울릴 알람이 없으면 None — 이때는 변경값을 저장하지 않는다(OVERRIDE_ALARM_ID 필수 제약).
    """
    target = apply_date(record_date(saved_at))
    after = to_kst(saved_at)
    candidates = [(ring_at(a, target), a.alarm_id, a) for a in alarms if rings_on(a, target) and ring_at(a, target) > after]
    return min(candidates)[2] if candidates else None
