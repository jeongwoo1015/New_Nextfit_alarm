"""기록 화면: 리포트, 한 줄 일기 캘린더·상세, 일기 사진."""
from datetime import date

from fastapi import APIRouter, Depends, Query, Request

from backend.app.deps import history_service
from backend.core.auth import current_user_id
from backend.services.history_service import HistoryService

from .schemas import (CalendarDayOut, DiaryDayOut, DiarySessionOut, NightRecordOut, PhotoOut, ReportOut, SessionOut,
                      StepOut, TotalsOut)

router = APIRouter(tags=["history"])


@router.get("/reports", response_model=ReportOut)
def get_report(period: str = Query("week", pattern="^(week|month|year)$"), date_: date | None = Query(None, alias="date"),
               user_id: str = Depends(current_user_id), svc: HistoryService = Depends(history_service)):
    """리포트 화면: 상단은 전체 기간 누적(overall), 그래프와 그 영역 합계는 date가 속한 주·월·연(totals·series, 기본 오늘),
    현재·최장 연속 달성일."""
    return svc.report(user_id, period, date_)


@router.get("/diary/calendar", response_model=list[CalendarDayOut])
def get_calendar(month: str = Query(..., pattern=r"^\d{4}-(0[1-9]|1[0-2])$", description="YYYY-MM"),
                 user_id: str = Depends(current_user_id), svc: HistoryService = Depends(history_service)):
    """한 줄 일기 캘린더: 그달 날짜별 사진 URL 또는 루틴 성공률."""
    year, mon = map(int, month.split("-"))
    return [CalendarDayOut(date=d.day, photo_url=d.photo_url, routine_completion_rate=d.summary.routine_completion_rate,
                           routine_done=d.summary.routine_done, routine_total=d.summary.routine_total,
                           has_session=d.summary.n_sessions > 0, has_night_record=d.has_night_record)
            for d in svc.calendar(user_id, year, mon)]


@router.get("/diary/{day}", response_model=DiaryDayOut)
def get_diary(day: date, user_id: str = Depends(current_user_id), svc: HistoryService = Depends(history_service)):
    """날짜를 누르면: 그날 밤 기록(한 줄 일기)·아침 세션·사진·하루 요약. 파일이 없으면 URL만 null."""
    d = svc.diary(user_id, day)
    night = None
    if d.night:
        night = NightRecordOut.model_validate(d.night)
        night.voice_url = d.voice_url
    return DiaryDayOut(date=d.day, summary=TotalsOut.model_validate(d.summary), night=night,
                       sessions=[DiarySessionOut(session=SessionOut.model_validate(s), steps=[StepOut.model_validate(x) for x in st])
                                 for s, st in d.sessions],
                       photo_url=d.photo_url)


@router.put("/diary/{day}/photo", response_model=PhotoOut,
            openapi_extra={"requestBody": {"content": {"image/jpeg": {"schema": {"type": "string", "format": "binary"}}}}})
async def put_photo(day: date, request: Request, user_id: str = Depends(current_user_id),
                    svc: HistoryService = Depends(history_service)):
    """일기 대표 사진(jpg)을 본문 그대로 보낸다 (Content-Type: image/jpeg, 10MB 이하). 다시 올리면 덮어쓴다."""
    return PhotoOut(date=day, photo_url=svc.upload_photo(user_id, day, await request.body(), request.headers.get("content-type")))
