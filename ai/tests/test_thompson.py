"""11/21: ContextualBandit의 Thompson Sampling 정책(기본값). 점수판 형식(counts/sums)과 공개 API는 그대로다."""
import random

import pytest

from ai.personalization.bandit import ARMS, ContextualBandit, POLICIES


def test_default_policy_is_thompson_and_state_round_trips():
    b = ContextualBandit()
    assert b.policy == "thompson" and POLICIES == ("thompson", "epsilon")
    b.update("low|ok", "gentle", 0.8)
    d = b.to_dict()
    assert d["policy"] == "thompson" and d["version"] == 2
    c = ContextualBandit.from_dict(d)
    assert c.policy == "thompson" and c.to_dict() == d


def test_old_state_without_policy_loads_as_thompson_and_bad_policy_is_rejected():
    old = {"version": 1, "features": ["wake", "tired"], "epsilon": 0.15, "prior_mean": 0.5, "prior_weight": 2.0, "counts": {"a::standard": 2}, "sums": {"a::standard": 1.0}}
    assert ContextualBandit.from_dict(old).policy == "thompson"
    assert ContextualBandit.from_dict({**old, "policy": "bogus"}).policy == "thompson"
    with pytest.raises(ValueError):
        ContextualBandit(policy="bogus")


def test_beta_params_start_at_the_prior_and_follow_the_records():
    b = ContextualBandit()
    assert b.beta_params("c", "push") == (1.0, 1.0)                    # 평균 0.5, 강도 2 = Beta(1, 1)
    for r in (1.0, 1.0, 0.0, 0.5):
        b.update("c", "push", r)
    assert b.beta_params("c", "push") == pytest.approx((1.0 + 2.5, 1.0 + 1.5))      # 점수 합 2.5가 성공, 나머지 1.5가 실패


def test_select_is_valid_deterministic_for_a_seed_and_respects_allowed_arms():
    b = ContextualBandit()
    d = b.select("c", rng=random.Random(3))
    assert d.arm in ARMS and set(d.probs) == set(ARMS) and sum(d.probs.values()) == pytest.approx(1.0) and 0 < d.propensity <= 1
    assert d.arm == b.select("c", rng=random.Random(3)).arm and d.probs == b.select("c", rng=random.Random(99)).probs      # 확률 추정은 상태만 보고 정해진다
    safe = b.select("c", allowed=("standard", "gentle"), rng=random.Random(1))
    assert safe.arm in ("standard", "gentle") and set(safe.allowed) == {"standard", "gentle"}
    assert b.select("c", allowed=("nope",), rng=random.Random(1)).arm == "standard"       # 후보가 비면 기본 전략


def test_it_learns_the_better_arm_and_explores_less_over_time():
    truth = {"standard": 0.4, "gentle": 0.3, "push": 0.85}
    env, pick = random.Random(0), random.Random(1)
    b = ContextualBandit(mc_samples=0)
    chosen = []
    for _ in range(160):
        d = b.select("c", rng=pick)
        b.update("c", d.arm, 1.0 if env.random() < truth[d.arm] else 0.0)
        chosen.append(d.arm)
    assert chosen[-60:].count("push") >= 45                           # 뒤로 갈수록 잘 먹히는 전략으로 쏠린다
    assert b.value("c", "push") > b.value("c", "standard")


def test_few_records_keep_exploring_and_many_records_settle():
    young, old = ContextualBandit(), ContextualBandit()
    for _ in range(300):
        old.update("c", "push", 1.0)
        old.update("c", "standard", 0.0)
        old.update("c", "gentle", 0.0)
    young_arms = {young.select("c", rng=random.Random(s)).arm for s in range(60)}
    old_arms = {old.select("c", rng=random.Random(s)).arm for s in range(60)}
    assert len(young_arms) >= 2 and old_arms == {"push"}


def test_merge_and_epsilon_policy_still_work():
    a, b = ContextualBandit(policy="epsilon"), ContextualBandit(policy="epsilon")
    a.update("c", "push", 1.0)
    b.update("c", "push", 0.0)
    m = ContextualBandit.merge(a, b)
    assert m.policy == "epsilon" and m.counts["c::push"] == 2 and m.sums["c::push"] == 1.0
    assert ContextualBandit.merge(ContextualBandit(), ContextualBandit()).policy == "thompson"
    assert ContextualBandit(policy="epsilon").select("c", rng=random.Random(0)).probs["standard"] == pytest.approx(0.15 / 3 + 0.85)
