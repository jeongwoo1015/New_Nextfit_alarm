from datetime import datetime

import pytest

from backend.core.errors import ValidationFailed
from backend.core.timeutil import KST
from backend.domain.models import SessionStep
from backend.domain.step_rules import apply_verdict, mark, pending_steps, progress

T1 = datetime(2026, 11, 2, 7, 5, tzinfo=KST)
T2 = datetime(2026, 11, 2, 7, 9, tzinfo=KST)


def step(order=1, **over):
    return SessionStep(f"s{order}", "u1", "sess", order, f"단계 {order}", **over)


def invariant(s):
    assert (s.step_status == "PENDING") == (s.checked_at is None)


@pytest.mark.parametrize("verdict, status", [("done", "DONE"), ("skipped", "SKIPPED"), ("not_done", "SKIPPED")])
def test_verdict_sets_status_and_checked_at(verdict, status):
    s = apply_verdict(step(), verdict, T1)
    assert (s.step_status, s.checked_at, s.ai_verdict) == (status, T1, verdict)
    invariant(s)


@pytest.mark.parametrize("verdict", ["ambiguous", "other"])
def test_unclear_verdict_keeps_status(verdict):
    s = apply_verdict(step(), verdict, T1)
    assert (s.step_status, s.checked_at, s.ai_verdict) == ("PENDING", None, verdict)
    done = apply_verdict(apply_verdict(step(), "done", T1), verdict, T2)
    assert (done.step_status, done.checked_at, done.ai_verdict) == ("DONE", T1, verdict)


def test_last_verdict_wins():
    s = apply_verdict(apply_verdict(step(), "done", T1), "skipped", T2)
    assert (s.step_status, s.checked_at, s.ai_verdict) == ("SKIPPED", T2, "skipped")
    same = apply_verdict(apply_verdict(step(), "done", T1), "done", T2)
    assert same.checked_at == T1        # 같은 상태면 체크 시각 유지


def test_unknown_verdict_rejected():
    with pytest.raises(ValidationFailed):
        apply_verdict(step(), "DONE", T1)


def test_manual_mark_and_undo():
    s = mark(step(ai_verdict="ambiguous"), "DONE", T1)
    assert (s.step_status, s.checked_at, s.ai_verdict) == ("DONE", T1, "ambiguous")
    back = mark(s, "PENDING", T2)
    assert (back.step_status, back.checked_at) == ("PENDING", None)
    invariant(back)
    with pytest.raises(ValidationFailed):
        mark(s, "FINISHED", T1)


def test_progress_and_pending_for_reconnect():
    steps = [apply_verdict(step(1), "done", T1), step(3), apply_verdict(step(2), "not_done", T1), step(4)]
    p = progress(steps)
    assert (p.total, p.done, p.skipped, p.pending, p.finished) == (4, 1, 1, 2, False)
    assert [s.step_order for s in pending_steps(steps)] == [3, 4]
    assert progress([apply_verdict(step(), "done", T1)]).finished
    assert not progress([]).finished
