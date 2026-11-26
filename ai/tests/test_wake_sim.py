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


def test_short_hard_has_different_correct_start_by_sleep_length():
    from ai.personalization.wake_sim import ladder_of
    assert correct_start(ladder_of("short_hard", 0, True)) == 3 and correct_start(ladder_of("short_hard", 0, False)) == 1


def test_bandit_learns_the_sleep_length_context_for_short_hard():
    """11/25: 수면이 짧은 날은 크게, 긴 날은 부드럽게 시작하는지(상황 학습)."""
    gaps = []
    for seed in range(8):
        r = run_policy("bandit", "short_hard", 60, 2000 + seed)
        ev = range(21, 60)
        short = [r["starts"][i] for i in ev if r["shorts"][i]]
        long_ = [r["starts"][i] for i in ev if not r["shorts"][i]]
        gaps.append(sum(short) / len(short) - sum(long_) / len(long_))
    assert sum(gaps) / len(gaps) > 1.0


def test_snoozing_does_not_change_volume_learning():
    """11/26 보상 분리: 미루기는 볼륨 학습에 영향이 없다. 같은 아침을 겪은 snoozer와 normal의 학습 기록이 같아야 한다."""
    from ai.personalization.wake_sim import SNOOZE_RATE
    assert 0 < SNOOZE_RATE < 0.5
    a = run_policy("bandit", "snoozer", 60, 7)
    b = run_policy("bandit", "normal", 60, 7)
    assert a["shorts"] == b["shorts"] and a["starts"] == b["starts"]      # 같은 환경, 같은 선택
    assert a["ladder"].counts == b["ladder"].counts                       # 미룬 날이 학습 기록을 바꾸지 않는다
    assert 0 < sum(a["snoozes"]) < 60 and not any(b["snoozes"])           # snoozer만 실제로 미룬다


def test_snoozer_is_still_started_at_the_level_it_needs():
    res = run_all(types=("snoozer",), policies=("fixed1", "fixed3", "bandit"), days=60, reps=6)
    assert res["snoozer"]["bandit"]["correct_rate"] > 0.6
    assert res["snoozer"]["bandit"]["avg_start"] > 2.0
