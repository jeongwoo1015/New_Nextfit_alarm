"""Agent·로그 테스트. 모드별 동작, 안전 규칙, 오류 복귀, 로그, 학습. Gemini 호출 없음. (옛 코칭 시뮬레이션 sim.py는 11/26 삭제, coach_sim.py로 대체)"""
import json
import random
import re
from datetime import date, timedelta

import pytest

from ai.live.version import PROMPT_VERSION
from ai.personalization import rules
from ai.personalization.agent import MODES, ab_group, decide, learn
from ai.personalization.bandit import ARMS, ContextualBandit
from ai.personalization.logs import DecisionLog
from ai.personalization.records import DONE, SKIPPED, SessionRecord, StepRecord
from ai.personalization.strategy import DEFAULT_STRATEGY, Strategy
from ai.personalization.user_model import compute_stats

TODAY = date(2026, 11, 10)


def history(wake_ok=True, skip=False, n=5):
    steps = [StepRecord(step_name="a", status=SKIPPED if skip else DONE, step_order=1, template_item_id="t1")]
    return [SessionRecord(session_date=TODAY - timedelta(days=i + 1), wake_success=wake_ok, steps=steps) for i in range(n)]


# ---------------- 버전 ----------------
def test_prompt_version_format():
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}\.\d+", PROMPT_VERSION)


# ---------------- 모드 ----------------
def test_off_and_unknown_modes_use_default_strategy():
    for mode in ("off", "weird", None, ""):
        d = decide("u1", history(False), TODAY, "TIRED", mode=mode)
        assert d.strategy == DEFAULT_STRATEGY and d.log.source == "default" and d.briefing_block == "" and d.coaching_block == ""
    assert decide("u1", history(), TODAY, mode="off").log.error is None
    assert "모르는 mode" in decide("u1", history(), TODAY, mode="weird").log.error
    assert MODES == ("off", "rule", "bandit", "ab")


def test_rule_mode_matches_rules_decide_and_logs_rule_ids():
    sessions = history(wake_ok=False)
    d = decide("u1", sessions, TODAY, condition="GOOD", speech_style="FRIENDLY", mode="rule")
    want = rules.decide(compute_stats(sessions, TODAY), "GOOD", "FRIENDLY")
    assert d.strategy == want.strategy and d.log.rule_ids == list(want.rule_ids) == ["R1"] and d.log.source == "rule"
    assert d.log.context_key is None and d.log.propensity is None and d.log.stats["n_sessions"] == 5


def test_bandit_mode_records_context_probability_and_candidates():
    b = ContextualBandit(policy="epsilon")
    d = decide("u1", history(wake_ok=False), TODAY, condition="TIRED", mode="bandit", bandit=b, rng=random.Random(0))
    lg = d.log
    assert lg.source == "bandit" and lg.context_key == "tired" and lg.allowed_arms == ["standard", "gentle"]
    assert 0 < lg.propensity <= 1 and lg.strategy_id in ARMS and d.strategy == ARMS[lg.strategy_id] and lg.explored in (True, False)


def test_bandit_mode_without_bandit_falls_back_to_rule():
    d = decide("u1", history(False), TODAY, mode="bandit", bandit=None)
    assert d.log.source == "rule" and d.log.rule_ids == ["R1"] and d.log.error is None


# ---------------- A/B ----------------
def test_ab_assignment_is_deterministic_and_balanced():
    assert all(ab_group("u1", TODAY) == ab_group("u1", TODAY) for _ in range(5))
    groups = [ab_group(f"user{i}", TODAY + timedelta(days=j)) for i in range(20) for j in range(20)]
    assert set(groups) == {"rule", "bandit"} and 150 < groups.count("bandit") < 250                     # 400개 중 약 절반


def test_ab_mode_runs_the_assigned_side_and_logs_probability():
    b = ContextualBandit(policy="epsilon")
    seen = set()
    for j in range(40):
        day = TODAY + timedelta(days=j)
        d = decide("u1", history(), day, mode="ab", bandit=b, rng=random.Random(j))
        assert d.log.ab_group == ab_group("u1", day) == d.log.source and d.log.ab_propensity == 0.5
        seen.add(d.log.source)
    assert seen == {"rule", "bandit"}


def test_ab_without_bandit_is_not_randomized():
    d = decide("u1", history(), TODAY, mode="ab", bandit=None)
    assert d.log.ab_group == "rule" and d.log.ab_propensity == 1.0 and d.log.source == "rule"


# ---------------- 안전 규칙 ----------------
@pytest.mark.parametrize("mode", ["rule", "bandit", "ab"])
@pytest.mark.parametrize("cond,style", [("TIRED", "FRIENDLY"), ("GOOD", "KIND"), ("STRESSED", "KIND")])
def test_push_never_appears_when_forbidden(mode, cond, style):
    b = ContextualBandit(policy="epsilon", epsilon=1.0)
    for j in range(60):
        d = decide(f"u{j}", history(wake_ok=False, skip=True), TODAY + timedelta(days=j), cond, style, mode=mode, bandit=b, rng=random.Random(j))
        assert d.strategy.strategy_id != "push" and d.strategy.coaching_intensity != "firm"


# ---------------- 오류 복귀 ----------------
class Boom:
    def choose(self, *a, **k):
        raise RuntimeError("bandit 고장")

    arms = {}


@pytest.mark.parametrize("sessions", [[object()], [None], "not a list", [{"session_date": "x"}]])
def test_broken_history_falls_back_to_default(sessions):
    d = decide("u1", sessions, TODAY, mode="rule")
    assert d.strategy == DEFAULT_STRATEGY and d.log.source == "fallback" and d.log.error and d.briefing_block == "" and d.coaching_block == ""
    assert d.log.rule_ids == [] and d.log.propensity is None and d.log.prompt_version == PROMPT_VERSION


def test_broken_bandit_falls_back_and_never_raises():
    d = decide("u1", history(), TODAY, mode="bandit", bandit=Boom())
    assert d.log.source == "fallback" and "bandit 고장" in d.log.error and d.strategy == DEFAULT_STRATEGY
    assert decide("u1", history(), TODAY, mode="rule", rule_config=None).log.source == "fallback"        # 설정이 망가져도 대화는 이어진다


# ---------------- 지시문 블록 ----------------
def test_blocks_follow_strategy_and_stage():
    tired = decide("u1", history(), TODAY, "TIRED", mode="rule")
    assert "부드럽게" in tired.coaching_block and "응원 정도: 많이" in tired.briefing_block and "코칭 강도" not in tired.briefing_block
    short = decide("u1", history(skip=True), TODAY, mode="rule")                    # 건너뜀 비율 높음 → 브리핑 짧게
    assert "2~3문장" in short.briefing_block and short.coaching_block == ""
    plain = decide("u1", history(), TODAY, mode="rule")
    assert plain.strategy.is_default and plain.briefing_block == plain.coaching_block == ""


# ---------------- 로그 ----------------
def test_log_row_json_roundtrip_and_fields(tmp_path):
    d = decide("u1", history(False), TODAY, "TIRED", "KIND", mode="bandit", bandit=ContextualBandit(policy="epsilon"), rng=random.Random(1))
    row = d.log.to_row()
    assert isinstance(row["strategy"], str) and isinstance(row["stats"], str) and isinstance(row["allowed_arms"], str)
    back = DecisionLog.from_row(json.loads(json.dumps(row)))
    assert back == d.log
    path = tmp_path / "log.jsonl"
    d.log.append_jsonl(str(path)), d.log.append_jsonl(str(path))
    lines = path.read_text(encoding="utf-8").strip().split("\n")
    assert len(lines) == 2 and json.loads(lines[0])["propensity"] == d.log.propensity
    for key in ("user_id", "session_date", "source", "strategy_id", "mode", "context_key", "propensity", "allowed_arms", "prompt_version", "created_at"):
        assert key in row
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T.*", row["created_at"]) and row["session_date"] == "2026-11-10"


def test_from_row_tolerates_garbage():
    assert DecisionLog.from_row({"user_id": "u", "session_date": "d", "source": "rule", "strategy_id": "x", "strategy": "{broken", "extra": 1}).strategy == {}
    assert DecisionLog.from_row({"user_id": "u", "session_date": "d", "source": "rule", "strategy_id": "x", "strategy": {"a": 1}}).strategy == {"a": 1}


# ---------------- 학습 ----------------
def test_learn_only_for_bandit_choices():
    b = ContextualBandit(policy="epsilon")
    d = decide("u1", history(), TODAY, mode="bandit", bandit=b, rng=random.Random(0))
    assert learn(b, d.log, 1.0) is True and b.counts == {f"{d.log.context_key}::{d.log.strategy_id}": 1}
    rule_log = decide("u1", history(), TODAY, mode="rule").log
    fb = decide("u1", [object()], TODAY, mode="bandit", bandit=b).log
    off = decide("u1", history(), TODAY, mode="off").log
    before = dict(b.counts)
    assert not any(learn(b, lg, 1.0) for lg in (rule_log, fb, off)) and b.counts == before


def test_learn_rejects_bad_scores_and_unknown_strategy():
    b = ContextualBandit(policy="epsilon")
    d = decide("u1", history(), TODAY, mode="bandit", bandit=b, rng=random.Random(0))
    assert learn(b, d.log, float("nan")) is False and learn(b, d.log, None) is False and b.counts == {}
    d.log.strategy_id = "ghost"
    assert learn(b, d.log, 1.0) is False
