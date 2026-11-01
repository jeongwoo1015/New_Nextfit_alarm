from dataclasses import dataclass
from datetime import date, time

import pytest

from backend.domain.models import Alarm, NightRecord, TemplateItem
from backend.domain.session_rules import content_steps, resolve_snapshot, routine_steps

# 정의서 초기 프리셋 N (10분 버전 합 600초, 20분 버전 합 1200초)
N_ITEMS = [
    TemplateItem("n1", "N", "스트레칭(축소)", 1, 180, 300, "PLAY", "스트레칭 순서"),
    TemplateItem("n2", "N", "햇빛 보기", 2, 60, 60, "QA"),
    TemplateItem("n3", "N", "물 한 잔", 3, 60, 60, "QA"),
    TemplateItem("n4", "N", "이불 정리", 4, 60, 60, "QA"),
    TemplateItem("n5", "N", "세수·양치", 5, 120, 180, "QA"),
    TemplateItem("n6", "N", "오늘 할 일 3개 말하기", 6, 120, 240, "SPEECH"),
    TemplateItem("n7", "N", "가장 중요한 일 1개와 시작 시각", 7, None, 180, "SPEECH"),
    TemplateItem("n8", "N", "하루 목표 한 줄", 8, None, 120, "SPEECH"),
]
ALARM = Alarm("a1", "u1", time(7, 0), "ROUTINE", template_id="N", routine_mode="CUSTOM", coaching_min=20)
TODAY = date(2026, 11, 2)


def night(**over):
    base = dict(night_record_id="r1", user_id="u1", record_date=date(2026, 11, 1), apply_date=TODAY)
    return NightRecord(**{**base, **over})


def test_preset_versions_follow_seed_durations():
    ten = routine_steps(N_ITEMS, 10, "PRESET")
    twenty = routine_steps(reversed(N_ITEMS), 20, "PRESET")   # 입력 순서와 무관하게 ITEM_ORDER 순
    assert [s.step_name for s in ten] == [i.item_name for i in N_ITEMS[:6]]
    assert sum(s.planned_duration_sec for s in ten) == 600
    assert sum(s.planned_duration_sec for s in twenty) == 1200
    assert [s.step_order for s in twenty] == list(range(1, 9))
    assert twenty[0].template_item_id == "n1" and twenty[0].script == "스트레칭 순서" and twenty[0].response_type == "PLAY"


def test_custom_excludes_removed_items_but_preset_ignores_them():
    custom = routine_steps(N_ITEMS, 20, "CUSTOM", excluded_item_ids={"n4", "n8"})
    assert [s.template_item_id for s in custom] == ["n1", "n2", "n3", "n5", "n6", "n7"]
    assert [s.step_order for s in custom] == [1, 2, 3, 4, 5, 6]                 # 번호는 다시 1부터
    assert sum(s.planned_duration_sec for s in custom) == 1200 - 60 - 120      # 뺀 만큼 짧아짐
    assert len(routine_steps(N_ITEMS, 20, "PRESET", excluded_item_ids={"n4"})) == 8


def test_zero_coaching_makes_no_steps():
    assert routine_steps(N_ITEMS, 0, "PRESET") == []


def test_snapshot_without_override_uses_alarm():
    s = resolve_snapshot(ALARM, None, TODAY)
    assert (s.base_wake_time, s.target_wake_time, s.coaching_min) == (time(7, 0), time(7, 0), 20)
    assert (s.template_id, s.routine_mode, s.content_type) == ("N", "CUSTOM", None)


def test_override_changes_wake_time_and_coaching():
    s = resolve_snapshot(ALARM, night(override_alarm_id="a1", override_wake_time=time(7, 30), override_coaching_min=10), TODAY)
    assert (s.base_wake_time, s.target_wake_time, s.coaching_min) == (time(7, 0), time(7, 30), 10)
    assert len(routine_steps(N_ITEMS, s.coaching_min, s.routine_mode)) == 6     # 20분 알람이라도 10분 버전


def test_override_skip_coaching():
    s = resolve_snapshot(ALARM, night(override_alarm_id="a1", override_coaching_min=0), TODAY)
    assert s.coaching_min == 0 and s.target_wake_time == time(7, 0)


@pytest.mark.parametrize("rec", [
    night(override_alarm_id="other", override_coaching_min=0),                           # 다른 알람
    night(override_alarm_id="a1", override_coaching_min=0, apply_date=date(2026, 11, 3)),  # 다른 날
])
def test_override_not_applied(rec):
    assert resolve_snapshot(ALARM, rec, TODAY).coaching_min == 20


@dataclass
class AIStep:
    name: str
    planned_sec: int | None
    response_type: str
    script: str


def test_content_steps_have_no_template_item():
    steps = content_steps([AIStep("퀴즈 1번", 200, "SPEECH", "문제: ...\n정답: ..."), AIStep("퀴즈 2번", 400, "WEIRD", "")], 10)
    assert [(s.step_order, s.template_item_id) for s in steps] == [(1, None), (2, None)]
    assert steps[1].response_type == "QA" and steps[1].script is None
    assert content_steps([AIStep("x", 1, "QA", "y")], 0) == []


def test_content_alarm_snapshot():
    a = Alarm("a2", "u1", time(6, 30), "CONTENT", content_type="QUIZ", content_option="HISTORY", coaching_min=10)
    s = resolve_snapshot(a, None, TODAY)
    assert (s.morning_call_type, s.template_id, s.content_type, s.content_option) == ("CONTENT", None, "QUIZ", "HISTORY")
    assert s.coaching_min == 10 and s.routine_mode is None
