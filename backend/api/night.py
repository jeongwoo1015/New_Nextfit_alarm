"""밤 흐름: 굿나잇콜 토큰, 대화 원문, 밤 기록, 목소리."""
from datetime import date

from fastapi import APIRouter, BackgroundTasks, Depends, Request, status

from backend.app.deps import night_service
from backend.clients.ai_gateway import CalendarEvent
from backend.core.auth import current_user_id
from backend.domain.models import NightRecord
from backend.services.night_service import NightService

from .schemas import LiveTokenOut, NightRecordOut, NightTokenIn, TranscriptIn

router = APIRouter(prefix="/night", tags=["night"])


def _record(svc: NightService, rec: NightRecord) -> NightRecordOut:
    out = NightRecordOut.model_validate(rec)
    out.voice_url = svc.voice_url(rec)
    return out


@router.post("/token", response_model=LiveTokenOut)
def night_token(body: NightTokenIn, user_id: str = Depends(current_user_id), svc: NightService = Depends(night_service)):
    """굿나잇콜 시작 전. 서버가 지시문(호칭·말투, 오늘·내일 일정, 내일 알람·루틴 항목)을 고정한 1회용 토큰을 준다."""
    to_events = lambda xs: [CalendarEvent(e.title, e.start, e.end, e.all_day) for e in xs]
    return svc.issue_token(user_id, to_events(body.events_today), to_events(body.events_tomorrow))


@router.post("/transcript", response_model=NightRecordOut, status_code=status.HTTP_202_ACCEPTED)
def save_transcript(body: TranscriptIn, tasks: BackgroundTasks, user_id: str = Depends(current_user_id),
                    svc: NightService = Depends(night_service)):
    """통화 종료 후 받아쓰기를 보낸다. 응답은 바로(EXTRACT_STATUS = PENDING), 추출은 백그라운드.
    결과는 GET /night/records/{night_record_id}로 확인한다."""
    rec = svc.save_transcript(user_id, [t.model_dump() for t in body.transcript], body.started_at, body.ended_at)
    tasks.add_task(svc.extract, user_id, rec.night_record_id)
    return _record(svc, rec)


@router.get("/records/{night_record_id}", response_model=NightRecordOut)
def get_record(night_record_id: str, user_id: str = Depends(current_user_id), svc: NightService = Depends(night_service)):
    return _record(svc, svc.get_record(user_id, night_record_id))


@router.get("/records", response_model=NightRecordOut)
def get_record_by_date(record_date: date, user_id: str = Depends(current_user_id), svc: NightService = Depends(night_service)):
    """밤 날짜(RECORD_DATE)로 조회 — 일기 화면."""
    return _record(svc, svc.get_record_by_date(user_id, record_date))


@router.put("/records/{night_record_id}/voice", response_model=NightRecordOut,
            openapi_extra={"requestBody": {"content": {"audio/mp4": {"schema": {"type": "string", "format": "binary"}}}}})
async def put_voice(night_record_id: str, request: Request, user_id: str = Depends(current_user_id),
                    svc: NightService = Depends(night_service)):
    """목소리 파일(m4a)을 본문 그대로 보낸다 (Content-Type: audio/mp4). 다시 올리면 덮어쓴다."""
    return _record(svc, svc.save_voice(user_id, night_record_id, await request.body()))
