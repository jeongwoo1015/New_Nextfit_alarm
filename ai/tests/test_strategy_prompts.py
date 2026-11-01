"""개인화 전략 블록이 브리핑·코칭 프롬프트에 올바르게 붙는지 확인한다 (네트워크 없음)."""
import pytest

from ai.devclient.rules import HARD, check
from ai.devclient.scenarios import SCENARIOS
from ai.live.config import build_setup
from ai.live.inputs import BriefingInput, CoachingInput, Persona, Step
from ai.live.prompts.briefing import LENGTH_NORMAL, LENGTH_SHORT, briefing_prompt
from ai.live.prompts.coaching import coaching_prompt
from ai.personalization.agent import decide
from ai.personalization.strategy import DEFAULT_STRATEGY, Strategy, strategy_block
from ai.tests.test_rules import BRIEF, WAKE_Q, T, START, briefing_log, failed, log, scn

STEPS = (Step(1, "물 한 잔", 60, "QA"), Step(2, "스트레칭", 120, "PLAY", "1. 목을 돌려요."))
SHORT = strategy_block(Strategy(briefing_length="short"), "briefing")
SOFT = strategy_block(Strategy(coaching_intensity="soft"), "coaching")
FIRM = strategy_block(Strategy(coaching_intensity="firm"), "coaching")


def test_default_strategy_leaves_prompts_unchanged():
    assert strategy_block(DEFAULT_STRATEGY, "briefing") == "" and strategy_block(None, "coaching") == ""
    b, c = briefing_prompt(BriefingInput(now_hhmm="07:02")), coaching_prompt(CoachingInput(steps=STEPS))
    for p in (b, c):
        assert "[오늘의 전략]" not in p and not p.endswith("\n")
    assert "4~6문장으로 말하고 그 안에서는 질문하지 않는다" in b and LENGTH_SHORT not in b
    assert b == briefing_prompt(BriefingInput(now_hhmm="07:02", strategy_block=""))


def test_cheer_block_is_appended_last_and_rest_is_unchanged():
    blk = strategy_block(Strategy(encouragement="high"), "briefing")
    base = briefing_prompt(BriefingInput(now_hhmm="07:02"))
    p = briefing_prompt(BriefingInput(now_hhmm="07:02", strategy_block=blk))
    assert p == base + "\n\n" + blk and "4~6문장으로 말하고" in p


def test_short_strategy_replaces_the_length_rule_instead_of_stacking_two():
    base = briefing_prompt(BriefingInput(now_hhmm="07:02"))
    p = briefing_prompt(BriefingInput(now_hhmm="07:02", strategy_block=SHORT))
    assert p.endswith(SHORT) and p.count("[오늘의 전략]") == 1
    assert "4~6문장" not in p and LENGTH_SHORT in p and "2~3문장" in p                   # 4~6문장 규칙이 남아 AI가 긴 쪽을 따르지 않게 한다
    assert p.replace(LENGTH_SHORT, LENGTH_NORMAL).startswith(base)                       # 나머지 규칙은 그대로
    assert "그 안에서는 질문하지 않는다" in p and "준비 확인" in p


def test_short_with_no_coaching_day_keeps_closing_rule():
    p = briefing_prompt(BriefingInput(coaching_min=0, strategy_block=SHORT))
    assert LENGTH_SHORT in p and "코칭을 하지 않는 날" in p


@pytest.mark.parametrize("blk", [SOFT, FIRM])
def test_coaching_block_is_appended_last_and_rest_is_unchanged(blk):
    base = coaching_prompt(CoachingInput(steps=STEPS))
    p = coaching_prompt(CoachingInput(steps=STEPS, strategy_block=blk))
    assert p.startswith(base) and p.endswith(blk) and p.count("[오늘의 전략]") == 1
    assert "안전 규칙" in blk and "어조" in blk              # 블록 끝에 어조·안전 규칙 우선 문구가 있다


def test_content_session_also_gets_block():
    p = coaching_prompt(CoachingInput(morning_call_type="CONTENT", steps=STEPS, strategy_block=SOFT))
    assert p.endswith(SOFT)


def test_briefing_block_has_no_coaching_line_and_coaching_block_no_length_line():
    s = Strategy("firm", "short", "high")
    assert "코칭 강도" not in strategy_block(s, "briefing") and "브리핑 길이" in strategy_block(s, "briefing")
    assert "브리핑 길이" not in strategy_block(s, "coaching") and "코칭 강도" in strategy_block(s, "coaching")


def test_setup_carries_block_into_system_instruction():
    setup = build_setup("briefing", BriefingInput(strategy_block=SHORT))
    assert "브리핑 길이: 짧게" in str(setup) and "4~6문장" not in str(setup)


def test_agent_blocks_plug_into_inputs():
    d = decide("u1", [], __import__("datetime").date(2026, 11, 10), "TIRED", mode="rule")
    assert "부드럽게" in d.coaching_block
    assert "부드럽게" in coaching_prompt(CoachingInput(steps=STEPS, strategy_block=d.coaching_block))
    assert "응원 정도: 많이" in briefing_prompt(BriefingInput(strategy_block=d.briefing_block))


def test_strategy_scenarios_exist_and_only_add_the_block():
    for name, base in (("briefing_short", "briefing_full"), ("briefing_cheer", "briefing_full"),
                       ("coaching_soft", "coaching_routine"), ("coaching_firm", "coaching_routine")):
        s, b = SCENARIOS[name], SCENARIOS[base]
        assert s.inp.strategy_block.startswith("[오늘의 전략]") and b.inp.strategy_block == ""
        assert s.utterances == b.utterances and s.kind == b.kind
    assert SCENARIOS["briefing_short"].expect["short_briefing"] is True and "short_briefing" not in SCENARIOS["briefing_full"].expect


# ---------- 규칙 검사기: 짧은 브리핑 ----------
SHORT_BRIEF = "지금 7시 2분이에요. 오늘은 비가 오니 우산을 챙기세요. 이제 아침 루틴을 시작할 준비가 됐어요?"


def test_short_briefing_range_is_applied_only_when_flagged():
    flagged = scn("briefing", coaching_min=20, short_briefing=True)
    normal = scn("briefing", coaching_min=20)
    assert not failed(check(briefing_log(SHORT_BRIEF), flagged))                       # 3문장: 짧은 날엔 통과
    assert "브리핑: 4~7문장(마지막은 준비 확인 질문)" in failed(check(briefing_log(SHORT_BRIEF), normal))   # 기본 날엔 그대로 실패
    assert not failed(check(briefing_log(BRIEF), normal))
    assert "브리핑: 2~4문장(마지막은 준비 확인 질문)" in failed(check(briefing_log(BRIEF + " 힘내요. 화이팅이에요."), flagged))   # 짧게인데 길면 실패


def test_short_briefing_without_coaching():
    flagged = scn("briefing", coaching_min=0, short_briefing=True)
    ok = "지금 7시 2분이에요. 오늘은 비가 오니 우산을 챙기세요. 좋은 하루 보내세요."
    r = check(log("briefing", [T(WAKE_Q), T(ok, heard="응 일어났어요")]), flagged)
    assert not any("문장" in x for x in failed(r))
    r = check(log("briefing", [T(WAKE_Q), T(ok + " 힘내요. 잘될 거예요. 파이팅이에요.", heard="응 일어났어요")]), flagged)
    assert "브리핑: 코칭 0분이면 2~3문장, 질문 없음" in failed(r)
