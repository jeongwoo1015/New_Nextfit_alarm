from dataclasses import replace
from datetime import date, datetime, time

import pytest

from backend.core.errors import ValidationFailed
from backend.core.timeutil import KST
from backend.domain.alarm_rules import next_ring, pick_override_alarm, rings_on, validate_alarm
from backend.domain.models import Alarm

ROUTINE = Alarm("a1", "u1", time(7, 0), "ROUTINE", repeat_days="1111100", template_id="t1")
CONTENT = Alarm("a2", "u1", time(6, 30), "CONTENT", content_type="QUIZ", content_option="HISTORY")


def test_valid_alarms_pass():
    validate_alarm(ROUTINE)
    validate_alarm(CONTENT)
    validate_alarm(replace(CONTENT, content_option=None))


@pytest.mark.parametrize("alarm, hint", [
    (replace(ROUTINE, template_id=None), "template_id"),
    (replace(ROUTINE, content_type="QUIZ"), "content_type"),
    (replace(CONTENT, content_type=None, content_option=None), "content_type"),
    (replace(CONTENT, template_id="t1"), "template_id"),
    (replace(CONTENT, content_option="ENGLISH"), "content_option"),
    (replace(ROUTINE, repeat_days="111110"), "repeat_days"),
    (replace(ROUTINE, repeat_days="11111a0"), "repeat_days"),
    (replace(ROUTINE, alarm_mode="BEFORE_5"), "alarm_mode"),
    (replace(ROUTINE, label="가" * 21), "label"),
    (replace(ROUTINE, morning_call_type="BOTH"), "morning_call_type"),
])
def test_invalid_alarms(alarm, hint):
    with pytest.raises(ValidationFailed, match=hint):
        validate_alarm(alarm)


def test_rings_on_uses_monday_first_order():
    assert rings_on(ROUTINE, date(2026, 11, 2))        # 월
    assert rings_on(ROUTINE, date(2026, 11, 6))        # 금
    assert not rings_on(ROUTINE, date(2026, 11, 7))    # 토
    assert not rings_on(replace(ROUTINE, enabled=False), date(2026, 11, 2))


def test_next_ring_picks_earliest_upcoming():
    friday_night = datetime(2026, 11, 6, 23, 0, tzinfo=KST)
    weekend = Alarm("a3", "u1", time(9, 0), "ROUTINE", repeat_days="0000011", template_id="t1")
    a, at = next_ring([ROUTINE, weekend], friday_night)
    assert a.alarm_id == "a3" and at == datetime(2026, 11, 7, 9, 0, tzinfo=KST)
    a, at = next_ring([ROUTINE], friday_night)
    assert at == datetime(2026, 11, 9, 7, 0, tzinfo=KST)   # 다음 주 월요일
    assert next_ring([replace(ROUTINE, repeat_days="0000000")], friday_night) is None


def test_override_alarm_is_the_one_ringing_on_apply_date():
    thursday_night = datetime(2026, 11, 5, 23, 30, tzinfo=KST)
    assert pick_override_alarm([ROUTINE, CONTENT], thursday_night).alarm_id == "a2"   # 금 06:30이 07:00보다 먼저
    after_midnight = datetime(2026, 11, 6, 1, 0, tzinfo=KST)                           # 여전히 목요일 밤 → 금요일 아침
    assert pick_override_alarm([ROUTINE], after_midnight).alarm_id == "a1"


def test_no_override_alarm_when_nothing_rings_next_morning():
    friday_night = datetime(2026, 11, 6, 23, 0, tzinfo=KST)                            # 토요일 아침엔 평일 알람 없음
    assert pick_override_alarm([ROUTINE], friday_night) is None
