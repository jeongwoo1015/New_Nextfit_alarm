"""루틴 프리셋 선택 카드와 앱 설정값."""
from fastapi import APIRouter, Depends

from backend.app.deps import routine_service
from backend.core.auth import current_user_id
from backend.services.routine_service import Preset, RoutineService

from .schemas import LimitsOut, PresetOut, TemplateItemOut

router = APIRouter(tags=["routines"])


def _preset(p: Preset) -> PresetOut:
    t = p.template
    return PresetOut(template_id=t.template_id, template_name=t.template_name, target_desc=t.target_desc,
                     description=t.description, total_sec=p.total_sec,
                     items=[TemplateItemOut.model_validate(i) for i in p.items])


@router.get("/presets", response_model=list[PresetOut])
def list_presets(_: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    """선택 가능한(IS_ACTIVE) 프리셋과 항목별 기본 시간."""
    return [_preset(p) for p in svc.presets()]


@router.get("/presets/{template_id}", response_model=PresetOut)
def get_preset(template_id: str, _: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    return _preset(svc.preset(template_id))


@router.get("/config/limits", response_model=LimitsOut)
def limits(_: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    """알람 세부 설정 화면이 쓰는 값: 루틴 합계 상한, 시간 단위, 콘텐츠 고정 시간."""
    return svc.limits()
