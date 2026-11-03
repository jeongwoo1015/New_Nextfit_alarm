"""FastAPI 앱. 실행: uvicorn backend.app.main:app --reload (저장소 루트에서)"""
from fastapi import Depends, FastAPI

from backend.api import alarms, history, morning, music, night, routines, users
from backend.clients.ai_gateway import build_ai_gateway
from backend.clients.weather import build_weather_client
from backend.core.auth import current_user_id
from backend.core.config import get_settings
from backend.core.errors import install_error_handlers
from backend.core.timeutil import now_kst
from backend.repositories.registry import Repositories, build_repositories


def create_app(repos: Repositories | None = None, ai=None, clock=now_kst, weather=None) -> FastAPI:
    """repos·ai·clock·weather는 테스트에서 바꿔 끼운다 (clock: KST 현재 시각을 돌려주는 함수)."""
    app = FastAPI(title="Re:Spec Backend API", version="0.1.0")
    settings = get_settings()
    app.state.repos = repos or build_repositories(settings.repository_backend)
    app.state.ai = ai or build_ai_gateway(settings)
    app.state.weather = weather or build_weather_client(settings)
    app.state.clock = clock
    app.state.personalization_mode = settings.personalization_mode
    install_error_handlers(app)

    @app.get("/health", tags=["system"])
    def health():
        return {"status": "ok"}

    @app.get("/me", tags=["system"])
    def me(user_id: str = Depends(current_user_id)):
        """로그인 토큰 확인용. 토큰의 user_id를 그대로 돌려준다."""
        return {"user_id": user_id}

    for module in (users, alarms, routines, night, morning, history, music):
        app.include_router(module.router)
    return app


app = create_app()
