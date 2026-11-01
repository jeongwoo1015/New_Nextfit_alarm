"""루틴 프리셋 선택 카드와 개인화(항목 빼기)."""
from fastapi import APIRouter, Depends

from backend.app.deps import routine_service
from backend.core.auth import current_user_id
from backend.services.routine_service import Preset, RoutineService, UserItem

from .schemas import PresetOut, TemplateItemOut, UserItemOut, UserItemPatch

router = APIRouter(tags=["routines"])


def _preset(p: Preset) -> PresetOut:
    t = p.template
    return PresetOut(template_id=t.template_id, template_name=t.template_name, target_desc=t.target_desc,
                     description=t.description, total_10_sec=p.total_sec(10), total_20_sec=p.total_sec(20),
                     items=[TemplateItemOut.model_validate(i) for i in p.items])


def _items(items: list[UserItem]) -> list[UserItemOut]:
    return [UserItemOut(**TemplateItemOut.model_validate(u.item).model_dump(), enabled=u.enabled) for u in items]


@router.get("/presets", response_model=list[PresetOut])
def list_presets(_: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    """선택 가능한(IS_ACTIVE) 프리셋과 항목. 10분/20분 버전별 총 시간 포함."""
    return [_preset(p) for p in svc.presets()]


@router.get("/presets/{template_id}", response_model=PresetOut)
def get_preset(template_id: str, _: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    return _preset(svc.preset(template_id))


@router.get("/presets/{template_id}/my-items", response_model=list[UserItemOut])
def my_items(template_id: str, user_id: str = Depends(current_user_id), svc: RoutineService = Depends(routine_service)):
    """프리셋 항목과 내가 뺐는지(enabled=false). CUSTOM 알람에만 적용된다."""
    return _items(svc.user_items(user_id, template_id))


@router.put("/routine-items/{template_item_id}", response_model=list[UserItemOut])
def set_item(template_item_id: str, body: UserItemPatch, user_id: str = Depends(current_user_id),
             svc: RoutineService = Depends(routine_service)):
    """항목 빼기({"enabled": false}) / 다시 넣기(true). 응답은 그 프리셋의 바뀐 항목 목록."""
    return _items(svc.set_enabled(user_id, template_item_id, body.enabled))
