"""아침 흐름: 모닝 세션, 재기상, 브리핑·코칭 토큰, 단계 판정·체크."""
from fastapi import APIRouter, BackgroundTasks, Depends

from backend.app.deps import morning_service
from backend.clients.ai_gateway import CalendarEvent
from backend.core.auth import current_user_id
from backend.services.morning_service import Location, MorningService, SessionView

from .schemas import (BedtimeIn, BriefingTokenIn, CoachingTokenOut, FortuneTokenOut, LiveTokenOut, ProgressOut, RewakeOut, SessionOut, SessionStartIn,
                      GameScoreIn, GameScoreOut, SessionViewOut, SnoozeIn, SnoozeOut, WakeLogOut, WakeVolumeIn, StepDurationIn, StepOut, StepStatusIn, StretchAreaIn, StretchStepOut, VerdictIn)

router = APIRouter(prefix="/morning", tags=["morning"])


def wake_log_out(row: dict) -> WakeLogOut:
    return WakeLogOut(**{k: row.get(k) for k in WakeLogOut.model_fields if k != "user_set"},
                      user_set=str(row.get("source") or "").startswith("user"))


def _view(v: SessionView) -> SessionViewOut:
    routines = ({area: [StretchStepOut.model_validate(p) for p in plans] for area, plans in v.stretch_routines.items()}
                if v.stretch_routines else None)
    return SessionViewOut(session=SessionOut.model_validate(v.session), steps=[StepOut.model_validate(s) for s in v.steps],
                          progress=ProgressOut.model_validate(v.progress),
                          morning_plan=list(v.morning_plan) if v.morning_plan is not None else None, stretch_routines=routines)


@router.post("/sessions", response_model=SessionViewOut)
def start_session(body: SessionStartIn, tasks: BackgroundTasks, user_id: str = Depends(current_user_id),
                  svc: MorningService = Depends(morning_service)):
    """알람이 울릴 때. 오늘 그 알람의 세션을 만들거나(그날 값 스냅샷, 하루 한정 변경 반영) 이미 있으면 그대로 돌려준다.
    오늘의 포춘 문장은 응답 뒤 백그라운드에서 만들어 둔다(브리핑 동안 준비, 알람 응답을 늦추지 않음)."""
    v = svc.start(user_id, body.alarm_id)
    if not v.session.fortune_text:
        tasks.add_task(svc.prepare_fortune_quietly, user_id, v.session.session_id)
    tasks.add_task(svc.learn_past_strategies, user_id)     # 끝내지 못한 지난 세션의 코칭 강도 학습
    return _view(v)


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
def snooze(session_id: str, body: SnoozeIn | None = None, user_id: str = Depends(current_user_id),
           svc: MorningService = Depends(morning_service)):
    """미루기. 버튼 "5분 뒤 다시 울리기"는 본문 없이, 말로 미루기(AI reschedule_alarm)는 {"minutes_later": 10} 또는 {"at_time": "09:00"}.
    앱은 응답의 snoozed_until에 알람을 한 번 다시 예약하고, 다시 울리면 POST /morning/sessions로 같은 세션을 받는다.
    버튼·말 합쳐 알람(세션)마다 3번까지(넘으면 409 SNOOZE_LIMIT), at_time이 지났거나 60분 뒤면 422 SNOOZE_TIME_INVALID.
    한 번이라도 하면 그날은 기상 실패. 3번 다 미룬 알람이 다시 울리면 앱이 자동 연결하고, 서버는 그 세션을 auto_connected로 남긴다."""
    body = body or SnoozeIn()
    r = svc.snooze(user_id, session_id, body.minutes_later, body.at_time)
    return SnoozeOut(snooze_count=r.snooze_count, snoozes_today=r.snoozes_today, snoozed_until=r.snoozed_until)


@router.post("/sessions/{session_id}/wake-volume", response_model=WakeLogOut)
def report_wake_volume(session_id: str, body: WakeVolumeIn, user_id: str = Depends(current_user_id),
                       svc: MorningService = Depends(morning_service)):
    """기상 볼륨 결과: 전화를 받은 직후 또는 미루기 버튼을 누른 직후 한 번. received_level은 그 순간의 단계(미루기도 null 아님).
    그날 처음 보고한 세션만 기록하고(다시 울림·다른 알람은 무시), 확인 질문을 마치거나 세션이 끝나면 서버가 학습한다.
    미룬 날은 받은 단계도 실패로 보고 바로 학습하고, 반응 없이 꺼졌으면(null) 바로 학습."""
    return wake_log_out(svc.report_wake_volume(user_id, session_id, body.start_level, body.received_level))


@router.post("/sessions/{session_id}/wake-check", response_model=SessionViewOut)
def wake_check(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """브리핑 전 확인 멘트(2~3개)를 모두 마쳤을 때 — 브리핑 Live의 wake_check_done을 앱이 전달.
    기상 성공 = 스누즈 0회 + 그때까지 재기상 음악 0회 + 첫 응답 있음. 처음 한 번만 판정한다."""
    return _view(svc.wake_check(user_id, session_id))


@router.post("/sessions/{session_id}/complete", response_model=SessionViewOut)
def complete_flow(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """포춘까지 모닝콜의 모든 순서를 끝까지 마쳤을 때 한 번(중간에 통화를 끊었으면 부르지 않는다).
    리포트 기상 성공률은 이 기록(flow_completed_at)이 있는 세션을 성공으로 센다(11/26 회의). 다시 불러도 처음 시각을 유지한다."""
    return _view(svc.complete(user_id, session_id))


@router.put("/sessions/{session_id}/bedtime", response_model=SessionOut)
def report_bedtime(session_id: str, body: BedtimeIn, user_id: str = Depends(current_user_id),
                   svc: MorningService = Depends(morning_service)):
    """모닝콜에서 사용자가 말한 어젯밤 잠든 시각(AI report_bedtime) {"bed_time": "HH:MM"}. 서버가 날짜를 붙여 저장하고
    (정오 이후 = 전날 밤, 정오 전 = 자정을 넘긴 그날) 리포트 취침 시각을 이 값으로 덮어쓴다. 지금보다 뒤면 422 BEDTIME_INVALID."""
    return svc.report_bedtime(user_id, session_id, body.bed_time)


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


@router.post("/sessions/{session_id}/fortune-token", response_model=FortuneTokenOut)
def fortune_token(session_id: str, user_id: str = Depends(current_user_id), svc: MorningService = Depends(morning_service)):
    """포춘 단계(브리핑 → 전날 녹음 듣기 → 포춘 → 코칭)에서 호출. AI가 오늘의 포춘 문장을 한 번 그대로 읽는다(함수 호출 없음).
    AI 음성이 끝나면(turnComplete) 앱이 다음 단계로 넘어간다. 재연결해도 같은 문장(세션에 저장)."""
    token, s = svc.fortune_token(user_id, session_id)
    return FortuneTokenOut(**token.__dict__, fortune_text=s.fortune_text, fortune_style=s.fortune_style,
                           fortune_source=s.fortune_source)


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


@router.put("/sessions/{session_id}/steps/{step_order}/score", response_model=GameScoreOut)
def save_game_score(session_id: str, step_order: int, body: GameScoreIn, user_id: str = Depends(current_user_id),
                    svc: MorningService = Depends(morning_service)):
    """게임 기록: 제한 시간 안에 주고받은 횟수를 앱이 세서 보낸다 (게임 세션만, 아니면 422 NOT_GAME_SESSION).
    이어 하기로 다시 보내면 바꾼다. 말한 단어는 서버에 보내지 않는다. 응답의 best_score는 같은 게임의 최고 기록."""
    step, best = svc.save_game_score(user_id, session_id, step_order, body.score)
    return GameScoreOut(step_order=step.step_order, score=step.score, best_score=best, is_best=step.score >= best)


@router.patch("/sessions/{session_id}/steps/{step_order}", response_model=SessionViewOut)
def mark_step(session_id: str, step_order: int, body: StepStatusIn, user_id: str = Depends(current_user_id),
              svc: MorningService = Depends(morning_service)):
    """체크리스트에서 직접 체크(DONE)·건너뛰기(SKIPPED)·해제(PENDING)."""
    return _view(svc.mark_step(user_id, session_id, step_order, body.step_status))


@router.post("/sessions/{session_id}/stretch-area", response_model=SessionViewOut)
def select_stretch_area(session_id: str, body: StretchAreaIn, user_id: str = Depends(current_user_id),
                        svc: MorningService = Depends(morning_service)):
    """스트레칭 코칭 중 AI의 select_stretch_area(area)를 앱이 전달. 아직 판정하지 않은 단계를 그 부위 루틴(1번부터)으로 바꾼다.
    판정한 단계가 이미 있거나 이미 다른 부위를 골랐으면 409 STRETCH_AREA_LOCKED (앱은 AI에 그대로 ok를 돌려주고 기존 단계로 진행)."""
    return _view(svc.select_stretch_area(user_id, session_id, body.area))


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
