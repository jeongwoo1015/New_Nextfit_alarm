"""알람 목록·설정 (홈 화면, 알람 세부 설정)."""
from dataclasses import dataclass, replace

from backend.core.auth import ensure_owner
from backend.core.errors import NotFound, ValidationFailed
from backend.domain.alarm_rules import validate_alarm
from backend.domain.models import Alarm, RoutineItem
from backend.domain.routine_rules import apply_durations, routine_items, total_sec
from backend.repositories.registry import Repositories

EDITABLE = {"alarm_time", "repeat_days", "label", "alarm_mode", "morning_call_type", "template_id",
            "content_type", "content_option", "enabled"}


@dataclass(frozen=True)
class AlarmRoutine:
    """알람 세부 설정의 루틴 항목 화면."""
    alarm: Alarm
    items: list[RoutineItem]
    max_sec: int

    @property
    def total_sec(self) -> int:
        return total_sec(self.items)


class AlarmService:
    def __init__(self, repos: Repositories):
        self.r = repos

    def list(self, user_id: str) -> list[Alarm]:
        return self.r.alarms.list_by_user(user_id)

    def get(self, user_id: str, alarm_id: str) -> Alarm:
        alarm = self.r.alarms.get(alarm_id)
        if not alarm:
            raise NotFound("알람을 찾을 수 없어요", "ALARM_NOT_FOUND")
        ensure_owner(user_id, alarm.user_id)
        return alarm

    def create(self, user_id: str, fields: dict) -> Alarm:
        alarm = Alarm(alarm_id="", user_id=user_id, **_editable(fields))
        self._validate(alarm, previous=None)
        return self.r.alarms.create(alarm)

    def update(self, user_id: str, alarm_id: str, changes: dict) -> Alarm:
        """프리셋을 바꾸거나 콘텐츠 알람으로 바꾸면 예전 항목 시간 설정은 DB 트리거가 지운다."""
        current = self.get(user_id, alarm_id)
        alarm = replace(current, **_editable(changes))
        self._validate(alarm, previous=current)
        return self.r.alarms.update(alarm)

    def delete(self, user_id: str, alarm_id: str) -> None:
        self.get(user_id, alarm_id)
        self.r.alarms.delete(alarm_id)

    # ---------- 알람 세부 설정: 루틴 항목 시간 ----------
    def routine(self, user_id: str, alarm_id: str) -> AlarmRoutine:
        alarm = self._routine_alarm(user_id, alarm_id)
        items = routine_items(self.r.templates.list_items(alarm.template_id), self.r.alarm_routine_items.get_overrides(alarm_id))
        return AlarmRoutine(alarm, items, self.r.config.get().routine_max_sec)

    def set_routine(self, user_id: str, alarm_id: str, durations: dict[str, int]) -> AlarmRoutine:
        """항목 시간 전체 교체. durations에 없는 항목은 프리셋 기본 시간, 0초는 그 알람에서 빼기."""
        alarm = self._routine_alarm(user_id, alarm_id)
        overrides = apply_durations(self.r.templates.list_items(alarm.template_id), durations, self.r.config.get().routine_max_sec)
        self.r.alarm_routine_items.replace(alarm_id, user_id, overrides)
        return self.routine(user_id, alarm_id)

    def _routine_alarm(self, user_id: str, alarm_id: str) -> Alarm:
        alarm = self.get(user_id, alarm_id)
        if alarm.morning_call_type != "ROUTINE":
            raise ValidationFailed("루틴 알람에만 항목 시간을 설정할 수 있어요", "NOT_ROUTINE_ALARM")
        return alarm

    def _validate(self, alarm: Alarm, previous: Alarm | None) -> None:
        validate_alarm(alarm)
        if alarm.morning_call_type != "ROUTINE":
            return
        template = self.r.templates.get_template(alarm.template_id)
        if not template:
            raise ValidationFailed("없는 루틴 프리셋이에요")
        # 비활성 프리셋은 새로 고를 수 없다. 이미 쓰던 알람은 그대로 둘 수 있다(정의서: 삭제 대신 비활성화)
        if not template.is_active and (previous is None or previous.template_id != alarm.template_id):
            raise ValidationFailed("더 이상 선택할 수 없는 루틴 프리셋이에요")


def _editable(fields: dict) -> dict:
    unknown = set(fields) - EDITABLE
    if unknown:
        raise ValidationFailed(f"바꿀 수 없는 항목이에요: {', '.join(sorted(unknown))}")
    return fields
