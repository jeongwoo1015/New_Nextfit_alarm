"""User Model(기록 형식·통계·보상) 테스트. 정의서(10/31수정) 컬럼명 기준, Gemini 호출 없음."""
from datetime import date, datetime, time, timedelta, timezone

import pytest

from ai.personalization.records import (DONE, KST, PENDING, SKIPPED, SessionRecord, StepRecord, build_sessions)
from ai.personalization.reward import RewardConfig, session_reward
from ai.personalization.user_model import (DEFAULT_STATS, MAX_GAP_DAYS, MIN_SESSIONS, clock_str, compute_stats,
                                           compute_streaks, day_summary, period_series)

TODAY = date(2026, 11, 10)   # 화요일


def step(status=DONE, tid="t1", name="물 한 잔", order=1):
    return StepRecord(step_name=name, status=status, step_order=order, template_item_id=tid)


def sess(day, ok=True, steps=(), cond=None, resp=None, end=None, rewake=0, coaching=20, **kw):
    d = date(2026, 11, day) if isinstance(day, int) else day
    return SessionRecord(session_date=d, wake_success=ok, steps=list(steps), condition_code=cond, coaching_min=coaching,
                         first_response_at=resp, session_end_at=end, rewake_count=rewake, **kw)


def at(day, hh, mm, tz=KST):
    return datetime(2026, 11, day, hh, mm, tzinfo=tz)


# ---------------- records: 정의서 컬럼 변환 ----------------
def test_session_from_rows_uses_spec_columns():
    row = {"session_id": "s1", "user_id": "u1", "session_date": "2026-11-09", "morning_call_type": "routine", "coaching_min": 10,
           "target_wake_time": "07:00:00", "first_response_at": "2026-11-08T21:42:00+00:00", "session_end_at": "2026-11-08T21:54:00+00:00",
           "rewake_count": 1, "wake_success": True}
    steps = [{"session_id": "s1", "step_order": 2, "step_name": "스트레칭", "template_item_id": "t2", "step_status": "skipped",
              "planned_duration_sec": 120, "checked_at": "2026-11-08T21:50:00Z", "ai_verdict": "NOT_DONE"},
             {"session_id": "s1", "step_order": 1, "step_name": "물 한 잔", "template_item_id": "t1", "step_status": "DONE"}]
    s = SessionRecord.from_rows(row, steps, {"condition_label": "tired"})
    assert s.session_date == date(2026, 11, 9) and s.call_type == "ROUTINE" and s.coaching_min == 10 and s.target_wake_time == time(7, 0)
    assert s.condition_code == "TIRED" and s.rewake_count == 1 and s.wake_success is True
    assert [x.step_order for x in s.steps] == [1, 2]                      # 순서대로 정렬
    assert s.steps[1].status == SKIPPED and s.steps[1].ai_verdict == "not_done" and s.steps[1].planned_sec == 120
    assert s.wake_clock == time(6, 42) and s.wake_minutes == 6 * 60 + 42    # UTC 21:42 = KST 06:42
    assert s.talk_minutes == pytest.approx(12.0) and s.steps_done == 1


def test_records_tolerate_missing_and_bad_values():
    s = SessionRecord.from_rows({}, None, None)
    assert s.session_date is None and s.coaching_min is None and s.rewake_count == 0 and s.wake_success is False and s.steps == []
    assert s.wake_clock is None and s.reaction_sec is None and s.talk_minutes is None
    bad = StepRecord.from_row({"step_status": "weird", "template_item_id": "", "step_order": "x"})
    assert bad.status == PENDING and bad.template_item_id is None and bad.step_order is None
    assert SessionRecord.from_rows({"session_date": "garbage", "rewake_count": "x"}, [], {"condition_label": "SAD"}).condition_code is None
    assert StepRecord.from_row(None).status == PENDING


def test_content_steps_have_no_item_key():
    assert StepRecord.from_row({"step_name": "퀴즈 1번", "step_status": "DONE"}).item_key is None
    assert StepRecord.from_row({"step_name": "x", "template_item_id": "abc"}).item_key == "abc"


def test_naive_times_are_treated_as_kst():
    s = SessionRecord.from_rows({"session_date": "2026-11-09", "first_response_at": "2026-11-09T06:30:00"})
    assert s.wake_clock == time(6, 30)


def test_reaction_time_needs_ring_time_or_on_time_alarm():
    base = dict(session_date=date(2026, 11, 9), target_wake_time=time(7, 0), first_response_at=at(9, 7, 2))
    assert SessionRecord(**base).reaction_sec is None                                    # 알람이 울린 시각을 알 수 없다
    assert SessionRecord(**base, alarm_mode="ON_TIME").reaction_sec == 120               # 정각 알람이면 목표 시각 기준
    assert SessionRecord(**base, alarm_mode="BEFORE_10").reaction_sec is None            # 미리 울리는 알람은 알 수 없다
    assert SessionRecord(**base, alarm_rang_at=at(9, 6, 59)).reaction_sec == 180          # 울린 시각 컬럼이 생기면 그걸 쓴다
    early = SessionRecord(session_date=date(2026, 11, 9), target_wake_time=time(7, 0), first_response_at=at(9, 6, 50), alarm_mode="ON_TIME")
    assert early.reaction_sec is None                                                    # 음수는 버린다


def test_build_sessions_joins_by_session_id_and_apply_date():
    sessions = [{"session_id": "a", "user_id": "u1", "session_date": "2026-11-09", "wake_success": True},
                {"session_id": "b", "user_id": "u1", "session_date": "2026-11-08", "wake_success": False},
                {"session_id": "c", "user_id": "u2", "session_date": "2026-11-09", "wake_success": True}]
    steps = [{"session_id": "a", "step_name": "x", "step_status": "DONE", "step_order": 1},
             {"session_id": "a", "step_name": "y", "step_status": "SKIPPED", "step_order": 2},
             {"session_id": "zzz", "step_name": "다른 세션", "step_status": "DONE"}]
    nights = [{"user_id": "u1", "apply_date": "2026-11-09", "condition_label": "GOOD"},
              {"user_id": "u2", "apply_date": "2026-11-09", "condition_label": "STRESSED"}]
    out = {s.session_id: s for s in build_sessions(sessions, steps, nights)}
    assert [x.step_name for x in out["a"].steps] == ["x", "y"] and out["b"].steps == [] and out["c"].steps == []
    assert out["a"].condition_code == "GOOD" and out["c"].condition_code == "STRESSED"   # 다른 사용자의 밤 기록이 섞이지 않는다
    assert out["b"].condition_code is None                                                # 밤 기록이 없는 날
    assert build_sessions(None) == [] and build_sessions([], None, None) == []


def test_build_sessions_night_row_without_user_id_matches_by_date():
    out = build_sessions([{"session_id": "a", "user_id": "u1", "session_date": "2026-11-09"}], [], [{"apply_date": "2026-11-09", "condition_label": "TIRED"}])
    assert out[0].condition_code == "TIRED"


# ---------------- user_model: 통계 ----------------
def test_empty_gives_defaults():
    assert compute_stats([], TODAY) is DEFAULT_STATS or compute_stats([], TODAY) == DEFAULT_STATS
    s = compute_stats(None, TODAY)
    assert s.n_sessions == 0 and s.wake_success_rate is None and not s.has_enough_data and s.current_streak == 0


def test_window_boundaries_exclude_today_and_include_seven_days_ago():
    sessions = [sess(10), sess(9), sess(3), sess(2)]          # today=11/10 → 11/3~11/9 만 포함
    assert compute_stats(sessions, TODAY).n_sessions == 2
    assert compute_stats(sessions, TODAY, include_today=True).n_sessions == 3
    assert compute_stats(sessions, TODAY, window_days=8).n_sessions == 3
    assert compute_stats(sessions).n_sessions == 4              # today=None 이면 전부


def test_enough_data_boundary():
    two = [sess(8), sess(9)]
    three = two + [sess(7)]
    assert MIN_SESSIONS == 3
    assert not compute_stats(two, TODAY).has_enough_data and compute_stats(three, TODAY).has_enough_data


def test_wake_rate_rewake_and_last_condition():
    s = compute_stats([sess(7, ok=True, rewake=0, cond="GOOD"), sess(8, ok=False, rewake=2, cond="NORMAL"), sess(9, ok=True, rewake=1, cond="TIRED")], TODAY)
    assert s.wake_success_rate == pytest.approx(2 / 3) and s.avg_rewake == pytest.approx(1.0)
    assert s.last_condition == "TIRED"                                  # 가장 최근 날짜의 값


def test_step_counts_and_ratios_match_ui_numbers():
    a = sess(8, steps=[step(DONE, "t1", order=1), step(DONE, "t2", "스트레칭", 2), step(SKIPPED, "t3", "독서", 3), step(PENDING, "t4", "일기", 4)])
    b = sess(9, steps=[step(DONE, "t1", order=1), step(SKIPPED, "t2", "스트레칭", 2)])
    s = compute_stats([a, b, sess(7)], TODAY)
    assert (s.routine_done, s.routine_total) == (3, 6) and s.routine_completion_rate == pytest.approx(0.5)   # 화면의 'n / m'과 같은 정의(PENDING은 분모에 포함)
    assert s.skip_ratio == pytest.approx(2 / 5)                                                              # 건너뜀 2 ÷ (수행 3 + 건너뜀 2), PENDING은 제외
    assert s.routine_rates == {"t1": 1.0, "t2": 0.5, "t3": 0.0} and s.routine_counts == {"t1": 2, "t2": 2, "t3": 1}   # t4는 PENDING뿐이라 없음


def test_content_steps_count_in_totals_but_not_in_item_rates():
    quiz = [step(DONE, None, "퀴즈 1번", 1), step(DONE, None, "퀴즈 2번", 2), step(SKIPPED, None, "퀴즈 3번", 3)]
    s = compute_stats([sess(9, steps=quiz)], TODAY)
    assert (s.routine_done, s.routine_total) == (2, 3) and s.routine_rates == {} and s.skip_ratio == pytest.approx(1 / 3)


def test_coaching_zero_day_has_no_steps_but_counts_for_wake():
    s = compute_stats([sess(9, ok=True, steps=[], coaching=0), sess(8, ok=False, steps=[step(DONE)])], TODAY)
    assert s.n_sessions == 2 and s.wake_success_rate == 0.5 and (s.routine_done, s.routine_total) == (1, 1)


def test_no_steps_at_all_gives_none_rates():
    s = compute_stats([sess(9)], TODAY)
    assert s.routine_completion_rate is None and s.skip_ratio is None and s.routine_rates == {}


def test_pending_only_does_not_make_skip_ratio():
    s = compute_stats([sess(9, steps=[step(PENDING)])], TODAY)
    assert s.skip_ratio is None and s.routine_completion_rate == 0.0 and s.routine_total == 1


def test_wake_clock_average_and_talk_time():
    sessions = [sess(7, resp=at(7, 6, 0), end=at(7, 6, 10)), sess(8, resp=at(8, 7, 0), end=at(8, 7, 20)), sess(9)]
    s = compute_stats(sessions, TODAY)
    assert s.avg_wake_minutes == pytest.approx(6 * 60 + 30) and s.avg_wake_clock == "06:30" and s.avg_talk_min == pytest.approx(15.0)
    assert s.avg_reaction_sec is None                                     # 알람이 울린 시각을 모르면 비어 있다
    assert clock_str(None) is None and clock_str(405) == "06:45" and clock_str(0) == "00:00"


def test_to_dict_is_plain():
    d = compute_stats([sess(9, steps=[step()], resp=at(9, 6, 45))], TODAY).to_dict()
    assert d["avg_wake_clock"] == "06:45" and d["routine_done"] == 1 and isinstance(d["routine_rates"], dict)


# ---------------- 연속 달성일 ----------------
def test_streak_basic_and_break():
    days = [sess(d, ok=ok) for d, ok in [(1, True), (2, True), (3, True), (4, False), (5, True), (6, True)]]
    assert compute_streaks(days) == (2, 3)                                # 현재 2(5,6), 최장 3(1~3)
    assert compute_streaks([sess(1, ok=True), sess(2, ok=False)]) == (0, 1)   # 마지막 날 실패면 현재는 0


def test_streak_skips_days_without_sessions_within_gap():
    weekday_only = [sess(6), sess(9), sess(10)]                           # 금(6) → 월(9): 주말(세션 없음)은 끊지 않는다
    assert (date(2026, 11, 9) - date(2026, 11, 6)).days <= MAX_GAP_DAYS
    assert compute_streaks(weekday_only, TODAY) == (3, 3)
    assert compute_streaks([sess(1), sess(1 + MAX_GAP_DAYS + 1)]) == (1, 1)    # 간격이 길면 끊긴다


def test_streak_any_success_in_day_counts_and_upto_cuts_future():
    two_alarms = [sess(5, ok=False), sess(5, ok=True), sess(6, ok=True)]
    assert compute_streaks(two_alarms) == (2, 2)
    assert compute_streaks([sess(5), sess(6), sess(9)], upto=date(2026, 11, 6)) == (2, 2)
    assert compute_streaks([sess(1)], upto=date(2026, 11, 20))[0] == 0     # 마지막 세션이 너무 오래 전이면 현재 연속은 0


def test_streak_in_stats_uses_all_history_not_window():
    long_run = [sess(d) for d in range(1, 10)]                            # 11/1~11/9 모두 성공
    s = compute_stats(long_run, TODAY)
    assert s.n_sessions == 7 and s.current_streak == 9 and s.best_streak == 9      # 통계는 최근 7일, 연속은 전체
    s2 = compute_stats(long_run + [sess(10)], TODAY, include_today=True)
    assert s2.current_streak == 10 and compute_stats(long_run + [sess(10, ok=False)], TODAY).current_streak == 9   # 오늘 결과는 기본으로 빼서 센다


# ---------------- 일별·주·월·연 집계 ----------------
def test_day_summary_matches_diary_screen():
    sessions = [sess(9, resp=at(9, 6, 42), steps=[step(DONE, "a", order=1)] * 6 + [step(SKIPPED, "b", order=7)])]
    d = day_summary(sessions, date(2026, 11, 9))
    assert d.avg_wake_clock == "06:42" and (d.routine_done, d.routine_total) == (6, 7) and d.routine_completion_rate == pytest.approx(6 / 7)
    empty = day_summary(sessions, date(2026, 11, 8))
    assert empty.n_sessions == 0 and empty.routine_completion_rate is None and empty.avg_wake_clock is None


def test_week_series_has_seven_monday_first_buckets():
    sessions = [sess(9, resp=at(9, 6, 0)), sess(11, ok=False), sess(15, resp=at(15, 8, 0))]    # 11/9=월, 11/11=수, 11/15=일, anchor 11/12(목)
    buckets = period_series(sessions, "week", date(2026, 11, 12))
    assert [b.label for b in buckets] == list("월화수목금토일") and buckets[0].start == date(2026, 11, 9) and buckets[-1].end == date(2026, 11, 16)
    assert buckets[0].wake_success_rate == 1.0 and buckets[0].avg_wake_clock == "06:00"
    assert buckets[2].wake_success_rate == 0.0 and buckets[1].n_sessions == 0 and buckets[1].wake_success_rate is None
    assert buckets[6].avg_wake_clock == "08:00"


def test_month_series_buckets_cover_the_month_exactly():
    buckets = period_series([sess(1), sess(8), sess(30)], "month", date(2026, 11, 15))
    assert [b.label for b in buckets] == ["1주", "2주", "3주", "4주", "5주"]
    assert buckets[0].start == date(2026, 11, 1) and buckets[-1].end == date(2026, 12, 1)
    assert [b.n_sessions for b in buckets] == [1, 1, 0, 0, 1]
    feb = period_series([], "month", date(2026, 2, 3))                       # 28일 → 4칸
    assert len(feb) == 4 and feb[-1].end == date(2026, 3, 1)
    dec = period_series([], "month", date(2026, 12, 31))
    assert dec[-1].end == date(2027, 1, 1)


def test_year_series_twelve_months():
    buckets = period_series([sess(date(2026, 1, 5)), sess(date(2026, 12, 31), ok=False), sess(date(2025, 12, 31))], "year", date(2026, 6, 1))
    assert [b.label for b in buckets] == [f"{m}월" for m in range(1, 13)]
    assert buckets[0].n_sessions == 1 and buckets[11].wake_success_rate == 0.0 and sum(b.n_sessions for b in buckets) == 2   # 작년 기록은 제외
    with pytest.raises(ValueError):
        period_series([], "decade", TODAY)


# ---------------- reward ----------------
def test_reward_cases():
    steps = [step(DONE, order=1), step(DONE, order=2), step(SKIPPED, order=3), step(PENDING, order=4)]
    assert session_reward(sess(9, ok=True, steps=steps)) == pytest.approx((1 + 0.5) / 2)
    assert session_reward(sess(9, ok=False, steps=steps)) == pytest.approx(0.25)
    assert session_reward(sess(9, ok=True, steps=[step(DONE)])) == 1.0
    assert session_reward(sess(9, ok=False, steps=[step(SKIPPED)])) == 0.0


def test_reward_without_steps_uses_wake_only():
    assert session_reward(sess(9, ok=True, steps=[], coaching=0)) == 1.0
    assert session_reward(sess(9, ok=False, steps=[], coaching=0)) == 0.0


def test_reward_config_and_clamp():
    s = sess(9, ok=True, steps=[step(SKIPPED)], rewake=3)
    assert session_reward(s, RewardConfig(wake_weight=3, routine_weight=1)) == pytest.approx(0.75)
    assert session_reward(s, RewardConfig(rewake_penalty=0.5)) == 0.0                 # 감점으로 음수가 되면 0
    assert session_reward(sess(9, ok=True, steps=[step(DONE)]), RewardConfig(rewake_penalty=-5)) == 1.0   # 1을 넘지 않는다
    assert session_reward(sess(9, ok=True, steps=[step()]), RewardConfig(wake_weight=0, routine_weight=0)) == 1.0   # 가중치가 0이어도 오류 없음
