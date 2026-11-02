"""온보딩, 내 정보, 설정."""
from fastapi import APIRouter, Depends, Response, status

from backend.app.deps import user_service
from backend.core.auth import current_user_id
from backend.core.errors import NotFound
from backend.services.user_service import OnboardingInput, Profile, UserService

from .schemas import AlarmOut, OnboardingIn, ProfileOut, SettingOut, SettingPatch, UserOut, UserPatch

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
    """내 정보(호칭·어조·말투·목소리·음악 장르)와 설정(취침 알림·테마·언어). 보낸 항목만 바꾼다."""
    return svc.update_setting(user_id, body.model_dump(exclude_none=True))
