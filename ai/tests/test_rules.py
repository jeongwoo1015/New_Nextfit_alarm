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
START = {"name": "start_routine", "args": {}}


def rep(step, verdict):
    return {"name": "report_step_result", "args": {"step_order": step, "verdict": verdict}}


def test_sentences_split_without_spaces():
    assert sentences("좋은 아침이에요.오늘은 비가 와요.우산 챙기세요!") == ["좋은 아침이에요.", "오늘은 비가 와요.", "우산 챙기세요!"]
    assert len(sentences("음...")) <= 1 and sentences("") == []


# ---------- 공통 ----------
def test_common_rules():
    r = check(log("briefing", [T("좋은 아침이에요. [END]")]), scn("briefing"))
    assert "[END] 글자 없음" in failed(r)
    assert "마크다운 없음" in failed(check(log("briefing", [T("**좋은 아침**이에요.")]), scn("briefing")))
    assert "영어 덧붙임 없음" in failed(check(log("briefing", [T("좋은 아침이에요. This is the end of the session. Goodbye!")]), scn("briefing")))
    bad = [{"name": "report_step_result", "args": {"step_order": 1, "verdict": "great"}}]
    assert "함수 호출 형식" in failed(check(log("briefing", [T("좋은 아침이에요.", bad)]), scn("briefing")))
    assert "첫 소리 3초 이내" in failed(check(log("briefing", [T("좋은 아침이에요.", fa=5.2)]), scn("briefing")), SOFT)


def test_tone_rules():
    assert "반말 유지" in failed(check(log("briefing", [T("좋은 아침이에요. 비가 와요.")]), scn("briefing", "CASUAL")))
    assert "존댓말 유지" in failed(check(log("briefing", [T("좋은 아침이야. 비가 와.")]), scn("briefing", "FORMAL")))
    assert "존댓말 유지" not in failed(check(log("briefing", [T("좋은 아침이에요. 우산을 챙기세요.")]), scn("briefing")))


def test_ai_must_not_end_before_the_user_says_so():
    early = log("night", [T("오늘 어땠어요?"), T("고생 많으셨어요. 푹 쉬세요.", [END], heard="발표는 잘 끝났어요")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" in failed(check(early, scn("night")))
    ok = log("night", [T("오늘 어땠어요?"), T("잘 자요, 좋은 꿈 꿔요.", [END], heard="이제 잘게요")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" not in failed(check(ok, scn("night", farewell=True)))
    # 사용자가 작별했는데 AI가 종료 호출을 안 하면 실패(farewell 기대가 있을 때)
    miss = log("night", [T("오늘 어땠어요?"), T("잘 자요.", heard="이제 잘게요")])
    assert "사용자 작별 뒤 end_session 호출" in failed(check(miss, scn("night", farewell=True)))


# ---------- 밤 ----------
def test_night_rules():
    ok = [T("오늘 발표 리허설이 있으셨네요, 잘 마무리하셨어요?"), T("고생 많으셨어요. 그때 기분은 어땠어요?", heard="피곤해요"),
          T("오늘은 발표를 잘 마쳤지만 지친 하루였던 것 같아요. 맞아요?", heard="뿌듯했어요")]
    r = check(log("night", ok), scn("night", event_words=["리허설"]))
    assert not failed(r) and not failed(r, SOFT)
    long_first = [T("오늘 하루 고생 많으셨어요. 일정이 많았네요. 어땠어요?")]
    assert "밤: 첫 마디 2문장 이하, 질문 하나" in failed(check(log("night", long_first), scn("night")))
    no_q = [T("오늘 발표가 있으셨네요, 잘 하셨어요?"), T("고생 많으셨어요. 푹 쉬세요.", heard="네")]
    assert "밤: 1번째 답변이 질문으로 끝남" in failed(check(log("night", no_q), scn("night")), SOFT)


def test_night_asks_tomorrow_naturally_not_awkwardly():
    weird = [T("오늘 발표가 있으셨네요, 잘 하셨어요?"), T("고생 많았어요. 오늘은 내일 특별한 일정이나 챙겨야 할 게 있어요?", heard="네")]
    assert "밤: '오늘은 내일…'처럼 어색한 문장 없음" in failed(check(log("night", weird), scn("night")))
    natural = [T("오늘 발표가 있으셨네요, 잘 하셨어요?"), T("오늘 하루를 정리해 드릴게요. 내일은 꼭 해내고 싶은 일이 있어요?", heard="네")]
    r = check(log("night", natural), scn("night", asks_tomorrow=True))
    assert "밤: '오늘은 내일…'처럼 어색한 문장 없음" not in failed(r) and "밤: 내일 목표·특이사항을 물어봄" not in failed(r, SOFT)
    never = [T("오늘 발표가 있으셨네요, 잘 하셨어요?"), T("고생 많았어요. 그때 기분은 어땠어요?", heard="네")]
    assert "밤: 내일 목표·특이사항을 물어봄" in failed(check(log("night", never), scn("night", asks_tomorrow=True)), SOFT)


def test_night_extract_checks():
    ex_ok = NightRecordResult("DONE", {"override_wake_time": "07:30", "override_coaching_min": 10, "diary_text": "지쳤지만 뿌듯한 하루"})
    s = scn("night", extract={"override_wake_time": "07:30", "override_coaching_min": 10}, diary=True)
    base = [T("오늘 어땠어요?")]
    assert not failed(check(log("night", base), s, {"extract": ex_ok}))
    ex_bad = NightRecordResult("DONE", {"override_wake_time": "08:00", "override_coaching_min": None, "diary_text": None})
    assert {"추출 override_wake_time", "추출 override_coaching_min", "추출 diary_text(한 줄 일기)"} <= failed(check(log("night", base), s, {"extract": ex_bad}))
    assert "추출 성공" in failed(check(log("night", base), s, {"extract": NightRecordResult("FAILED", {}, "실패")}))


def test_night_override_proposal_may_use_three_sentences():
    proposal = "치과 예약이 있으시군요. 피곤해 보이는데 내일 알람을 늦춰 볼까요? 내일 하루만 적용되는 변경이에요."
    r = check(log("night", [T("오늘 어땠어요?"), T(proposal)]), scn("night"))
    assert not [x for x in r if not x.passed and x.severity == HARD and "답변" in x.rule]
    r = check(log("night", [T("오늘 어땠어요?"), T("고생 많으셨어요. 푹 쉬세요. 더 이야기하고 싶은 게 있어요?")]), scn("night"))
    assert "밤: 1번째 답변 2문장 이하" in failed(r)


# ---------- 브리핑 ----------
WAKE_Q = "예준 님 좋은 아침이에요. 잘 주무셨어요?"
BRIEF = ("지금 7시 2분이에요. 오늘은 비가 오니 우산을 챙기세요. 오전 9시 반에 주간 회의가 있어요. "
         "어젯밤 정리한 컨디션을 생각해 부담 없이 시작해요. 이제 아침 루틴을 시작할 준비가 됐어요?")


def briefing_log(brief=BRIEF, start=True):
    t2 = T("좋아요, 시작해요.", [START] if start else [], heard="네 준비됐어요")
    return log("briefing", [T(WAKE_Q), T(brief, heard="응 일어났어요"), t2], "start_routine" if start else "turnComplete")


def test_briefing_passes():
    s = scn("briefing", weather_words=["우산"], event_words=["회의"], now_words=["7시"], expect_start=True)
    r = check(briefing_log(), s)
    assert not failed(r) and not failed(r, SOFT)


def test_briefing_must_ask_one_check_question_first_then_brief():
    s = scn("briefing")
    direct = log("briefing", [T("좋은 아침이에요. 오늘은 비가 와요. 우산을 챙기세요. 일정이 있어요. 힘내요.")])
    assert "브리핑: 확인 질문 하나만 묻기" in failed(check(direct, s))
    no_brief = log("briefing", [T(WAKE_Q)])
    assert "브리핑: 확인 질문에 답한 뒤 브리핑함" in failed(check(no_brief, s))
    name = "브리핑: 확인 질문 하나만 묻기"
    assert name not in failed(check(briefing_log(), s))
    assert name in failed(check(log("briefing", [T("잘 주무셨어요? 지금 몸은 좀 깼어요?"), T(BRIEF, heard="네")]), s))      # 한 번에 두 가지
    assert name in failed(check(log("briefing", [T("오늘 날씨가 궂은데 잘 주무셨어요?"), T(BRIEF, heard="네")]), s))          # 날씨를 섞음


def test_sleepy_answer_still_counts_as_answer():
    sleepy = [T(WAKE_Q), T(BRIEF, heard="음... 아직 졸려요"), T("좋아요, 시작해요.", [START], heard="네 준비됐어요")]
    assert not failed(check(log("briefing", sleepy, "start_routine"), scn("briefing", expect_start=True)))


def test_briefing_must_not_call_a_wake_function():
    called = briefing_log()
    called.turns[1].calls.append({"name": "wake_check_done", "args": {}})          # 기상 확인은 앱이 판단한다: 알 수 없는 함수 호출은 실패
    assert "함수 호출 형식" in failed(check(called, scn("briefing")))


def test_briefing_no_alarm_words_and_question_rules():
    s = scn("briefing")
    with_alarm = BRIEF.replace("지금 7시 2분이에요.", "알람은 7시로 맞춰져 있어요.")
    assert "브리핑: 알람 시각·알람 언급 없음" in failed(check(briefing_log(with_alarm), s))
    no_q = BRIEF.replace("준비가 됐어요?", "준비해요.")
    assert "브리핑: 4~7문장(마지막은 준비 확인 질문)" in failed(check(briefing_log(no_q), s))
    two_q = BRIEF.replace("우산을 챙기세요.", "우산 챙겼어요?")
    assert "브리핑: 4~7문장(마지막은 준비 확인 질문)" in failed(check(briefing_log(two_q), s))


def test_briefing_start_only_after_ready_answer():
    s = scn("briefing", expect_start=True)
    early = log("briefing", [T(WAKE_Q), T(BRIEF, [START], heard="응 일어났어요")], "start_routine")
    assert "브리핑: 준비됐다는 답 뒤에만 시작" in failed(check(early, s))
    assert "브리핑: 준비됐다고 하면 start_routine 호출" in failed(check(briefing_log(start=False), s))


def test_briefing_zero_coaching():
    zero = ("지금 7시 2분이야. 비가 오니까 우산 챙겨. 오전 9시 반에 주간 회의가 있어. 어제 피곤했으니 부담 없이 시작해. 오늘도 힘내길 바랄게.")
    ok = log("briefing", [T("좋은 아침이야. 일어났어?"), T(zero, heard="응 일어났어"), T("잘 가, 좋은 하루.", [END], heard="고마워 이만 끊을게")], "end_session")
    r = check(ok, scn("briefing", "CASUAL", coaching_skipped=True, farewell=True))
    assert not failed(r)
    invite = zero.replace("오늘도 힘내길 바랄게.", "이제 루틴을 시작하자.")
    bad = log("briefing", [T("좋은 아침이야. 일어났어?"), T(invite, heard="응")])
    assert "브리핑: 코칭 안 하는 날이면 시작 권유 없음" in failed(check(bad, scn("briefing", "CASUAL", coaching_skipped=True)))
    asks = zero.replace("오늘도 힘내길 바랄게.", "준비됐어?")
    bad2 = log("briefing", [T("좋은 아침이야. 일어났어?"), T(asks, heard="응")])
    assert "브리핑: 코칭 안 하는 날이면 4~6문장, 질문 없음" in failed(check(bad2, scn("briefing", "CASUAL", coaching_skipped=True)))


# ---------- 코칭 ----------
def coaching_log(first_calls=(), verdicts=((1, "done"), (2, "skipped")), farewell=True, end=True):
    turns = [T("먼저 물 한 잔이에요. 끝나면 '했어요'라고 말씀해 주세요.", first_calls),
             T("다음은 스트레칭이에요.", [rep(*verdicts[0])], heard="마셨어요"),
             T("잘하셨어요. 좋은 하루 보내세요.", [rep(*verdicts[1])], heard="건너뛸게요")]
    if farewell:
        turns.append(T("안녕히 계세요.", [END] if end else [], heard="이만 끝낼게요"))
    return log("coaching", turns, "end_session" if (farewell and end) else "turnComplete")


def test_coaching_pass():
    s = scn("coaching", verdicts={1: "done", 2: "skipped"}, step_names=["물 한 잔", "스트레칭"], farewell=True)
    r = check(coaching_log(), s)
    assert not failed(r) and not failed(r, SOFT)


def test_coaching_premature_wrong_turn_and_wrong_verdict():
    s = scn("coaching", verdicts={1: "done", 2: "skipped"})
    assert "코칭: 사용자 답 전에 판정 호출 없음" in failed(check(coaching_log(first_calls=[rep(1, "done")]), s))
    assert "코칭: 판정이 기대와 같음" in failed(check(coaching_log(verdicts=((1, "done"), (2, "done"))), s))
    ahead = coaching_log()
    ahead.turns[1].calls.append(rep(2, "done"))                    # 1번 답을 받은 턴에서 2번까지 미리 판정(실제 실행에서 나온 문제)
    assert "코칭: 한 턴에 방금 답한 단계만 판정" in failed(check(ahead, s))


def test_coaching_ai_must_not_end_by_itself_and_must_end_on_goodbye():
    s = scn("coaching", verdicts={1: "done", 2: "skipped"}, farewell=True)
    self_end = coaching_log(farewell=False)
    self_end.turns[-1].calls.append(END)
    self_end.ended_by = "end_session"
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" in failed(check(self_end, s))
    assert "사용자 작별 뒤 end_session 호출" in failed(check(coaching_log(end=False), s))


def test_coaching_duplicate_and_two_steps_in_one_turn_are_warnings():
    t = coaching_log()
    t.turns[1].calls.append(rep(1, "done"))
    s = scn("coaching", verdicts={1: "done", 2: "skipped"}, step_names=["물 한 잔", "스트레칭"])
    assert "코칭: 같은 단계 판정 중복 없음" in failed(check(t, s), SOFT)
    t2 = coaching_log()
    t2.turns[0].said = "먼저 물 한 잔이에요. 다음은 스트레칭이에요."
    r = check(t2, s)
    assert "코칭: 0번째 안내가 단계 하나만" in failed(r, SOFT) and not failed(r)


def test_quiz_answer_leak_is_hard_failure():
    s = scn("coaching", verdicts={1: "done", 2: "done"}, answers=["서울", "열두 달"])
    ok = log("coaching", [T("문제예요. 대한민국의 수도는 어디일까요?"), T("맞아요, 서울이에요. 다음 문제예요. 1년은 몇 개월일까요?", [rep(1, "done")], heard="서울"),
                          T("열두 달이에요. 수고했어요.", [rep(2, "done")], heard="열두 달")])
    assert not failed(check(ok, s))
    leak = log("coaching", [T("대한민국의 수도는 서울인데, 어디일까요?"), T("다음 문제예요.", [rep(1, "done")], heard="서울"),
                            T("마무리예요.", [rep(2, "done")], heard="열두 달")])
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
    assert s.utterances or s.kind == "fortune"                       # 새 흐름에서는 브리핑도 사용자 답(확인 질문 답/준비됐어)이 필요하다. 포춘은 읽기만 해서 말이 없어도 된다
    if s.kind == "fortune":
        assert s.expect.get("fortune_text") and s.expect["fortune_text"] in s.inp.fortune_text         # 읽을 문장이 기대값과 같다
    if s.expect.get("farewell"):
        assert s.utterances[-1] and any(w in s.utterances[-1] for w in ("잘게", "끊을게", "끝낼게"))   # 끝내는 쪽은 항상 사용자
    if s.kind == "briefing":
        assert len(s.utterances) == 2                                                                    # 확인 질문 답 + (준비됐어요 또는 작별)
    if s.kind == "coaching":
        removed = {k for k, v in s.expect.get("adjust", {}).items() if v == 0}                          # 오늘 뺀 단계는 판정하지 않는다
        assert set(s.expect["verdicts"]) == {st.order for st in s.inp.steps} - removed
        assert len(s.utterances) == len(s.expect["verdicts"]) + len(s.expect.get("no_verdict_turns", ())) + s.expect.get("extra_turns", 0) + 1   # 판정 턴 + 시간 변경 턴 + (게임처럼 한 단계 안의 추가 대화) + 작별 인사


def test_new_scenarios_exist():
    assert "night_ignore_proposal" in SCENARIOS                      # 제안에 답하지 않았을 때 변경이 기록되면 안 된다
    assert SCENARIOS["night_ignore_proposal"].expect["extract"] == {"override_wake_time": None, "override_skip_coaching": False}


def test_quiz_scenario_answers_match_steps():
    s = SCENARIOS["coaching_quiz"]
    for step, ans in zip(s.inp.steps, s.expect["answers"]):
        assert f"정답: {ans}" in step.script or ans in step.script.split("정답:")[1]


# ---------- 콘텐츠는 별도 세션에서 진행하고 이어서 기본 루틴으로 넘어가는 흐름 ----------
QUIZ_VERDICTS = {1: "done", 2: "done", 3: "done"}


def content_log(ahead=False):
    turns = [T("먼저 첫 문제예요. 대한민국의 수도는 어디일까요?"),
             T("맞아요. 다음 문제예요. 1년은 몇 개월일까요?", [rep(1, "done")] + ([rep(2, "done")] if ahead else []), heard="서울이에요"),
             T("맞아요. 마지막 문제예요. 한글을 만든 임금님은 누구일까요?", [rep(2, "done")], heard="열두 달이요"),
             T("잘하셨어요. 좋은 하루 보내세요.", [rep(3, "done")], heard="세종대왕이요"),
             T("안녕히 계세요.", [END], heard="고마워요 이만 끝낼게요")]
    return log("coaching", turns, "end_session")


def content_scn(**kw):
    base = dict(verdicts=QUIZ_VERDICTS, answers=["서울", "열두 달", "세종대왕"], farewell=True)
    base.update(kw)
    return scn("coaching", **base)


# ---------- 시간 표기: "열다섯 분"이 아니라 15분 / 십오 분 ----------
def test_native_korean_minutes_are_flagged():
    rule = "시간은 '열다섯 분'이 아니라 15분·십오 분으로 말함"
    for bad in ("회의는 아홉 시 열다섯 분에 시작해요.", "독서는 열다섯분 동안 해요.", "스트레칭은 스무 분이 아니라 두 분 반이에요."):
        assert rule in failed(check(log("coaching", [T(bad)]), scn("coaching"))), bad
    for ok in ("회의는 9시 15분에 시작해요.", "독서는 십오 분 동안 해요.", "지금은 일곱 시 이 분이에요.", "한 분이 오셨어요."):
        assert rule not in failed(check(log("coaching", [T(ok)]), scn("coaching"))), ok


# ---------- 루틴 중간 독려 ----------
def test_encouragement_between_steps_is_required():
    def coach(says):
        turns = [T("첫 단계예요. 물 한 잔 마셔 주세요.")] + [T(txt, [rep(i, "done")], heard="했어요") for i, txt in enumerate(says, 1)]
        return log("coaching", turns)
    s = scn("coaching", verdicts={1: "done", 2: "done", 3: "done"}, encourages=True)
    rule = "코칭: 단계 사이에 독려 한마디"
    plain = coach(["잘하셨어요. 다음은 스트레칭이에요.", "좋아요. 마지막은 할 일 말하기예요.", "수고하셨어요."])
    assert rule in failed(check(plain, s))
    cheer = coach(["잘하셨어요. 다음은 스트레칭이에요.", "좋아요, 거의 다 왔어요. 마지막은 할 일 말하기예요.", "수고하셨어요."])
    assert rule not in failed(check(cheer, s))
    last_only = coach(["잘하셨어요. 다음은 스트레칭이에요.", "좋아요. 마지막은 할 일 말하기예요.", "정말 대단해요, 힘차게 하루를 시작해요."])    # 마지막 단계 뒤 마무리 칭찬은 세지 않는다
    assert rule in failed(check(last_only, s))


# ---------- 밤 대화 마무리: 묻지 않고 "이제 마무리할게요", 작별 인사는 한 번만, 그 응답에서 end_session ----------
CLOSING_BASE = [T("오늘 하루는 어땠어요?"), T("점심은 뭘 드셨어요?", heard="별일 없었어요"),
                T("오늘은 김치찌개를 맛있게 먹은 평온한 하루였던 것 같아요, 맞아요?", heard="김치찌개요"),
                T("내일 미리 챙길 일정이나 걱정이 있으세요?", heard="네 맞아요")]


def test_ai_closes_by_itself_after_summary_and_tomorrow():
    ok = log("night", CLOSING_BASE + [T("이제 마무리할게요. 편안한 밤 되세요.", [END], heard="없어요")], "end_session")
    r = check(ok, scn("night", ends_cleanly=True))
    for rule in ("밤: 작별 인사는 한 번만 하고 그 응답에서 대화를 끝냄", "밤: 마무리해도 되는지 묻지 않음", "밤: 요약 확인과 내일 이야기를 마친 뒤에 마무리함",
                 "밤: 마무리하는 응답은 질문으로 끝나지 않음", "사용자가 끝내자고 하기 전에 AI가 끝내지 않음"):
        assert rule not in failed(r), rule


def test_asking_for_permission_to_close_fails():
    bad = log("night", CLOSING_BASE + [T("더 하고 싶은 이야기가 없으시면 이제 마무리할까요?", [END], heard="없어요")], "end_session")
    assert "밤: 마무리해도 되는지 묻지 않음" in failed(check(bad, scn("night", ends_cleanly=True)))


def test_closing_before_summary_and_tomorrow_fails():
    early = log("night", [T("오늘 하루는 어땠어요?"), T("이제 마무리할게요. 편안한 밤 되세요.", [END], heard="별일 없었어요")], "end_session")
    r = check(early, scn("night", ends_cleanly=True))
    assert "밤: 요약 확인과 내일 이야기를 마친 뒤에 마무리함" in failed(r)


def test_closing_response_must_not_end_with_a_question():
    bad = log("night", CLOSING_BASE + [T("이제 마무리할게요, 괜찮으시죠?", [END], heard="없어요")], "end_session")
    assert "밤: 마무리하는 응답은 질문으로 끝나지 않음" in failed(check(bad, scn("night", ends_cleanly=True)))


def test_end_without_closing_words_or_user_farewell_is_still_early():
    early = log("night", [T("오늘 하루는 어땠어요?"), T("편안한 밤 되세요.", [END], heard="별일 없었어요")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" in failed(check(early, scn("night")))
    no_close = log("night", [T("내일 걱정이 있으세요?"), T("알겠어요.", [END], heard="없어요")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" in failed(check(no_close, scn("night")))


def test_short_ack_after_farewell_ends_immediately():
    ok = log("night", [T("내일 걱정이 있으세요?"), T("알겠어요. 편안한 밤 되세요.", heard="없어요"), T("", [END], heard="알았다고")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" not in failed(check(ok, scn("night")))


def test_repeating_goodnight_wishes_without_ending_fails():
    bad = log("night", CLOSING_BASE + [T("편안한 밤 되세요.", heard="없어요"), T("좋은 꿈 꾸세요.", heard="그래")], "turnComplete")
    assert "밤: 작별 인사는 한 번만 하고 그 응답에서 대화를 끝냄" in failed(check(bad, scn("night", ends_cleanly=True)))


def test_opening_greeting_is_not_a_farewell_and_empty_turns_are_skipped():
    turns = [T("예준 님, 오늘 하루는 어땠어요? 편안한 밤이에요.")] + CLOSING_BASE[1:] + [T("", heard=""), T("편안한 밤 되세요.", heard="그래"), T("", [END], heard="알았다고")]
    r = check(log("night", turns, "end_session"), scn("night", ends_cleanly=True))
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" not in failed(r)             # 작별 뒤 "알았다고"에 끝내는 것은 허용 (빈 턴은 건너뜀)
    assert "밤: 작별 인사는 한 번만 하고 그 응답에서 대화를 끝냄" in failed(r)        # 다만 이상적인 동작(작별 응답에서 바로 종료)은 아니므로 엄격 검사는 실패


# ---------- 코칭 중 오늘 시간 변경 (adjust_step_time) ----------
def adj(step, sec):
    return {"name": "adjust_step_time", "args": {"step_order": step, "seconds": sec}}


def adjust_log(call=None, same_turn_verdict=False):
    call = call or adj(2, 60)
    turns = [T("물 한 잔 드셨나요?"),
             T("좋아요. 스트레칭을 해 볼까요?", [rep(1, "done")], heard="물 마셨어요"),
             T("오늘만 스트레칭을 1분으로 바꿨어요. 1분 동안 해 주세요.", [call] + ([rep(2, "done")] if same_turn_verdict else []), heard="스트레칭은 오늘 1분만 할게요"),
             T("잘하셨어요. 오늘 할 일 세 가지를 말해 주세요.", [rep(2, "done")], heard="스트레칭 했어요")]
    return log("coaching", turns)


ADJ_SCN = dict(verdicts={1: "done", 2: "done"}, adjust={2: 60}, no_verdict_turns=(2,))


def test_adjust_step_time_passes_when_it_matches():
    r = check(adjust_log(), scn("coaching", **ADJ_SCN))
    assert not failed(r)


def test_adjust_step_time_checks():
    s = scn("coaching", **ADJ_SCN)
    assert "함수 호출 형식" in failed(check(adjust_log(adj(2, 45)), s))                          # 30초 단위가 아님
    assert "함수 호출 형식" in failed(check(adjust_log({"name": "adjust_step_time", "args": {"step_order": 2}}), s))   # seconds 없음
    assert "코칭: 시간 변경 호출이 기대와 같음" in failed(check(adjust_log(adj(2, 120)), s))      # 다른 시간
    assert "코칭: 시간 변경 호출이 기대와 같음" in failed(check(adjust_log(), scn("coaching", verdicts={1: "done", 2: "done"}, no_verdict_turns=(2,))))   # 요청하지 않았는데 호출
    assert "코칭: 같은 단계에 시간 변경과 판정을 함께 하지 않음" in failed(check(adjust_log(same_turn_verdict=True), s))


def test_removed_step_is_not_judged():
    turns = [T("물 한 잔 드셨나요?"), T("좋아요. 스트레칭을 해 볼까요?", [rep(1, "done")], heard="물 마셨어요"),
             T("오늘만 마지막 단계를 뺐어요. 스트레칭은 끝나면 말씀해 주세요.", [adj(3, 0)], heard="마지막은 오늘 빼 주세요"),
             T("잘하셨어요.", [rep(2, "done")], heard="스트레칭 했어요")]
    s = scn("coaching", verdicts={1: "done", 2: "done"}, adjust={3: 0}, no_verdict_turns=(2,))
    assert not failed(check(log("coaching", turns), s))
    turns[3].calls.append(rep(3, "skipped"))          # 0초로 뺀 단계를 또 판정함
    assert "코칭: 판정이 기대와 같음" in failed(check(log("coaching", turns), s))


def test_content_session_passes():
    r = check(content_log(), content_scn())
    assert not failed(r) and not failed(r, SOFT)


def test_content_session_failures():
    assert "코칭: 한 턴에 방금 답한 단계만 판정" in failed(check(content_log(ahead=True), content_scn()))
    leak = content_log()
    leak.turns[0].said = "대한민국의 수도는 서울인데 어디일까요?"
    assert "코칭: 0번째 말에서 정답 미공개" in failed(check(leak, content_scn()))


def test_briefing_does_not_run_steps():
    s = scn("briefing", ready_words=["퀴즈"], expect_start=True)
    brief = ("지금 7시 2분이에요. 비가 오니 우산을 챙기세요. 오전 9시 반에 주간 회의가 있어요. 어젯밤 정리한 컨디션을 생각해 부담 없이 시작해요. 이제 퀴즈 시간을 시작할 준비가 됐어요?")
    ok = log("briefing", [T(WAKE_Q), T(brief, heard="응 일어났어요"), T("좋아요, 시작해요.", [START], heard="네 퀴즈 시작할 준비됐어요")], "start_routine")
    r = check(ok, s)
    assert not failed(r) and not failed(r, SOFT)
    bad = log("briefing", [T(WAKE_Q), T(brief, [rep(1, "done")], heard="응 일어났어요")])
    assert "브리핑: 단계 판정은 하지 않음(콘텐츠·루틴 진행은 다음 세션)" in failed(check(bad, s))
    wrong_q = log("briefing", [T(WAKE_Q), T(brief.replace("퀴즈 시간을", "아침 일과를"), heard="응 일어났어요")])
    assert "브리핑: 다음 세션 시작 준비를 물음" in failed(check(wrong_q, s), SOFT)


def test_coaching_with_continued_step_numbers():
    s = scn("coaching", verdicts={4: "done", 5: "skipped"}, step_names=["물 한 잔", "스트레칭"], farewell=True)
    turns = [T("먼저 물 한 잔이에요."), T("다음은 스트레칭이에요.", [rep(4, "done")], heard="마셨어요"),
             T("잘하셨어요.", [rep(5, "skipped")], heard="건너뛸게요"), T("안녕히 계세요.", [END], heard="이만 끝낼게요")]
    assert not failed(check(log("coaching", turns, "end_session"), s))
    turns[1].calls = [rep(1, "done")]                                          # 번호가 1부터라고 착각하면 실패
    assert "코칭: 한 턴에 방금 답한 단계만 판정" in failed(check(log("coaching", turns, "end_session"), s))


def test_fixed_script_sentences_are_exempt_from_tone_check():
    from ai.live.inputs import CoachingInput, Step
    inp = CoachingInput(steps=(Step(1, "스트레칭", 120, "PLAY", "목을 천천히 좌우로 돌려요.\n어깨를 크게 돌려요."),))
    s = Scenario("t", "coaching", inp, tone="CASUAL", expect={"verdicts": {1: "done"}})
    said = "스트레칭 하자. 목을 천천히 좌우로 돌려요. 어깨를 크게 돌려요."          # 스크립트 문장은 그대로 읽은 것
    assert "반말 유지" not in failed(check(log("coaching", [T(said)]), s))
    assert "반말 유지" in failed(check(log("coaching", [T("스트레칭 해요. 시작해요.")]), s))      # 스크립트가 아닌 존댓말은 여전히 실패


# ---------- 포춘 읽기 ----------
FORTUNE = "용기는 떨림이 사라진 뒤가 아니라, 첫마디를 꺼낼 때 찾아옵니다."


def fscn(**kw):
    s = scn("fortune", fortune_text=FORTUNE, **kw)
    return s


def test_fortune_exact_reading_passes():
    r = check(log("fortune", [T(f"오늘의 포춘이에요. {FORTUNE}")]), fscn())
    assert not failed(r) and not failed(r, SOFT)
    r = check(log("fortune", [T(f"오늘의 포춘이에요.{FORTUNE.replace(', ', ',')}")]), fscn())     # 받아쓰기 공백·쉼표 차이는 허용
    assert not failed(r)


def test_fortune_changed_or_missing_sentence_fails():
    changed = "용기는 떨림이 사라진 뒤에 찾아옵니다."
    assert "포춘: 문장을 한 글자도 바꾸지 않고 읽음" in failed(check(log("fortune", [T(f"오늘의 포춘이에요. {changed}")]), fscn()))
    assert "포춘: 문장을 한 글자도 바꾸지 않고 읽음" in failed(check(log("fortune", [T("오늘의 포춘이에요.")]), fscn()))


def test_fortune_extras_are_soft_but_repeat_question_and_calls_are_hard():
    chatty = f"좋은 아침이에요. 오늘의 포춘이에요. {FORTUNE} 힘이 되었으면 좋겠어요. 오늘도 파이팅이에요."
    r = check(log("fortune", [T(chatty)]), fscn())
    assert "포춘: 소개 한 문장 외에 덧붙이지 않음" in failed(r, SOFT) and not failed(r)
    assert "포춘: 첫 응답에서 한 번만 읽음" in failed(check(log("fortune", [T(f"{FORTUNE} {FORTUNE}")]), fscn()))
    assert "포춘: 읽은 뒤 질문하지 않음" in failed(check(log("fortune", [T(f"{FORTUNE} 어때요?")]), fscn()))
    assert "포춘: 읽는 중 함수 호출 없음" in failed(check(log("fortune", [T(FORTUNE, calls=[END])]), fscn()))


def test_fortune_replay_check():
    ok = log("fortune", [T(FORTUNE), T(FORTUNE, heard="다시 읽어 주세요"), T("네 좋은 하루 보내세요.", [END], heard="이만 끝낼게요")], "end_session")
    assert not failed(check(ok, fscn(replay=True, farewell=True)))
    bad = log("fortune", [T(FORTUNE), T("네 알겠어요.", heard="다시 읽어 주세요")])
    assert "포춘: 다시 읽어 달라고 하면 그대로 한 번 더 읽음" in failed(check(bad, fscn(replay=True)))


def test_morning_sessions_reject_night_greetings():
    from ai.devclient.rules import NIGHT_GREETING
    bad = log("coaching", [T("퀴즈를 모두 풀었네요. 안녕히 주무세요.")])
    assert "아침 대화에 밤 인사 없음" in failed(check(bad, scn("coaching")))
    ok = log("coaching", [T("퀴즈를 모두 풀었네요. 좋은 하루 보내세요.")])
    assert "아침 대화에 밤 인사 없음" not in failed(check(ok, scn("coaching")))
    assert "아침 대화에 밤 인사 없음" not in {r.rule for r in check(log("night", [T("편안한 밤 되세요.")]), scn("night"))}     # 밤 대화는 해당 없음
    assert all(NIGHT_GREETING.search(x) for x in ("잘 자요", "편안한 밤 되세요", "좋은 꿈 꾸세요"))


def test_wordchain_pass_tolerances():
    s = Scenario("w", "coaching", None, expect={"verdicts": {1: "done"}, "verdict_any_of": {1: ("skipped",)}, "pass_ends": True, "report_turn": {1: 2}})
    skipped = log("coaching", [T("앞 단어의 마지막 글자로 시작해요. 첫 단어는 사과예요."), T("일기"), T("알겠어요.", [rep(1, "skipped")], heard="패스할게요")])
    assert "코칭: 판정이 기대와 같음" not in failed(check(skipped, s))
    ended = log("coaching", [T("앞 단어의 마지막 글자로 시작해요. 첫 단어는 사과예요."), T("일기"),
                             T("함께해 주셔서 감사해요.", [rep(1, "done"), END], heard="음 패스할게요")], "end_session")
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" not in failed(check(ended, s))
    strict = Scenario("w", "coaching", None, expect={"verdicts": {1: "done"}, "report_turn": {1: 2}})
    assert "사용자가 끝내자고 하기 전에 AI가 끝내지 않음" in failed(check(ended, strict))            # 허용은 끝말잇기 시나리오에만 적용된다
    assert "코칭: 판정이 기대와 같음" in failed(check(skipped, strict))
