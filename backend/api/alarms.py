"""알람 목록·설정 (홈 화면)."""
from fastapi import APIRouter, Depends, Response, status

from backend.app.deps import alarm_service
from backend.core.auth import current_user_id
from backend.core.errors import ValidationFailed
from backend.services.alarm_service import AlarmRoutine, AlarmService

from .schemas import AlarmIn, AlarmOut, AlarmPatch, AlarmRoutineIn, AlarmRoutineOut, RoutineItemOut

router = APIRouter(prefix="/alarms", tags=["alarms"])

NULLABLE = {"label", "template_id", "content_type", "content_option"}


def _routine(r: AlarmRoutine) -> AlarmRoutineOut:
    items = [RoutineItemOut.model_validate(x) for x in r.items]
    return AlarmRoutineOut(alarm_id=r.alarm.alarm_id, template_id=r.alarm.template_id, total_sec=r.total_sec,
                           max_sec=r.max_sec, items=items)


@router.get("", response_model=list[AlarmOut])
def list_alarms(user_id: str = Depends(current_user_id), svc: AlarmService = Depends(alarm_service)):
    return svc.list(user_id)


@router.post("", response_model=AlarmOut, status_code=status.HTTP_201_CREATED)
def create_alarm(body: AlarmIn, user_id: str = Depends(current_user_id), svc: AlarmService = Depends(alarm_service)):
    return svc.create(user_id, body.model_dump())


@router.get("/{alarm_id}", response_model=AlarmOut)
def get_alarm(alarm_id: str, user_id: str = Depends(current_user_id), svc: AlarmService = Depends(alarm_service)):
    return svc.get(user_id, alarm_id)


@router.patch("/{alarm_id}", response_model=AlarmOut)
def patch_alarm(alarm_id: str, body: AlarmPatch, user_id: str = Depends(current_user_id),
                svc: AlarmService = Depends(alarm_service)):
    """보낸 항목만 바꾼다. 목록의 토글은 {"enabled": false}."""
    changes = body.model_dump(exclude_unset=True)
    nulls = sorted(k for k, v in changes.items() if v is None and k not in NULLABLE)
    if nulls:
        raise ValidationFailed(f"비울 수 없는 항목이에요: {', '.join(nulls)}")
    return svc.update(user_id, alarm_id, changes)


@router.delete("/{alarm_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_alarm(alarm_id: str, user_id: str = Depends(current_user_id), svc: AlarmService = Depends(alarm_service)):
    svc.delete(user_id, alarm_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{alarm_id}/routine-items", response_model=AlarmRoutineOut)
def get_routine_items(alarm_id: str, user_id: str = Depends(current_user_id), svc: AlarmService = Depends(alarm_service)):
    """알람 세부 설정: 이 알람의 항목별 시간(0 = 뺀 항목), 합계, 상한. 루틴 알람만."""
    return _routine(svc.routine(user_id, alarm_id))


@router.put("/{alarm_id}/routine-items", response_model=AlarmRoutineOut)
def put_routine_items(alarm_id: str, body: AlarmRoutineIn, user_id: str = Depends(current_user_id),
                      svc: AlarmService = Depends(alarm_service)):
    """항목 시간 전체 교체 (30초 단위, 0초 = 빼기, 합계 ≤ 상한, 최소 한 항목). 보내지 않은 항목은 기본 시간."""
    durations = {i.template_item_id: i.duration_sec for i in body.items}
    if len(durations) != len(body.items):
        raise ValidationFailed("같은 항목이 두 번 들어 있어요", "ROUTINE_ITEM_DUPLICATE")
    return _routine(svc.set_routine(user_id, alarm_id, durations))
