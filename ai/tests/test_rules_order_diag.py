"""11/21(로컬 전용 테스트): 판정 순서 점검이 미리/늦음/무응답을 구분해 보여 주는지 본다. 모델 호출은 없다."""
from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import check
from ai.devclient.scenarios import SCENARIOS


def rep(order):
    return {"name": "report_step_result", "args": {"step_order": order, "verdict": "done"}}


def turn(heard="", said="안내해요.", calls=()):
    return TurnLog(label=heard or "시작", heard=heard, said=said, calls=list(calls))


def run(turns, ended_by="end_session"):
    return check(SessionLog(kind="coaching", turns=turns, ended_by=ended_by), SCENARIOS["tone_formal_user_casual"])


def by(results, name):
    return next(r for r in results if r.rule == name)


ORDER_RULE = "코칭: 한 턴에 방금 답한 단계만 판정"
SILENT_RULE = "코칭: 사용자 말에 AI가 응답함(무응답 턴 없음)"
END = turn("고마워 이만 끊을게", "좋은 하루 보내세요.", [{"name": "end_session", "args": {}}])


def clean_turns():
    # 발화: 1 응 했어 / 2 다시 설명(판정 없음) / 3 했어 / 4 다시 설명(판정 없음) / 5 했어 / 6 응 했어 / 7 작별
    return [turn(), turn("응 했어", calls=[rep(1)]), turn("못 들었어"), turn("했어", calls=[rep(2)]), turn("어렵네"),
            turn("했어", calls=[rep(3)]), turn("응 했어", calls=[rep(4)]), END]


def test_clean_order_passes_both_checks():
    r = run(clean_turns())
    assert by(r, ORDER_RULE).passed and by(r, SILENT_RULE).passed


def test_silent_turn_makes_next_report_late_and_is_labeled():
    t = clean_turns()
    t[5] = turn("했어", said="", calls=[])                  # 5번째 발화에 AI가 말도 호출도 하지 않음
    t[6] = turn("응 했어", calls=[rep(3)])                  # 3단계 판정이 한 턴 늦게 옴
    t[7] = turn("응 했어", calls=[rep(4)])
    t.append(END)
    r = run(t)
    order, silent = by(r, ORDER_RULE), by(r, SILENT_RULE)
    assert not order.passed and "늦음" in order.detail and "무응답" in order.detail
    assert not silent.passed and "5" in silent.detail


def test_early_report_is_labeled_early():
    t = clean_turns()
    t[3] = turn("했어", calls=[rep(2), rep(3)])             # 2단계 답에서 3단계까지 미리 판정
    t[5] = turn("했어")
    r = run(t)
    order = by(r, ORDER_RULE)
    assert not order.passed and "미리" in order.detail
