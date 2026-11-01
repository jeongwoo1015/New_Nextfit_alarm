"""서버 설정. 값은 환경변수(.env)에서 읽는다."""
import os
from dataclasses import dataclass
from functools import lru_cache

from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    supabase_url: str = ""
    supabase_service_role_key: str = ""
    supabase_jwt_secret: str = ""   # 비어 있으면 JWKS(비대칭 키)로 검증
    gemini_api_key: str = ""
    repository_backend: str = "memory"   # memory | supabase

    @property
    def jwks_url(self) -> str:
        return f"{self.supabase_url.rstrip('/')}/auth/v1/.well-known/jwks.json" if self.supabase_url else ""


@lru_cache
def get_settings() -> Settings:
    load_dotenv()
    return Settings(
        supabase_url=os.getenv("SUPABASE_URL", ""),
        supabase_service_role_key=os.getenv("SUPABASE_SERVICE_ROLE_KEY", ""),
        supabase_jwt_secret=os.getenv("SUPABASE_JWT_SECRET", ""),
        gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
        repository_backend=os.getenv("REPOSITORY_BACKEND", "memory"),
    )
