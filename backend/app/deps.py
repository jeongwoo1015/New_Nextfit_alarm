"""엔드포인트가 쓰는 의존성: 저장소 묶음과 서비스."""
from fastapi import Depends, Request

from backend.repositories.registry import Repositories
from backend.services.alarm_service import AlarmService
from backend.services.routine_service import RoutineService
from backend.services.user_service import UserService


def get_repos(request: Request) -> Repositories:
    return request.app.state.repos


def user_service(repos: Repositories = Depends(get_repos)) -> UserService:
    return UserService(repos)


def alarm_service(repos: Repositories = Depends(get_repos)) -> AlarmService:
    return AlarmService(repos)


def routine_service(repos: Repositories = Depends(get_repos)) -> RoutineService:
    return RoutineService(repos)
