"""저장소 묶음과 선택. REPOSITORY_BACKEND=memory(기본) | supabase.

supabase를 고르면 `backend.repositories.supabase.build_repositories()`(backend-db 담당)가 Repositories를 돌려줘야 한다.
"""
import importlib
from dataclasses import dataclass

from .base import (AlarmRepository, NightRepository, PersonalizationRepository, PresetRepository, SessionRepository,
                   StorageClient, UserRepository)


@dataclass
class Repositories:
    users: UserRepository
    presets: PresetRepository
    alarms: AlarmRepository
    nights: NightRepository | None = None
    sessions: SessionRepository | None = None
    personalization: PersonalizationRepository | None = None
    storage: StorageClient | None = None


def build_repositories(backend: str) -> Repositories:
    if backend == "memory":
        from .memory import memory_repositories
        return memory_repositories()
    if backend == "supabase":
        return importlib.import_module("backend.repositories.supabase").build_repositories()
    raise ValueError(f"REPOSITORY_BACKEND는 memory 또는 supabase예요: {backend!r}")
