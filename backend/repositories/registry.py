"""저장소 묶음과 선택. REPOSITORY_BACKEND=memory(기본) | supabase.

supabase를 고르면 `backend.repositories.supabase.build_repositories()`(backend-db 담당)가 Repositories를 돌려줘야 한다.
"""
import importlib
from dataclasses import dataclass

from .base import (AlarmRepository, AlarmRoutineItemRepository, AppConfigRepository, RoutineTemplateRepository,
                   UserRepository, UserSettingRepository)


@dataclass
class Repositories:
    users: UserRepository
    settings: UserSettingRepository
    config: AppConfigRepository
    alarms: AlarmRepository
    templates: RoutineTemplateRepository
    alarm_routine_items: AlarmRoutineItemRepository


def build_repositories(backend: str) -> Repositories:
    if backend == "memory":
        from .memory import memory_repositories
        return memory_repositories()
    if backend == "supabase":
        return importlib.import_module("backend.repositories.supabase").build_repositories()
    raise ValueError(f"REPOSITORY_BACKEND는 memory 또는 supabase예요: {backend!r}")
