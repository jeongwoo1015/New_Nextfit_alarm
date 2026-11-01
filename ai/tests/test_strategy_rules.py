"""Rule과 전략 테스트. 입력 28가지 전수 검사 + 경계값 + 전략 문장. Gemini 호출 없음."""
import itertools

import pytest

from ai.personalization.rules import RuleConfig, decide, is_tired
from ai.personalization.strategy import (DEFAULT_STRATEGY, ENCOURAGEMENTS, INTENSITIES, Strategy, coerce, strategy_block)
from ai.personalization.user_model import MIN_SESSIONS, UserStats


def stats(n=5, wake=0.8, skip=0.2):
    return UserStats(n_sessions=n, wake_success_rate=wake, skip_ratio=skip)


def fields(d):
    s = d.strategy
    return s.coaching_intensity, s.briefing_length, s.encouragement


# ---------------- 28가지 전수 검사 ----------------
def expected(enough, wake_low, skip, tired, kind):
    """손으로 정리한 기대값(규칙표를 그대로 옮김). skip: 'high' / 'low' / None"""
    ids, intensity, briefing, cheer = [], "normal", "normal", "normal"
    if not enough:
        ids.append("R0")
    else:
        if wake_low:
            intensity = "firm"; ids.append("R1")
        if skip == "high":
            briefing = "short"; ids.append("R3")
    if tired:
        intensity, cheer = "soft", "high"; ids.append("R4")
    if intensity == "firm" and kind:
        intensity = "normal"; ids.append("U1")
    if enough and not ids:
        ids.append("R-")
    return (intensity, briefing, cheer), tuple(ids)


def build_cases():
    cases = []
    for tired, kind in itertools.product((False, True), repeat=2):                        # 기록 부족: 4가지
        cases.append((False, False, None, tired, kind))
    for wake_low, skip, tired, kind in itertools.product((True, False), ("high", "low", None), (False, True), (False, True)):   # 기록 충분: 24가지
        cases.append((True, wake_low, skip, tired, kind))
    return cases


CASES = build_cases()


def run_case(enough, wake_low, skip, tired, kind):
    st = stats(n=5 if enough else MIN_SESSIONS - 1, wake=0.4 if wake_low else 0.8,
               skip={"high": 0.6, "low": 0.2, None: None}[skip])
    return decide(st, condition="TIRED" if tired else "GOOD", speech_style="KIND" if kind else "FRIENDLY")


def test_there_are_28_cases():
    assert len(CASES) == 28 and len(set(CASES)) == 28


@pytest.mark.parametrize("case", CASES)
def test_every_input_combination(case):
    d = run_case(*case)
    want_fields, want_ids = expected(*case)
    assert fields(d) == want_fields and d.rule_ids == want_ids


def test_outputs_are_6_strategies_and_12_rule_id_patterns():
    results = [run_case(*c) for c in CASES]
    assert len({fields(r) for r in results}) == 6
    assert len({r.rule_ids for r in results}) == 12


# ---------------- 경계값 ----------------
@pytest.mark.parametrize("rate,firm", [(0.49, True), (0.4999, True), (0.5, False), (0.51, False), (0.0, True), (1.0, False)])
def test_wake_rate_boundary(rate, firm):
    d = decide(stats(wake=rate, skip=0.0))
    assert (d.strategy.coaching_intensity == "firm") is firm and ("R1" in d.rule_ids) is firm


@pytest.mark.parametrize("ratio,short", [(0.49, False), (0.5, True), (0.51, True), (0.0, False), (1.0, True)])
def test_skip_ratio_boundary(ratio, short):
    d = decide(stats(skip=ratio))
    assert (d.strategy.briefing_length == "short") is short and ("R3" in d.rule_ids) is short


@pytest.mark.parametrize("n,enough", [(0, False), (MIN_SESSIONS - 1, False), (MIN_SESSIONS, True), (10, True)])
def test_min_sessions_boundary(n, enough):
    d = decide(stats(n=n, wake=0.1, skip=0.9))
    assert ("R0" in d.rule_ids) is (not enough)
    assert (d.strategy.coaching_intensity == "firm") is enough            # 기록이 부족하면 통계 규칙은 건너뜀


def test_custom_thresholds():
    cfg = RuleConfig(wake_low=0.8, skip_high=0.9)
    assert decide(stats(wake=0.7, skip=0.85), config=cfg).rule_ids == ("R1",)
    assert decide(stats(wake=0.8, skip=0.9), config=cfg).rule_ids == ("R3",)


# ---------------- 컨디션 ----------------
@pytest.mark.parametrize("cond,tired", [("TIRED", True), ("STRESSED", True), ("tired", True), (" Stressed ", True),
                                         ("GOOD", False), ("NORMAL", False), (None, False), ("", False), ("weird", False)])
def test_tired_codes(cond, tired):
    assert is_tired(cond) is tired
    assert (decide(stats(), condition=cond).strategy.coaching_intensity == "soft") is tired


def test_tired_beats_firm_and_new_user_can_be_tired():
    d = decide(stats(wake=0.1), condition="TIRED")
    assert fields(d) == ("soft", "normal", "high") and d.rule_ids == ("R1", "R4")
    new = decide(stats(n=0), condition="STRESSED")                        # 기록이 없어도 어젯밤 컨디션은 적용
    assert fields(new) == ("soft", "normal", "high") and new.rule_ids == ("R0", "R4")


def test_old_session_condition_is_not_used_for_today():
    # 옛 코드는 오늘 컨디션이 없으면 최근 세션의 컨디션을 대신 썼다. 이제는 오늘 값만 쓴다.
    st = UserStats(n_sessions=5, wake_success_rate=0.9, skip_ratio=0.0, last_condition="TIRED")
    d = decide(st, condition=None)
    assert fields(d) == ("normal", "normal", "normal") and d.rule_ids == ("R-",)


# ---------------- 사용자 말투 설정 ----------------
@pytest.mark.parametrize("style,capped", [("KIND", True), ("kind", True), (" KIND ", True), ("FRIENDLY", False), ("FIRM", False), (None, False), ("???", False)])
def test_user_speech_style_caps_firm(style, capped):
    d = decide(stats(wake=0.1), speech_style=style)
    assert d.strategy.coaching_intensity == ("normal" if capped else "firm")
    assert ("U1" in d.rule_ids) is capped and "R1" in d.rule_ids


def test_soft_is_always_allowed_and_u1_not_added_when_not_firm():
    d = decide(stats(wake=0.1), condition="TIRED", speech_style="KIND")
    assert d.strategy.coaching_intensity == "soft" and d.rule_ids == ("R1", "R4")


# ---------------- 기타 입력 ----------------
def test_none_stats_and_none_values():
    d = decide(None)
    assert d.rule_ids == ("R0",) and d.strategy.is_default
    assert decide(UserStats(n_sessions=5)).rule_ids == ("R-",)            # 성공률·건너뜀 값이 없으면 해당 규칙 건너뜀


def test_decision_is_deterministic_and_uses_rule_id():
    a, b = decide(stats(wake=0.3, skip=0.7), "TIRED", "KIND"), decide(stats(wake=0.3, skip=0.7), "TIRED", "KIND")
    assert a == b and a.strategy.strategy_id == "rule"


# ---------------- 전략 값과 지시문 블록 ----------------
def test_strategy_defaults_and_invalid_values():
    assert DEFAULT_STRATEGY.is_default and not hasattr(DEFAULT_STRATEGY, "item_minutes")
    s = Strategy(coaching_intensity="angry", briefing_length="long", encouragement="max", strategy_id="a b/c")
    assert (s.coaching_intensity, s.briefing_length, s.encouragement) == ("normal", "normal", "normal") and s.strategy_id == "a_b_c"
    assert Strategy(strategy_id=None).strategy_id == "default" and set(INTENSITIES) == {"soft", "normal", "firm"} and "high" in ENCOURAGEMENTS


def test_strategy_dict_roundtrip_and_coerce():
    s = Strategy("firm", "short", "low", "x")
    assert Strategy.from_dict(s.to_dict()) == s
    for bad in (None, {}, "text", 5, {"coaching_intensity": 3}):
        assert Strategy.from_dict(bad).is_default
    assert coerce(None) is None and coerce(s) is s and coerce(s.to_dict()) == s and coerce(5) is None

    class Other:                                                           # 다른 경로로 불러온 같은 이름의 클래스도 읽는다
        def to_dict(self):
            return s.to_dict()
    assert coerce(Other()) == s


def test_default_strategy_adds_nothing_to_prompt():
    assert strategy_block(None) == "" and strategy_block(DEFAULT_STRATEGY) == "" and strategy_block({}) == ""
    assert strategy_block(Strategy(), "coaching") == ""


def test_blocks_per_value_and_stage():
    soft = strategy_block(Strategy("soft", "normal", "high"), "coaching")
    assert "부드럽게" in soft and "응원 정도: 많이" in soft and soft.startswith("[오늘의 전략]")
    assert "단호하게" in strategy_block(Strategy("firm"), "coaching") and "비난하거나 다그치는 표현은 쓰지 않는다" in strategy_block(Strategy("firm"))
    assert "2~3문장" in strategy_block(Strategy(briefing_length="short"), "briefing")
    assert strategy_block(Strategy(briefing_length="short"), "coaching") == ""        # 코칭에는 브리핑 길이를 붙이지 않는다
    assert strategy_block(Strategy(coaching_intensity="firm"), "briefing") == ""      # 브리핑에는 코칭 강도를 붙이지 않는다
    assert "응원 정도: 적게" in strategy_block({"encouragement": "low"}, "briefing")


def test_block_always_keeps_user_style_and_safety_above_strategy():
    for s in (Strategy("soft"), Strategy("firm"), Strategy(briefing_length="short"), Strategy(encouragement="high")):
        assert "사용자가 고른 어조·말투와 안전 규칙" in strategy_block(s)
