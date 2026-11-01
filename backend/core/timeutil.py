"""KST 기준 시각·날짜 (정의서 공통 규칙: 날짜는 모두 KST)."""
from datetime import date, datetime, timedelta, timezone

KST = timezone(timedelta(hours=9), "KST")


def now_kst() -> datetime:
    return datetime.now(KST)


def to_kst(dt: datetime) -> datetime:
    """시간대가 없는 시각은 KST로 본다."""
    return dt.replace(tzinfo=KST) if dt.tzinfo is None else dt.astimezone(KST)


def today_kst(now: datetime | None = None) -> date:
    return to_kst(now or now_kst()).date()


def record_date(saved_at: datetime) -> date:
    """RECORD_DATE(밤 날짜) = 저장 시각(KST) − 12시간의 날짜. 10/5 23시와 10/6 01시는 모두 10/5 밤.

    DB 트리거가 최종 값을 정한다. 서버는 조회 조건을 만들 때만 같은 규칙을 쓴다.
    """
    return (to_kst(saved_at) - timedelta(hours=12)).date()


def apply_date(record: date) -> date:
    """APPLY_DATE(아침 적용일) = RECORD_DATE + 1일."""
    return record + timedelta(days=1)
