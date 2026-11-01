from datetime import date, datetime, timezone

from backend.core.timeutil import KST, apply_date, record_date, today_kst


def test_record_date_late_night_and_after_midnight_are_same_night():
    assert record_date(datetime(2026, 10, 5, 23, 0, tzinfo=KST)) == date(2026, 10, 5)
    assert record_date(datetime(2026, 10, 6, 1, 0, tzinfo=KST)) == date(2026, 10, 5)


def test_record_date_boundary_is_noon():
    assert record_date(datetime(2026, 10, 6, 11, 59, tzinfo=KST)) == date(2026, 10, 5)
    assert record_date(datetime(2026, 10, 6, 12, 0, tzinfo=KST)) == date(2026, 10, 6)


def test_naive_time_is_kst_and_utc_is_converted():
    assert record_date(datetime(2026, 10, 6, 1, 0)) == date(2026, 10, 5)
    # UTC 15:00 = KST 다음날 00:00
    assert record_date(datetime(2026, 10, 5, 15, 0, tzinfo=timezone.utc)) == date(2026, 10, 5)
    assert today_kst(datetime(2026, 10, 5, 15, 0, tzinfo=timezone.utc)) == date(2026, 10, 6)


def test_apply_date_is_next_day():
    assert apply_date(date(2026, 10, 31)) == date(2026, 11, 1)
