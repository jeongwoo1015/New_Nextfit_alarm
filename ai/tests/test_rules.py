"""규칙 검사·시나리오 정의 테스트 (네트워크 없음: 가짜 대화 기록으로 검사기가 옳게 판정하는지 확인한다)."""
import pytest

from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import HARD, SOFT, check, sentences, summarize
from ai.devclient.scenarios import SCENARIOS, Scenario
from ai.live.config import build_setup
from ai.live.inputs import BriefingInput, NightInput
from ai.tasks.night_record import NightRecordResult


def log(kind, turns, ended_by="turnComplete"):
    return SessionLog(kind=kind, turns=turns, ended_by=ended_by)


def T(said="", calls=(), heard="", fa=1.0):
    return TurnLog(label="x", heard=heard, said=said, calls=list(calls), first_audio=fa)


def failed(results, sev=HARD):
    return {r.rule for r in results if not r.passed and r.severity == sev}


def scn(kind, tone="FORMAL", **expect):
    return Scenario("t", kind, None, tone=tone, expect=expect)


END = {"name": "end_session", "args": {}}


def rep(step, verdict):
    return {"name": "report_step_result", "args": {"step_order": step, "verdict": verdict}}


def test_sentences_split_without_spaces():
    assert sentences("좋은 아침이에요.오늘은 비가 와요.우산 챙기세요!") == ["좋은 아침이에요.", "오늘은 비가 와요.", "우산 챙기세요!"]
    assert sentences("음...") == ["음..."] or len(sentences("음...")) <= 1
    assert sentences("") == []


# ---------- 브리핑 ----------
GOOD_BRIEF = "예준 님 좋은 아침이에요. 오늘은 비가 오니 우산을 챙기세요. 알람은 7시로 맞춰져 있고 오전 9시 반에 회의가 있어요. 어젯밤 정리한 목표를 떠올려 봐요. 이제 아침 루틴을 시작해 봐요."


def test_briefing_passes():
    r = check(log("briefing", [T(GOOD_BRIEF, [END])], "end_session"), scn("briefing", weather_words=["우산"], event_words=["회의"], wake_words=["7시"]))
    assert not failed(r) and not failed(r, SOFT)


def test_briefing_failures():
    bad = "좋은 아침이에요. 날씨는 어때요? 오늘 하루 힘내요."
    r = check(log("briefing", [T(bad)]), scn("briefing"))
    assert {"브리핑: 물음표 없음", "브리핑: 4~6문장", "브리핑: end_session 호출"} <= failed(r)


def test_briefing_zero_coaching_must_not_invite_start():
    text = "좋은 아침이에요. 비가 와요. 알람은 7시예요. 회의가 있어요. 이제 아침 루틴을 시작해 봐요."
    r = check(log("briefing", [T(text, [END])], "end_session"), scn("briefing", coaching_min=0))
    assert "브리핑: 코칭 0분이면 시작 권유 없음" in failed(r)
    ok = "좋은 아침이야. 비가 와. 알람은 7시야. 회의가 있어. 오늘 하루 힘차게 보내길 바랄게."
    assert "브리핑: 코칭 0분이면 시작 권유 없음" not in failed(check(log("briefing", [T(ok, [END])], "end_session"), scn("briefing", "CASUAL", coaching_min=0)))


def test_common_rules():
    r = check(log("briefing", [T("좋은 아침이에요. [END]", [END])], "end_session"), scn("briefing"))
    assert "[END] 글자 없음" in failed(r)
    r = check(log("briefing", [T("**좋은 아침**이에요.", [END])], "end_session"), scn("briefing"))
    assert "마크다운 없음" in failed(r)
    r = check(log("briefing", [T("좋은 아침이에요. This is the end of the session. Goodbye!", [END])], "end_session"), scn("briefing"))
    assert "영어 덧붙임 없음" in failed(r)
    r = check(log("briefing", [T("좋은 아침이에요.", [{"name": "report_step_result", "args": {"step_order": 1, "verdict": "great"}}])]), scn("briefing"))
    assert "함수 호출 형식" in failed(r)
    r = check(log("briefing", [T("좋은 아침이에요.", fa=5.2)]), scn("briefing"))
    assert "첫 소리 3초 이내" in failed(r, SOFT)


def test_tone_rules():
    assert "반말 유지" in failed(check(log("briefing", [T("좋은 아침이에요. 비가 와요.")]), scn("briefing", "CASUAL")))
    assert "존댓말 유지" in failed(check(log("briefing", [T("좋은 아침이야. 비가 와.")]), scn("briefing", "FORMAL")))
    assert "존댓말 유지" not in failed(check(log("briefing", [T("좋은 아침이에요. 우산을 챙기세요.")]), scn("briefing")))


# ---------- 밤 ----------
def test_night_rules():
    ok = [T("오늘 발표 리허설이 있으셨네요, 잘 마무리하셨어요?"), T("고생 많으셨어요. 내일 일정은 있으세요?", heard="피곤해요"),
          T("알겠어요. 푹 쉬세요.", [END])]
    r = check(log("night", ok, "end_session"), scn("night", event_words=["리허설"]))
    assert not failed(r) and not failed(r, SOFT)
    long_first = [T("오늘 하루 고생 많으셨어요. 일정이 많았네요. 어땠어요?")]
    assert "밤: 첫 마디 2문장 이하, 질문 하나" in failed(check(log("night", long_first), scn("night")))
    no_q = [T("오늘 발표가 있으셨네요, 잘 하셨어요?"), T("고생 많으셨어요. 푹 쉬세요.")]
    assert "밤: 1번째 답변이 질문으로 끝남" in failed(check(log("night", no_q), scn("night")), SOFT)
    q_end = [T("오늘 어땠어요?"), T("내일 일정은 있으세요?", [END])]
    assert "밤: 질문하고 바로 끝내지 않음" in failed(check(log("night", q_end, "end_session"), scn("night")))


def test_night_extract_checks():
    ex_ok = NightRecordResult("DONE", {"override_wake_time": "07:30", "override_coaching_min": 10})
    s = scn("night", extract={"override_wake_time": "07:30", "override_coaching_min": 10})
    base = [T("오늘 어땠어요?")]
    assert not failed(check(log("night", base), s, {"extract": ex_ok}))
    ex_bad = NightRecordResult("DONE", {"override_wake_time": "08:00", "override_coaching_min": None})
    assert {"추출 override_wake_time", "추출 override_coaching_min"} <= failed(check(log("night", base), s, {"extract": ex_bad}))
    assert "추출 성공" in failed(check(log("night", base), s, {"extract": NightRecordResult("FAILED", {}, "실패")}))


# ---------- 코칭 ----------
def coaching_log(first_calls=(), verdicts=((1, "done"), (2, "skipped")), end=True):
    turns = [T("먼저 물 한 잔이에요. 끝나면 '했어요'라고 말씀해 주세요.", first_calls),
             T("다음은 스트레칭이에요.", [rep(*verdicts[0])]),
             T("잘하셨어요. 좋은 하루 보내세요.", [rep(*verdicts[1])] + ([END] if end else []))]
    return log("coaching", turns, "end_session" if end else "turnComplete")


def test_coaching_pass():
    s = scn("coaching", verdicts={1: "done", 2: "skipped"}, step_names=["물 한 잔", "스트레칭"])
    r = check(coaching_log(), s)
    assert not failed(r) and not failed(r, SOFT)


def test_coaching_premature_report_and_wrong_verdict_and_no_end():
    s = scn("coaching", verdicts={1: "done", 2: "skipped"})
    assert "코칭: 사용자 답 전에 판정 호출 없음" in failed(check(coaching_log(first_calls=[rep(1, "done")]), s))
    assert "코칭: 판정이 기대와 같음" in failed(check(coaching_log(verdicts=((1, "done"), (2, "done"))), s))
    assert "코칭: 마지막에 end_session" in failed(check(coaching_log(end=False), s))


def test_coaching_duplicate_and_two_steps_in_one_turn_are_warnings():
    t = coaching_log(first_calls=())
    t.turns[1].calls.append(rep(1, "done"))
    s = scn("coaching", verdicts={1: "done", 2: "skipped"}, step_names=["물 한 잔", "스트레칭"])
    assert "코칭: 같은 단계 판정 중복 없음" in failed(check(t, s), SOFT)
    t2 = coaching_log()
    t2.turns[0].said = "먼저 물 한 잔이에요. 다음은 스트레칭이에요."
    r = check(t2, s)
    assert "코칭: 0번째 안내가 단계 하나만" in failed(r, SOFT) and not failed(r)


def test_quiz_answer_leak_is_hard_failure():
    s = scn("coaching", verdicts={1: "done", 2: "done"}, answers=["서울", "열두 달"])
    ok = log("coaching", [T("문제예요. 대한민국의 수도는 어디일까요?"), T("맞아요, 서울이에요. 다음 문제예요. 1년은 몇 개월일까요?", [rep(1, "done")]),
                          T("열두 달이에요. 수고했어요.", [rep(2, "done"), END])], "end_session")
    assert not failed(check(ok, s))
    leak = log("coaching", [T("대한민국의 수도는 서울인데, 어디일까요?"), T("다음 문제예요.", [rep(1, "done")]),
                            T("마무리예요.", [rep(2, "done"), END])], "end_session")
    assert "코칭: 0번째 말에서 정답 미공개" in failed(check(leak, s))


def test_summarize():
    r = check(log("briefing", [T("좋은 아침이에요?")]), scn("briefing"))
    hard, soft = summarize(r)
    assert hard >= 1 and soft >= 0


# ---------- 시나리오 정의 ----------
@pytest.mark.parametrize("name", list(SCENARIOS))
def test_scenarios_build_valid_setup(name):
    s = SCENARIOS[name]
    setup = build_setup(s.kind, s.inp)
    assert setup["systemInstruction"]["parts"][0]["text"]
    assert isinstance(s.inp, {"night": NightInput, "briefing": BriefingInput}.get(s.kind, object))
    if s.kind in ("night", "coaching"):
        assert s.utterances
    if s.kind == "briefing":
        assert not s.utterances
    if s.kind == "coaching":
        n = len(s.inp.steps)
        assert set(s.expect["verdicts"]) == set(range(1, n + 1)) and len(s.utterances) == n


def test_quiz_scenario_answers_match_steps():
    s = SCENARIOS["coaching_quiz"]
    for step, ans in zip(s.inp.steps, s.expect["answers"]):
        assert f"정답: {ans}" in step.script or ans in step.script.split("정답:")[1]


def test_night_override_proposal_may_use_three_sentences():
    proposal = "치과 예약이 있으시군요. 피곤해 보이는데 내일 알람을 늦춰 볼까요? 내일 하루만 적용되는 변경이에요."
    r = check(log("night", [T("오늘 어땠어요?"), T(proposal)]), scn("night"))
    assert not [x for x in r if not x.passed and x.severity == HARD and "답변" in x.rule]
    r = check(log("night", [T("오늘 어땠어요?"), T("고생 많으셨어요. 푹 쉬세요. 내일 일정이 있으세요?")]), scn("night"))
    assert "밤: 1번째 답변 2문장 이하" in failed(r)       # '하루' 안내가 없는 일반 답변은 2문장까지
