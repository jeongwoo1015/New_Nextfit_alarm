"""알람 목록·설정 (홈 화면)."""
from fastapi import APIRouter, Depends, Response, status

from backend.app.deps import alarm_service
from backend.core.auth import current_user_id
from backend.core.errors import ValidationFailed
from backend.services.alarm_service import AlarmService

from .schemas import AlarmIn, AlarmOut, AlarmPatch

router = APIRouter(prefix="/alarms", tags=["alarms"])

NULLABLE = {"label", "template_id", "routine_mode", "content_type", "content_option"}


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
