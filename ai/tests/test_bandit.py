"""Bandit 테스트: 상황 칸, 안전 규칙 16가지 전수, 선택 확률, 학습, 저장·복원·합치기. Gemini 호출 없음."""
import itertools
import json
import random

import pytest

from ai.personalization.bandit import (ARMS, FALLBACK_ARM, SAFE_ARMS, ContextualBandit, allowed_arms, context_key)
from ai.personalization.user_model import MIN_SESSIONS, UserStats


def stats(wake=0.8, n=5):
    return UserStats(n_sessions=n, wake_success_rate=wake)


# ---------------- 전략 후보 ----------------
def test_arms_are_the_planned_three_and_default_comes_first():
    assert list(ARMS) == ["standard", "gentle", "push"]                      # 동점이면 앞쪽이 이기므로 기본 전략이 맨 앞
    fields = {n: (a.coaching_intensity, a.briefing_length, a.encouragement) for n, a in ARMS.items()}
    assert fields == {"standard": ("normal", "normal", "normal"), "gentle": ("soft", "normal", "high"), "push": ("firm", "short", "normal")}
    assert all(a.strategy_id == n for n, a in ARMS.items()) and ARMS["standard"].is_default


# ---------------- 상황 칸 ----------------
def test_default_context_has_four_cells():
    keys = {context_key(stats(w), c) for w, c in itertools.product((0.1, 0.9), ("TIRED", "GOOD"))}
    assert keys == {"low|tired", "low|ok", "ok|tired", "ok|ok"}


def test_eight_cell_plan_is_one_config_line():
    feats = ("wake", "tired", "day")
    keys = {context_key(stats(w), c, d, feats) for w, c, d in itertools.product((0.1, 0.9), ("TIRED", "GOOD"), (0, 6))}
    assert len(keys) == 8 and context_key(stats(0.1), "TIRED", 5, feats) == "low|tired|wkd" and context_key(stats(), None, 0, feats) == "ok|ok|wk"
    assert ContextualBandit(features=feats).context(stats(0.1), "TIRED", 6) == "low|tired|wkd"


@pytest.mark.parametrize("rate,cell", [(0.49, "low"), (0.4999, "low"), (0.5, "ok"), (0.51, "ok")])
def test_wake_boundary(rate, cell):
    assert context_key(stats(rate), None).split("|")[0] == cell


def test_not_enough_data_is_treated_as_ok_not_low():
    assert context_key(stats(0.0, n=MIN_SESSIONS - 1), None) == "ok|ok"
    assert context_key(None, None) == "ok|ok" and context_key(UserStats(n_sessions=5), None) == "ok|ok"


@pytest.mark.parametrize("cond,tired", [("TIRED", True), ("STRESSED", True), ("tired", True), (" stressed ", True), ("GOOD", False), ("NORMAL", False), (None, False), ("", False), ("???", False)])
def test_tired_cell(cond, tired):
    assert context_key(stats(), cond).endswith("tired" if tired else "ok")


def test_old_session_condition_is_not_used_as_today():
    st = UserStats(n_sessions=5, wake_success_rate=0.9, last_condition="TIRED")       # 며칠 전 세션의 컨디션
    assert context_key(st, None) == "ok|ok"


def test_unknown_feature_is_rejected():
    with pytest.raises(ValueError):
        context_key(stats(), None, None, ("wake", "mood"))
    with pytest.raises(ValueError):
        ContextualBandit(features=("nope",))


# ---------------- 안전 규칙: 입력 16가지 ----------------
@pytest.mark.parametrize("wake_low,tired,day,kind", list(itertools.product((True, False), repeat=4)))
def test_all_16_input_combinations(wake_low, tired, day, kind):
    b = ContextualBandit(features=("wake", "tired", "day"))
    d = b.choose(stats(0.2 if wake_low else 0.9), "TIRED" if tired else "GOOD", "KIND" if kind else "FRIENDLY", 6 if day else 1, random.Random(0))
    want = SAFE_ARMS if (tired or kind) else tuple(ARMS)
    assert d.allowed == want and abs(sum(d.probs.values()) - 1) < 1e-9 and d.arm in want
    assert d.context == f"{'low' if wake_low else 'ok'}|{'tired' if tired else 'ok'}|{'wkd' if day else 'wk'}"


def test_allowed_arms_table():
    assert allowed_arms("GOOD", "FRIENDLY") == ("standard", "gentle", "push") and allowed_arms(None, None) == ("standard", "gentle", "push")
    assert allowed_arms("TIRED", "FRIENDLY") == SAFE_ARMS and allowed_arms("GOOD", "KIND") == SAFE_ARMS and allowed_arms("TIRED", "KIND") == SAFE_ARMS
    for style in ("kind", " KIND "):
        assert allowed_arms(None, style) == SAFE_ARMS
    assert allowed_arms(None, "FIRM") == tuple(ARMS)                       # FIRM은 단호함을 원하는 설정이라 push 허용


def test_push_is_never_picked_when_forbidden_even_when_exploring_everything():
    b = ContextualBandit(epsilon=1.0)
    rng = random.Random(1)
    for cond, style in (("TIRED", "FRIENDLY"), ("GOOD", "KIND"), ("STRESSED", "KIND")):
        assert all(b.choose(stats(), cond, style, rng=rng).arm != "push" for _ in range(300))


def test_empty_candidates_fall_back_to_standard_never_push():
    b = ContextualBandit()
    for allowed in ((), [], ("ghost",), ["unknown", "other"]):
        d = b.select("ok|ok", allowed=allowed, rng=random.Random(0))
        assert d.arm == FALLBACK_ARM == "standard" and d.allowed == ("standard",) and d.propensity == 1.0


# ---------------- 선택 확률 ----------------
def test_first_day_picks_standard_with_expected_probability():
    d = ContextualBandit(epsilon=0.15).select("ok|ok", rng=random.Random(0))
    assert d.probs == pytest.approx({"standard": 0.15 / 3 + 0.85, "gentle": 0.05, "push": 0.05})      # 기록이 없으면 기본 전략이 가장 유력
    assert d.values == {"standard": 0.5, "gentle": 0.5, "push": 0.5}


def test_probabilities_sum_to_one_and_propensity_matches_choice():
    b = ContextualBandit(epsilon=0.3)
    b.update("ok|ok", "gentle", 1.0)
    for allowed in (None, SAFE_ARMS):
        for seed in range(50):
            d = b.select("ok|ok", allowed=allowed, rng=random.Random(seed))
            assert sum(d.probs.values()) == pytest.approx(1.0) and d.propensity == d.probs[d.arm] and 0 < d.propensity <= 1


def test_epsilon_zero_is_greedy_and_epsilon_one_is_uniform():
    b = ContextualBandit(epsilon=0.0)
    b.update("ok|ok", "push", 1.0)
    assert {b.select("ok|ok", rng=random.Random(s)).arm for s in range(50)} == {"push"}
    u = ContextualBandit(epsilon=1.0)
    picks = [u.select("ok|ok", rng=random.Random(s)).arm for s in range(900)]
    assert all(250 < picks.count(a) < 350 for a in ARMS)                         # 약 1/3씩
    assert u.select("ok|ok", rng=random.Random(0)).probs == pytest.approx({a: 1 / 3 for a in ARMS})


def test_same_seed_same_result_and_explored_flag():
    b = ContextualBandit(epsilon=0.5)
    b.update("ok|ok", "gentle", 1.0)
    a = [b.select("ok|ok", rng=random.Random(7)) for _ in range(3)]
    assert a[0] == a[1] == a[2]
    seen = {(d.arm, d.explored) for d in (b.select("ok|ok", rng=random.Random(s)) for s in range(200))}
    assert ("gentle", False) in seen and any(e for _, e in seen) and all((arm != "gentle") == e for arm, e in seen)


# ---------------- 학습 ----------------
def test_value_pulls_toward_prior_with_little_data():
    b = ContextualBandit(prior_mean=0.5, prior_weight=2.0)
    assert b.value("c", "gentle") == 0.5
    b.update("c", "gentle", 1.0)
    assert b.value("c", "gentle") == pytest.approx((0.5 * 2 + 1) / 3)
    for _ in range(98):
        b.update("c", "gentle", 1.0)
    assert 0.98 < b.value("c", "gentle") < 1.0


def test_cells_and_arms_learn_independently():
    b = ContextualBandit()
    b.update("low|ok", "push", 1.0)
    assert b.value("low|ok", "push") > 0.5 and b.value("ok|ok", "push") == 0.5 and b.value("low|ok", "gentle") == 0.5


def test_update_rejects_bad_input_and_clamps():
    b = ContextualBandit()
    assert b.update("c", "ghost", 1.0) is False and b.update("c", "gentle", None) is False and b.update("c", "gentle", float("nan")) is False
    assert b.update("c", "gentle", "abc") is False and b.update("c", "gentle", True) is False and b.counts == {}
    assert b.update("c", "gentle", 5) and b.update("c", "gentle", -3) and b.update("c", "gentle", "0.5")
    assert b.sums["c::gentle"] == pytest.approx(1.0 + 0.0 + 0.5) and b.counts["c::gentle"] == 3


def simulate(best, days=300, seed=3):
    """best 전략만 평균 0.85점, 나머지는 0.35점을 받는 가상 사용자."""
    rng = random.Random(seed)
    b = ContextualBandit()
    picks = []
    for _ in range(days):
        d = b.choose(stats(0.3), "GOOD", "FRIENDLY", rng=rng)
        b.update(d.context, d.arm, min(1, max(0, rng.gauss(0.85 if d.arm == best else 0.35, 0.1))))
        picks.append(d.arm)
    return picks


@pytest.mark.parametrize("best", ["gentle", "push"])
def test_bandit_learns_the_better_strategy_for_a_simulated_user(best):
    picks = simulate(best)
    assert picks[:1] == ["standard"]                                          # 처음에는 기본 전략
    assert picks[-100:].count(best) > 70                                       # 시간이 지나면 잘 맞는 전략을 많이 고른다 (동작 확인용, 효과의 증거 아님)


def test_tired_days_never_learn_to_push_even_if_push_scores_best():
    rng = random.Random(0)
    b = ContextualBandit()
    for _ in range(200):
        d = b.choose(stats(0.3), "TIRED", "FRIENDLY", rng=rng)
        b.update(d.context, d.arm, 0.9 if d.arm == "push" else 0.3)
        assert d.arm != "push"


# ---------------- 저장·복원·합치기 ----------------
def test_save_and_restore_gives_identical_choices():
    b = ContextualBandit(epsilon=0.2)
    for arm, r in (("gentle", 1.0), ("push", 0.2), ("gentle", 0.8)):
        b.update("low|ok", arm, r)
    data = json.loads(json.dumps(b.to_dict()))                                # JSON으로 저장했다 읽어도 같다
    c = ContextualBandit.from_dict(data)
    assert c.to_dict() == b.to_dict() and c.epsilon == 0.2 and c.features == ("wake", "tired")
    for s in range(20):
        assert c.select("low|ok", rng=random.Random(s)) == b.select("low|ok", rng=random.Random(s))


@pytest.mark.parametrize("bad", [None, {}, "text", 5, [], {"features": ["nope"]}, {"counts": "x", "sums": 3}, {"epsilon": "x"}])
def test_from_dict_never_crashes(bad):
    b = ContextualBandit.from_dict(bad)
    assert isinstance(b, ContextualBandit) and b.select("ok|ok", rng=random.Random(0)).arm in ARMS


def test_from_dict_skips_corrupt_entries():
    b = ContextualBandit.from_dict({"counts": {"a::gentle": 2, "b::gentle": -1, "c::gentle": "x", "d::gentle": True},
                                    "sums": {"a::gentle": 1.5, "b::gentle": float("nan"), "c::gentle": -2}})
    assert b.counts == {"a::gentle": 2} and b.sums == {"a::gentle": 1.5}


def test_merge_sums_users_and_keeps_originals():
    a, b = ContextualBandit(), ContextualBandit()
    a.update("ok|ok", "gentle", 1.0), a.update("ok|ok", "gentle", 0.5)
    b.update("ok|ok", "gentle", 0.0), b.update("low|ok", "push", 1.0)
    m = ContextualBandit.merge(a, b)
    assert m.counts == {"ok|ok::gentle": 3, "low|ok::push": 1} and m.sums == {"ok|ok::gentle": pytest.approx(1.5), "low|ok::push": 1.0}
    assert m.value("ok|ok", "gentle") == pytest.approx((1.0 + 1.5) / (2 + 3))
    assert a.counts == {"ok|ok::gentle": 2} and b.counts == {"ok|ok::gentle": 1, "low|ok::push": 1}      # 원본은 그대로
    assert ContextualBandit.merge().counts == {}


def test_merge_requires_same_features():
    with pytest.raises(ValueError):
        ContextualBandit.merge(ContextualBandit(), ContextualBandit(features=("wake", "tired", "day")))
