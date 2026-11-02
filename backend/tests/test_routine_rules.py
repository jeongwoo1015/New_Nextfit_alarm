import pytest

from backend.core.errors import ValidationFailed
from dataclasses import replace

from backend.domain.routine_rules import apply_changes, apply_durations, build_routine, overrides_of, tomorrow_rows
from backend.repositories.seed import seed_templates, template_id

_, ITEMS = seed_templates()
Q_ITEMS = [i for i in ITEMS if i.template_id == template_id("Q")]      # 스트레칭 180, 물 60, 유산소 360
Q = build_routine(Q_ITEMS, {})
STRETCH, WATER, CARDIO = (i.template_item_id for i in Q_ITEMS)


def test_every_seed_preset_totals_600():
    for key in "MNOPQ":
        assert sum(i.default_duration_sec for i in ITEMS if i.template_id == template_id(key)) == 600
    assert len(ITEMS) == 24


def test_build_routine_marks_customized():
    r = build_routine(Q_ITEMS, {WATER: 0})
    assert [(x.item_name, x.duration_sec, x.customized) for x in r] == [
        ("스트레칭 (축소)", 180, False), ("물 한 잔", 0, True), ("유산소 (제자리 러닝 등)", 360, False)]


def test_apply_durations_stores_only_changes():
    assert apply_durations(Q, {}, 900) == {}
    assert apply_durations(Q, {STRETCH: 180, WATER: 0}, 900) == {WATER: 0}          # 기본값과 같으면 저장 안 함
    assert apply_durations(Q, {CARDIO: 600}, 900) == {CARDIO: 600}                  # 합계 840
    assert apply_durations(build_routine(Q_ITEMS, {WATER: 0}), {}, 900) == {}       # 전체 교체: 안 보낸 항목은 기본값


@pytest.mark.parametrize("durations, code", [
    ({CARDIO: 690}, "ROUTINE_OVER_LIMIT"),               # 180 + 60 + 690 = 930
    ({STRETCH: 0, WATER: 0, CARDIO: 0}, "ROUTINE_ALL_REMOVED"),
    ({WATER: 45}, "VALIDATION_FAILED"),
    ({WATER: -30}, "VALIDATION_FAILED"),
    ({"nope": 60}, "ROUTINE_ITEM_UNKNOWN"),
])
def test_apply_durations_rejects(durations, code):
    with pytest.raises(ValidationFailed) as e:
        apply_durations(Q, durations, 900)
    assert e.value.code == code


def test_always_changes_remove_shorten_restore():
    r = apply_changes(Q, [(STRETCH, 0), (CARDIO, 300)], max_sec=900)
    assert r.applied == [STRETCH, CARDIO] and overrides_of(r.items) == {STRETCH: 0, CARDIO: 300}
    r = apply_changes(r.items, [(STRETCH, None)], max_sec=900)                     # 되돌리기 = 프리셋 기본 시간
    assert overrides_of(r.items) == {CARDIO: 300}


def test_changes_skip_requests_that_break_rules():
    r = apply_changes(Q, [(STRETCH, 0), (WATER, 0), (CARDIO, 0), ("nope", 0), (WATER, 45)], max_sec=900)
    assert r.applied == [STRETCH, WATER] and r.rejected == [CARDIO, "nope", WATER]   # 마지막 항목은 남김, 30초 단위
    # 유산소를 늘려 둔 상태(0+60+840=900)에서 스트레칭을 되돌리면 1080초 → 반영하지 않음
    r = apply_changes(build_routine(Q_ITEMS, {STRETCH: 0, CARDIO: 840}), [(STRETCH, None)], max_sec=900)
    assert r.rejected == [STRETCH] and overrides_of(r.items) == {STRETCH: 0, CARDIO: 840}


def test_tomorrow_changes_are_relative_to_alarm_setting():
    alarm = build_routine(Q_ITEMS, {CARDIO: 420})                                  # 알람 설정: 유산소 7분
    night = [replace(i, alarm_duration_sec=i.duration_sec) for i in alarm]          # 내일만 목록 (아직 바꾼 것 없음)
    r = apply_changes(night, [(STRETCH, 60), (WATER, 0)], max_sec=900, restore_to_alarm=True)
    assert tomorrow_rows(r.items) == {STRETCH: 60, WATER: 0}
    r = apply_changes(r.items, [(WATER, None), (CARDIO, 420)], max_sec=900, restore_to_alarm=True)
    assert tomorrow_rows(r.items) == {STRETCH: 60}                                   # 되돌리기 = 알람 설정 시간
