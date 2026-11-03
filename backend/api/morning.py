"""아침 흐름: 모닝 세션, 재기상, 브리핑·코칭 토큰, 단계 판정·체크."""
from fastapi import APIRouter, Depends

from backend.app.deps import morning_service
from backend.clients.ai_gateway import CalendarEvent
from backend.core.auth import current_user_id
from backend.services.morning_service import Location, MorningService, SessionView

from .schemas import (BriefingTokenIn, CoachingTokenOut, LiveTokenOut, ProgressOut, RewakeOut, SessionOut, SessionStartIn,
                      SessionViewOut, SnoozeOut, StepDurationIn, StepOut, StepStatusIn, VerdictIn)

router = APIRouter(prefix="/morning", tags=["morning"])


def _view(v: SessionView) -> SessionViewOut:
    return SessionViewOut(session=SessionOut.model_validate(v.session), steps=[StepOut.model_validate(s) for s in v.steps],
                          progress=ProgressOut.model_validate(v.progress))


@router.post("/sessions", response_model=SessionViewOut)
def start_session(body: SessionStartIn, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """알람이 울릴 때. 오늘 그 알람의 세션을 만들거나(그날 값 스냅샷, 하루 한정 변경 반영) 이미 있으면 그대로 돌려준다."""
    return _view(svc.start(user_id, body.alarm_id))


@router.get("/sessions/today", response_model=SessionViewOut)
def today_session(user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """루틴 화면 "오늘의 체크리스트" — 오늘 가장 최근 세션과 진행 개수."""
    return _view(svc.today(user_id))


@router.get("/sessions/{session_id}", response_model=SessionViewOut)
def get_session(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    return _view(svc.view(user_id, session_id))


@router.post("/sessions/{session_id}/rewake", response_model=RewakeOut)
def rewake(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """8초 무응답으로 재기상(AI) 음악을 틀 때마다 호출. 확인 멘트를 마치기 전이면 기상 실패가 된다."""
    return RewakeOut(rewake_count=svc.rewake(user_id, session_id))


@router.post("/sessions/{session_id}/snooze", response_model=SnoozeOut)
def snooze(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """"5분 뒤 다시 울리기"를 누를 때마다 호출 (횟수 제한 없음). 5분 뒤 다시 울리면 POST /morning/sessions로 같은 세션을 받는다.
    한 번이라도 하면 그날은 기상 실패."""
    return SnoozeOut(snooze_count=svc.snooze(user_id, session_id))


@router.post("/sessions/{session_id}/wake-check", response_model=SessionViewOut)
def wake_check(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """브리핑 전 확인 멘트(2~3개)를 모두 마쳤을 때 — 브리핑 Live의 wake_check_done을 앱이 전달.
    기상 성공 = 스누즈 0회 + 그때까지 재기상 음악 0회 + 첫 응답 있음. 처음 한 번만 판정한다."""
    return _view(svc.wake_check(user_id, session_id))


@router.post("/sessions/{session_id}/response", response_model=SessionOut)
def first_response(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """알람 후 사용자의 첫 음성 응답 (처음 한 번만 기록, 반응 시간 계산용)."""
    return svc.respond(user_id, session_id)


@router.post("/sessions/{session_id}/briefing-token", response_model=LiveTokenOut)
def briefing_token(session_id: str, body: BriefingTokenIn, user_id: str = Depends(current_user_id),
                   svc: MorningService = Depends(morning_service)):
    """브리핑 Live 토큰. 그날 처음 호출할 때 개인화 전략을 정해 로그에 남긴다(재연결은 같은 전략).
    location을 보내면 그 위치의 오늘 날씨를 브리핑에 넣는다(실패하면 날씨 없이)."""
    events = [CalendarEvent(e.title, e.start, e.end, e.all_day) for e in body.events_today]
    loc = Location(body.location.lat, body.location.lon, body.location.name.strip()) if body.location else None
    return svc.briefing_token(user_id, session_id, events, loc)


@router.post("/sessions/{session_id}/coaching-token", response_model=CoachingTokenOut)
def coaching_token(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """브리핑에서 start_routine이 오면 호출(재연결도 같음). 처음이면 단계를 PENDING으로 만든다.
    코칭을 건너뛰는 날은 409 COACHING_SKIPPED, 모두 체크했으면 409 ALL_STEPS_CHECKED."""
    token, steps = svc.coaching_token(user_id, session_id)
    return CoachingTokenOut(**token.__dict__, steps=[StepOut.model_validate(s) for s in steps])


@router.post("/sessions/{session_id}/steps/{step_order}/verdict", response_model=SessionViewOut)
def report_verdict(session_id: str, step_order: int, body: VerdictIn, user_id: str = Depends(current_user_id),
                   svc: MorningService = Depends(morning_service)):
    """Live 함수 호출 report_step_result를 앱이 그대로 전달 (앱은 {"result": "ok"}로 AI에 응답)."""
    return _view(svc.report_verdict(user_id, session_id, step_order, body.verdict))


@router.patch("/sessions/{session_id}/steps/{step_order}", response_model=SessionViewOut)
def mark_step(session_id: str, step_order: int, body: StepStatusIn, user_id: str = Depends(current_user_id),
              svc: MorningService = Depends(morning_service)):
    """체크리스트에서 직접 체크(DONE)·건너뛰기(SKIPPED)·해제(PENDING)."""
    return _view(svc.mark_step(user_id, session_id, step_order, body.step_status))


@router.patch("/sessions/{session_id}/steps/{step_order}/duration", response_model=SessionViewOut)
def change_step_duration(session_id: str, step_order: int, body: StepDurationIn, user_id: str = Depends(current_user_id),
                         svc: MorningService = Depends(morning_service)):
    """오늘 진행 중 변경 — 코칭 중 "오늘 스트레칭 좀 적게"(Live 함수를 앱이 전달). 알람 설정·내일 값은 그대로.
    PENDING 단계만, 30초 단위, 오늘 합계 ≤ 상한. 0초는 오늘만 빼기(SKIPPED)."""
    return _view(svc.change_step_duration(user_id, session_id, step_order, body.duration_sec))


@router.post("/sessions/{session_id}/end", response_model=SessionViewOut)
def end_session(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """end_session을 받았거나 사용자가 통화를 끊었을 때. 남은 단계는 PENDING으로 둔다.
    확인 멘트를 끝내지 못한 채(wake-check 없이) 끝나면 기상 실패로 남는다."""
    return _view(svc.end(user_id, session_id))
