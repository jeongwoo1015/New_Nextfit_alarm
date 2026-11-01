import time
from datetime import datetime

import jwt
import pytest
from fastapi.testclient import TestClient

from backend.app.main import create_app
from backend.clients.fake_ai import FakeAIGateway
from backend.core.auth import TokenVerifier, get_verifier
from backend.core.config import Settings
from backend.core.timeutil import KST
from backend.repositories.memory import memory_repositories

SECRET = "test-secret-at-least-32-bytes-long!!"
USER_A = "aaaaaaaa-0000-0000-0000-000000000001"
USER_B = "bbbbbbbb-0000-0000-0000-000000000002"


def auth(user_id: str) -> dict:
    claims = {"sub": user_id, "aud": "authenticated", "exp": int(time.time()) + 600}
    return {"Authorization": f"Bearer {jwt.encode(claims, SECRET, algorithm='HS256')}"}


class Clock:
    """테스트용 KST 시계. 기본은 2026-11-05(목) 23:30."""

    def __init__(self, now: datetime = datetime(2026, 11, 5, 23, 30, tzinfo=KST)):
        self.now = now

    def __call__(self) -> datetime:
        return self.now


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def repos(clock):
    return memory_repositories(clock=clock)


@pytest.fixture
def ai():
    return FakeAIGateway()


@pytest.fixture
def client(repos, ai, clock):
    app = create_app(repos, ai, clock)
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(Settings(supabase_jwt_secret=SECRET))
    return TestClient(app)
