from dataclasses import dataclass
from datetime import date, time

import pytest

from backend.domain.models import Alarm, AppConfig, NightRecord
from backend.domain.routine_rules import build_routine
from backend.domain.session_rules import (content_duration_sec, content_steps, planned_total_sec, resolve_snapshot,
                                          routine_steps)
from backend.repositories.seed import seed_templates, template_id

TEMPLATES, ITEMS = seed_templates()
N_ITEMS = [i for i in ITEMS if i.template_id == template_id("N")]
ALARM = Alarm("a1", "u1", time(7, 0), "ROUTINE", template_id=template_id("N"))
TODAY = date(2026, 11, 2)


def night(**over):
    base = dict(night_record_id="r1", user_id="u1", record_date=date(2026, 11, 1), apply_date=TODAY)
    return NightRecord(**{**base, **over})


def test_default_routine_uses_preset_default_times():
    steps = routine_steps(build_routine(reversed(N_ITEMS), {}))     # 입력 순서와 무관하게 ITEM_ORDER 순
    assert [s.step_name for s in steps] == [i.item_name for i in N_ITEMS]
    assert [s.step_order for s in steps] == list(range(1, 7))
    assert planned_total_sec(steps) == 600
    assert steps[0].template_item_id == N_ITEMS[0].template_item_id and steps[0].response_type == "PLAY"
    assert steps[0].script and steps[0].step_detail is None


def test_alarm_overrides_change_times_and_zero_removes_item():
    stretch, bed = N_ITEMS[0].template_item_id, N_ITEMS[3].template_item_id
    steps = routine_steps(build_routine(N_ITEMS, {stretch: 300, bed: 0}))
    assert bed not in [s.template_item_id for s in steps]
    assert [s.step_order for s in steps] == [1, 2, 3, 4, 5]                    # 번호는 다시 1부터
    assert steps[0].planned_duration_sec == 300
    assert planned_total_sec(steps) == 600 + 120 - 60


def test_skip_coaching_makes_no_steps():
    assert routine_steps(build_routine(N_ITEMS, {}), coaching_skipped=True) == []


def test_snapshot_without_override_uses_alarm():
    s = resolve_snapshot(ALARM, None, TODAY)
    assert (s.base_wake_time, s.target_wake_time, s.coaching_skipped) == (time(7, 0), time(7, 0), False)
    assert (s.template_id, s.content_type) == (template_id("N"), None)


def test_override_changes_wake_time_and_skips_coaching():
    s = resolve_snapshot(ALARM, night(override_alarm_id="a1", override_wake_time=time(7, 30), override_skip_coaching=True), TODAY)
    assert (s.base_wake_time, s.target_wake_time, s.coaching_skipped) == (time(7, 0), time(7, 30), True)


@pytest.mark.parametrize("rec", [
    night(override_alarm_id="other", override_skip_coaching=True),                           # 다른 알람
    night(override_alarm_id="a1", override_skip_coaching=True, apply_date=date(2026, 11, 3)),  # 다른 날
])
def test_override_not_applied(rec):
    assert resolve_snapshot(ALARM, rec, TODAY).coaching_skipped is False


@dataclass
class AIStep:
    name: str
    planned_sec: int | None
    response_type: str
    script: str


def test_content_steps_keep_detail_for_reconnect():
    steps = content_steps([AIStep("퀴즈 1번", 200, "SPEECH", "문제: ...\n정답: ..."), AIStep("퀴즈 2번", 400, "WEIRD", "")])
    assert [(s.step_order, s.template_item_id) for s in steps] == [(1, None), (2, None)]
    assert steps[0].step_detail == {"response_type": "SPEECH", "script": "문제: ...\n정답: ..."}
    assert steps[1].response_type == "QA" and steps[1].step_detail == {"response_type": "QA", "script": None}
    assert content_steps([AIStep("x", 1, "QA", "y")], coaching_skipped=True) == []


def test_content_alarm_snapshot_and_fixed_duration():
    a = Alarm("a2", "u1", time(6, 30), "CONTENT", content_type="QUIZ", content_option="HISTORY")
    s = resolve_snapshot(a, None, TODAY)
    assert (s.morning_call_type, s.template_id, s.content_type, s.content_option) == ("CONTENT", None, "QUIZ", "HISTORY")
    assert content_duration_sec(AppConfig()) == 300                     # 팀 확정 5분
    assert content_duration_sec(AppConfig(content_duration_sec=480)) == 480
