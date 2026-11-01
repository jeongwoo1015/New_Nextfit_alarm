import pytest

from backend.core.errors import ValidationFailed
from backend.domain.routine_rules import apply_conversation, apply_durations, build_routine
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


def test_conversation_remove_and_restore():
    c = apply_conversation(Q, remove=[STRETCH], restore=[], max_sec=900)
    assert c.overrides == {STRETCH: 0} and c.removed == [STRETCH]
    c = apply_conversation(build_routine(Q_ITEMS, c.overrides), remove=[], restore=[STRETCH], max_sec=900)
    assert c.overrides == {} and c.restored == [STRETCH]


def test_conversation_skips_requests_that_break_rules():
    c = apply_conversation(Q, remove=[STRETCH, WATER, CARDIO, "nope"], restore=[], max_sec=900)
    assert c.removed == [STRETCH, WATER] and c.rejected == [CARDIO, "nope"]          # 마지막 항목은 남김
    # 유산소를 늘려 둔 상태(0+60+840=900)에서 스트레칭을 다시 넣으면 1080초 → 반영하지 않음
    c = apply_conversation(build_routine(Q_ITEMS, {STRETCH: 0, CARDIO: 840}), remove=[], restore=[STRETCH], max_sec=900)
    assert c.rejected == [STRETCH] and c.overrides == {STRETCH: 0, CARDIO: 840}

