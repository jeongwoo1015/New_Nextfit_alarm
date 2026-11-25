"""11/25: 코칭 강도 가상 사용자 시뮬레이션. 사용자별 학습이 전략을 갈라 고르는지, 안전 규칙이 항상 지켜지는지만 빠르게 본다(효과의 근거는 아니다)."""
import pytest

from ai.personalization.coach_sim import ALL_TYPES, POLICIES, SET_TYPES, VAL_TYPES, best_arm, response_of, run_all, run_policy


def test_types_are_split_and_best_arm_definition():
    assert not set(SET_TYPES) & set(VAL_TYPES) and set(ALL_TYPES) == set(SET_TYPES) | set(VAL_TYPES)
    assert best_arm(response_of("soft_lover", 0), False, None) == "gentle"
    assert best_arm(response_of("firm_lover", 0), False, None) == "push" and best_arm(response_of("firm_lover", 0), True, None) == "standard"   # 피곤한 날은 push 제외
    assert best_arm(response_of("tired_split", 0), False, None) == "push" and best_arm(response_of("tired_split", 0), True, None) == "gentle"
    assert best_arm(response_of("firm_lover", 0), False, "KIND") == "standard"                                                                # KIND도 push 제외
    assert best_arm(response_of("lowwake_soft", 0, low_wake=True), False, None) == "gentle" and best_arm(response_of("lowwake_soft", 0), False, None) == "standard"
    assert best_arm(response_of("habit_change", 0), False, None) == "gentle" and best_arm(response_of("habit_change", 40), False, None) == "push"


def test_same_seed_same_result_and_bad_input_rejected():
    a, b = run_policy("bandit", "soft_lover", 40, 5), run_policy("bandit", "soft_lover", 40, 5)
    assert a["arms"] == b["arms"] and a["fallbacks"] == 0
    with pytest.raises(ValueError):
        run_policy("nope", "soft_lover")
    with pytest.raises(ValueError):
        run_policy("bandit", "alien")


@pytest.mark.parametrize("policy", POLICIES)
@pytest.mark.parametrize("user_type", ALL_TYPES)
def test_never_pushes_on_tired_days_or_for_kind(policy, user_type):
    r = run_policy(policy, user_type, days=60, seed=1)
    assert r["push_on_tired"] == 0 and r["fallbacks"] == 0
    if user_type == "kind_firm":
        assert "push" not in r["arms"]


def test_per_user_learning_beats_always_standard_and_finds_context():
    res = run_all(types=("soft_lover", "tired_split"), policies=("standard", "bandit"), days=60, reps=6)
    for t in ("soft_lover", "tired_split"):
        assert res[t]["bandit"]["avg_done"] > res[t]["standard"]["avg_done"] + 0.05
    assert res["tired_split"]["bandit"]["correct_rate"] > 0.5


def test_no_difference_user_loses_nothing_and_standard_best_loses_little():
    res = run_all(types=("flat", "standard_best"), policies=("standard", "bandit"), days=60, reps=6)
    assert abs(res["flat"]["bandit"]["avg_done"] - res["flat"]["standard"]["avg_done"]) < 0.03
    assert res["standard_best"]["standard"]["avg_done"] - res["standard_best"]["bandit"]["avg_done"] < 0.10
