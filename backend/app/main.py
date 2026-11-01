"""FastAPI 앱. 실행: uvicorn backend.app.main:app --reload (저장소 루트에서)"""
from fastapi import Depends, FastAPI

from backend.api import alarms, routines, users
from backend.core.auth import current_user_id
from backend.core.config import get_settings
from backend.core.errors import install_error_handlers
from backend.repositories.registry import Repositories, build_repositories


def create_app(repos: Repositories | None = None) -> FastAPI:
    app = FastAPI(title="Re:Spec Backend API", version="0.1.0")
    app.state.repos = repos or build_repositories(get_settings().repository_backend)
    install_error_handlers(app)

    @app.get("/health", tags=["system"])
    def health():
        return {"status": "ok"}

    @app.get("/me", tags=["system"])
    def me(user_id: str = Depends(current_user_id)):
        """로그인 토큰 확인용. 토큰의 user_id를 그대로 돌려준다."""
        return {"user_id": user_id}

    for module in (users, alarms, routines):
        app.include_router(module.router)
    return app


app = create_app()
