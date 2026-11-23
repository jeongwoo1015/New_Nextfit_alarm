"""온보딩, 내 정보, 설정."""
from fastapi import APIRouter, Depends, Response, status

from backend.app.deps import user_service, wake_service
from backend.core.auth import current_user_id
from backend.core.errors import NotFound
from backend.domain.wake_rules import rewake_level
from backend.services.user_service import OnboardingInput, Profile, UserService
from backend.services.wake_service import WakeService

from .schemas import SETTING_NULLABLE, AiSettingChangeIn, AlarmOut, OnboardingIn, ProfileOut, SettingOut, SettingPatch, UserOut, UserPatch, WakeNextOut

router = APIRouter(tags=["users"])


def _profile(p: Profile) -> ProfileOut:
    return ProfileOut(onboarding_done=p.onboarding_done,
                      user=UserOut.model_validate(p.user) if p.user else None,
                      setting=SettingOut.model_validate(p.setting) if p.setting else None)


@router.get("/users/me", response_model=ProfileOut)
def get_me(user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    """앱 시작 시 호출. onboarding_done이 false면 온보딩 화면으로 보낸다."""
    return _profile(svc.profile(user_id))


@router.post("/onboarding", status_code=status.HTTP_201_CREATED)
def onboard(body: OnboardingIn, user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    """온보딩 1~3단계 값을 한 번에 저장하고, 고른 프리셋으로 07:00 루틴 알람을 만든다."""
    profile, alarm = svc.onboard(user_id, OnboardingInput(**body.model_dump()))
    return {**_profile(profile).model_dump(mode="json"), "first_alarm": AlarmOut.model_validate(alarm).model_dump(mode="json") if alarm else None}


@router.patch("/users/me", response_model=UserOut)
def patch_me(body: UserPatch, user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    return svc.update_user(user_id, body.model_dump(exclude_none=True))


@router.delete("/users/me", status_code=status.HTTP_204_NO_CONTENT)
def delete_me(user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    """탈퇴: 목소리·원본 음성·사진 파일을 지우고 Auth 계정을 지운다(DB 행은 CASCADE). 되돌릴 수 없다."""
    svc.delete_account(user_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/settings", response_model=SettingOut)
def get_settings(user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    p = svc.profile(user_id)
    if not p.setting:
        raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
    return p.setting


@router.patch("/settings", response_model=SettingOut)
def patch_settings(body: SettingPatch, user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    """내 정보(호칭·어조·말투·목소리·음악 장르)와 설정(취침 알림·테마·언어·기상 볼륨). 보낸 항목만 바꾼다.
    null은 무시하고, 기상 볼륨 두 칸(wake_max_start_level, wake_next_level)만 null로 비울 수 있다."""
    changes = {k: v for k, v in body.model_dump(exclude_unset=True).items() if v is not None or k in SETTING_NULLABLE}
    return svc.update_setting(user_id, changes)


@router.post("/settings/ai-change", response_model=SettingOut)
def ai_change_setting(body: AiSettingChangeIn, user_id: str = Depends(current_user_id), svc: UserService = Depends(user_service)):
    """대화로 설정 바꾸기 — 밤·브리핑 Live에서 AI가 update_setting(field, value, scope)을 부르면 앱이 인자를 그대로 보낸다.
    음악·성격·강도는 그 설정을 바꾸고(다음 모닝콜부터), wake_volume은 scope=TODAY면 다음 알람 한 번(wake_next_level),
    ALWAYS면 앞으로의 상한(wake_max_start_level). wake_volume에 scope가 없으면 TODAY(한 번만 바뀌는 쪽).
    허용 값 밖이면 422 SETTING_VALUE_INVALID — 앱은 결과와 상관없이 AI에는 {"result":"ok"}를 돌려준다(B22)."""
    return svc.change_setting_by_voice(user_id, body.field, body.value, body.scope)


@router.get("/wake-volume/next", response_model=WakeNextOut)
def wake_volume_next(user_id: str = Depends(current_user_id), svc: WakeService = Depends(wake_service)):
    """다음 아침 기상 알람의 시작 볼륨 (AI B23). 알람이 울릴 때 네트워크를 기다리지 않도록 앱이 밤 대화 뒤·앱을 열 때 받아 저장해 두고,
    울릴 때 그 값으로 시작한다(받은 값이 없으면 1단계). 밤 대화가 없던 날은 이때 정하며 올리지 않는다.
    다음 모닝콜이 없으면 404 NO_UPCOMING_ALARM."""
    n = svc.next(user_id)
    return WakeNextOut(session_date=n.session_date, alarm_id=n.alarm.alarm_id, alarm_time=n.alarm.alarm_time,
                       start_level=n.start_level, rewake_level=rewake_level(n.start_level),
                       source=n.row.get("source"), state=n.row.get("state"))
