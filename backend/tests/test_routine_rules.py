import pytest

from backend.core.errors import ValidationFailed
from backend.domain.routine_rules import (apply_conversation, apply_durations, routine_items, safe_write_order,
                                          total_sec)
from backend.repositories.seed import seed_templates, template_id

_, ITEMS = seed_templates()
Q = [i for i in ITEMS if i.template_id == template_id("Q")]      # 스트레칭 180, 물 60, 유산소 360
STRETCH, WATER, CARDIO = (i.template_item_id for i in Q)


def test_every_seed_preset_totals_600():
    for key in "MNOPQ":
        assert sum(i.default_duration_sec for i in ITEMS if i.template_id == template_id(key)) == 600
    assert len(ITEMS) == 24


def test_apply_durations_stores_only_changes():
    assert apply_durations(Q, {}, 900) == {}
    assert apply_durations(Q, {STRETCH: 180, WATER: 0}, 900) == {WATER: 0}          # 기본값과 같으면 저장 안 함
    assert apply_durations(Q, {CARDIO: 600}, 900) == {CARDIO: 600}                  # 합계 840


@pytest.mark.parametrize("durations, code", [
    ({CARDIO: 690}, "상한"),                             # 180 + 60 + 690 = 930
    ({STRETCH: 0, WATER: 0, CARDIO: 0}, "모두"),
    ({WATER: 45}, "30초"),
    ({WATER: -30}, "30초"),
])
def test_apply_durations_rejects(durations, code):
    with pytest.raises(ValidationFailed, match=code):
        apply_durations(Q, durations, 900)


def test_unknown_item_rejected():
    other = next(i for i in ITEMS if i.template_id == template_id("M")).template_item_id
    with pytest.raises(ValidationFailed) as e:
        apply_durations(Q, {other: 60}, 900)
    assert e.value.code == "ROUTINE_ITEM_UNKNOWN"


def test_conversation_remove_and_restore():
    c = apply_conversation(Q, {}, remove=[STRETCH], restore=[], max_sec=900)
    assert c.overrides == {STRETCH: 0} and c.removed == [STRETCH]
    c = apply_conversation(Q, c.overrides, remove=[], restore=[STRETCH], max_sec=900)
    assert c.overrides == {} and c.restored == [STRETCH]


def test_conversation_skips_requests_that_break_rules():
    c = apply_conversation(Q, {}, remove=[STRETCH, WATER, CARDIO, "nope"], restore=[], max_sec=900)
    assert c.removed == [STRETCH, WATER] and c.rejected == [CARDIO, "nope"]          # 마지막 항목은 남김
    # 유산소를 늘려 둔 상태(0+60+840=900)에서 스트레칭을 다시 넣으면 1080초 → 반영하지 않음
    c = apply_conversation(Q, {STRETCH: 0, CARDIO: 840}, remove=[], restore=[STRETCH], max_sec=900)
    assert c.rejected == [STRETCH] and c.overrides == {STRETCH: 0, CARDIO: 840}


def test_safe_write_order_keeps_each_step_valid():
    current, target = {CARDIO: 660}, {STRETCH: 300, CARDIO: 540, WATER: 0}           # 900 → 840
    ops = safe_write_order(Q, current, target)
    state = dict(current)
    for item_id, sec in ops:
        state.pop(item_id, None) if sec is None else state.__setitem__(item_id, sec)
        items = routine_items(Q, state)
        assert total_sec(items) <= 900 and any(r.duration_sec > 0 for r in items)
    assert state == target
