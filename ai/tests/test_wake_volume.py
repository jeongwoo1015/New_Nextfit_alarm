"""11/21: 기상 볼륨 개인화(시작 단계 Bandit). 사다리 학습, 사용자 우선 규칙, 상승 보류, 저장 형식을 본다. 모델 호출은 없다."""
import random

import pytest

from ai.personalization.wake_volume import (CANDIDATES, CONTEXT, raise_announced, START_CAP, UserWakePrefs, WakeLadder, decide_start_level, learn_wake, make_log,
                                            rewake_level)


def test_rewake_level_and_single_context():
    assert CONTEXT == "all"                                             # 11/26: 상황을 나누지 않는다(사용자마다 사다리 하나)
    assert [rewake_level(k) for k in (1, 3, 5)] == [2, 4, 6] and rewake_level(9) == 6


def test_ladder_learn_counts_failures_below_and_success_at_received_level():
    lad = WakeLadder()
    assert lad.learn("short", 1, 3)
    assert lad.counts == {"short|1": [0, 1], "short|2": [0, 1], "short|3": [1, 0]}
    lad.learn("short", 3, 3)                                            # 시작 단계에서 바로 받음
    assert lad.get("short", 3) == (2, 0)
    lad.learn("ok", 1, None)                                            # 끝까지 못 받음: 1~5 모두 실패
    assert [lad.get("ok", lv) for lv in range(1, 6)] == [(0, 1)] * 5
    lad.learn("ok", 3, 4)                                               # 첫 반응 단계(4)가 성공, 그 아래(3)는 실패
    assert lad.get("ok", 3) == (0, 2) and lad.get("ok", 4) == (1, 1)


def test_snooze_rewake_and_unanswered_check_do_not_change_volume_learning():
    """11/26 보상 분리: 반응한 뒤의 미루기·재기상·확인 답변 여부(confirmed)는 볼륨 학습에 영향이 없다. 3단계에서 미루기를 눌러도 3단계 반응 성공."""
    a, b = WakeLadder(), WakeLadder()
    a.learn("ok", 1, 3)
    b.learn("ok", 1, 3, confirmed=False)                                # 옛 호출 방식: 값을 넘겨도 무시된다
    assert a.counts == b.counts and a.get("ok", 3) == (1, 0)
    c = WakeLadder()
    assert learn_wake(c, "ok", 3, 3, confirmed=False) and c.get("ok", 3) == (1, 0)


def test_ladder_rejects_bad_records_and_round_trips():
    lad = WakeLadder()
    assert not lad.learn("c", 0, 2) and not lad.learn("c", 3, 2) and not lad.learn("c", 2, 9) and lad.counts == {}
    lad.learn("c", 1, 2)
    again = WakeLadder.from_dict(lad.to_dict())
    assert again.counts == lad.counts and WakeLadder.from_dict(None).counts == {} and WakeLadder.from_dict({"counts": "bad"}).counts == {}
    assert WakeLadder({"a|1": [1, 2], "b|1": [-1, 0], "c|1": "x", "d|1": [True, 0]}).counts == {"a|1": [1, 2]}
    merged = WakeLadder.merge(lad, lad)
    assert merged.get("c", 1) == (0, 2) and merged.get("c", 2) == (2, 0) and lad.get("c", 1) == (0, 1)       # 원본은 그대로


def test_decision_is_valid_and_capped_for_a_new_user():
    d = decide_start_level(None, None, 480, rng=random.Random(1))
    assert d.recommended in CANDIDATES and d.applied == d.recommended <= START_CAP and d.source == "bandit"
    assert d.state == "EXPLORING" and d.context_key == CONTEXT and 0 < d.propensity <= 1 and d.raise_pending is False


def test_it_settles_on_the_level_that_works():
    lad = WakeLadder()
    for _ in range(25):
        lad.learn(CONTEXT, 1, 3)                                         # 1단계로는 못 받고 3단계에서 받는 사람
    picks = {decide_start_level(lad, None, 300, rng=random.Random(s), mc_samples=0).applied for s in range(30)}
    assert picks == {3}
    easy = WakeLadder()
    for _ in range(25):
        easy.learn(CONTEXT, 1, 1)
    assert {decide_start_level(easy, None, 480, rng=random.Random(s), mc_samples=0).applied for s in range(30)} == {1}
    assert decide_start_level(lad, None, 300, rng=random.Random(0)).state == "RECOMMENDED"


def test_no_difference_prefers_the_gentle_level():
    lad = WakeLadder()
    for _ in range(40):
        lad.add(CONTEXT, 1, False)
        lad.add(CONTEXT, 3, False)
        lad.add(CONTEXT, 1, True)
        lad.add(CONTEXT, 3, True)                                           # 어느 단계든 50% — 억지로 차이를 만들지 않는다
    picks = [decide_start_level(lad, None, 480, rng=random.Random(s), mc_samples=0).applied for s in range(40)]
    assert picks.count(1) > picks.count(3)


def test_user_priority_today_beats_max_beats_bandit_and_cap_is_overridable_by_user():
    lad = WakeLadder()
    for _ in range(25):
        lad.learn(CONTEXT, 1, 3)
    base = dict(sleep_minutes=480, rng=random.Random(0))
    d = decide_start_level(lad, None, prefs=UserWakePrefs(max_start_level=2), **base)
    assert d.recommended == 3 and d.applied == 2 and d.source == "user_max" and d.state == "USER" and d.propensity is None
    d = decide_start_level(lad, None, prefs=UserWakePrefs(max_start_level=2, next_alarm_level=5), **base)
    assert d.applied == 5 and d.source == "user_today" and d.recommended == 3          # 사용자가 정한 값은 상한(3)을 넘어도 된다
    d = decide_start_level(lad, None, prefs=UserWakePrefs(next_alarm_level=1), **base)
    assert d.applied == 1 and d.source == "user_today"


def test_raise_needs_notice_otherwise_previous_level_is_kept():
    lad = WakeLadder()
    for _ in range(25):
        lad.learn(CONTEXT, 1, 3)
    base = dict(sleep_minutes=480, previous_level=1, rng=random.Random(0), mc_samples=0)
    told = decide_start_level(lad, None, notified=True, **base)
    assert told.applied == 3 and told.raise_pending is True and told.source == "bandit"
    silent = decide_start_level(lad, None, notified=False, **base)
    assert silent.applied == 1 and silent.source == "hold_no_notice" and silent.recommended == 3 and silent.raise_pending is False and silent.propensity is None
    lower = decide_start_level(WakeLadder(), None, previous_level=3, notified=False, sleep_minutes=480, rng=random.Random(5), mc_samples=0)
    assert lower.source != "hold_no_notice" or lower.applied == 3                        # 낮추는 것은 알림 없이도 괜찮다
    same = decide_start_level(lad, None, previous_level=3, notified=False, sleep_minutes=480, rng=random.Random(0), mc_samples=0)
    assert same.applied == 3 and same.raise_pending is False


def test_important_day_never_explores():
    lad = WakeLadder()
    lad.learn(CONTEXT, 3, 3)
    outs = {decide_start_level(lad, None, 480, important_day=True, rng=random.Random(s)).explored for s in range(30)}
    assert outs == {False}
    assert decide_start_level(lad, None, 480, important_day=True, rng=random.Random(1)).source == "important_day"


def test_global_prior_is_weak():
    glob = WakeLadder()
    for _ in range(200):
        glob.learn(CONTEXT, 1, 1)                                           # 전체 사용자는 1단계로 충분
    mine = WakeLadder()
    for _ in range(30):
        mine.learn(CONTEXT, 1, 3)                                           # 그런데 나는 3단계가 필요
    assert {decide_start_level(mine, glob, 480, rng=random.Random(s), mc_samples=0).applied for s in range(30)} == {3}


def test_learning_from_user_chosen_days_and_log_row():
    lad = WakeLadder()
    d = decide_start_level(lad, None, 300, prefs=UserWakePrefs(next_alarm_level=4), rng=random.Random(0))
    assert learn_wake(lad, d.context_key, d.applied, 4) and lad.get(CONTEXT, 4) == (1, 0)
    row = make_log(d, 7, "2026-11-21", UserWakePrefs(next_alarm_level=4), previous_level=1).to_row()
    assert row["recommended_level"] == d.recommended and row["applied_level"] == 4 and row["source"] == "user_today"
    assert row["prefs"] == {"max_start_level": None, "next_alarm_level": 4} and row["user_id"] == "7" and row["previous_level"] == 1


def test_raise_announced_reads_the_transcript():
    said = "내일 아침 알람은 소리를 1단계 대신 3단계에서 시작해 볼게요. 너무 크게 느껴지시면 말씀해 주세요."
    assert raise_announced(said, 3) and not raise_announced(said, 2) and not raise_announced("오늘도 수고하셨어요", 3) and not raise_announced(None, 3)
    assert not raise_announced("알람은 13단계 소리", 3)          # 숫자의 일부는 세지 않는다
    assert not raise_announced("3단계 운동을 해요", 3)           # 알람·소리·볼륨 말이 없으면 아니다


def test_sleep_minutes_is_ignored_and_not_a_learning_context():
    """11/26: 수면 시간은 학습 상황이 아니다. 값을 넘겨도(옛 호출) 같은 사다리·같은 추천이 나온다."""
    lad = WakeLadder()
    for _ in range(25):
        lad.learn(CONTEXT, 1, 3)
    outs = [decide_start_level(lad, None, m, rng=random.Random(4), mc_samples=0) for m in (None, 200, 300, 480, 900)]
    assert {(o.context_key, o.recommended, o.applied) for o in outs} == {(CONTEXT, 3, 3)}
