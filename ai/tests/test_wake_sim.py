"""11/21: 기상 볼륨 가상 사용자 시뮬레이션. 알고리즘이 사용자별 시작 단계를 찾아가는지만 빠르게 본다(효과의 근거는 아니다)."""
from ai.personalization.wake_sim import POLICIES, SET_TYPES, VAL_TYPES, correct_start, run_all, run_policy, LADDERS


def test_types_are_split_and_correct_start_definition():
    assert not set(SET_TYPES) & set(VAL_TYPES)
    assert correct_start(LADDERS["easy"]) == 1 and correct_start(LADDERS["normal"]) == 3 and correct_start(LADDERS["no_effect"]) == 1
    assert correct_start(LADDERS["very_deep"]) == 3


def test_same_seed_same_result_and_bad_input_rejected():
    a, b = run_policy("bandit", "normal", 40, 5), run_policy("bandit", "normal", 40, 5)
    assert a["starts"] == b["starts"]
    import pytest
    with pytest.raises(ValueError):
        run_policy("nope", "normal")


def test_bandit_finds_each_users_level_and_beats_wrong_fixed_choice():
    res = run_all(types=("easy", "normal", "deep"), policies=("fixed1", "fixed3", "bandit"), days=60, reps=6)
    assert res["easy"]["bandit"]["avg_start"] < 1.8 and res["easy"]["bandit"]["correct_rate"] > 0.7
    assert res["normal"]["bandit"]["avg_start"] > 2.4 and res["normal"]["bandit"]["immediate_rate"] > res["normal"]["fixed1"]["immediate_rate"] + 0.3
    assert res["deep"]["bandit"]["immediate_rate"] > res["deep"]["fixed1"]["immediate_rate"] + 0.3


def test_no_effect_user_is_not_pushed_louder_than_needed_and_habit_change_is_followed():
    res = run_all(types=("no_effect", "habit_change"), policies=("fixed3", "bandit"), days=60, reps=6)
    assert res["no_effect"]["bandit"]["avg_start"] < res["no_effect"]["fixed3"]["avg_start"] - 0.8
    assert res["habit_change"]["bandit"]["correct_rate"] > 0.7
