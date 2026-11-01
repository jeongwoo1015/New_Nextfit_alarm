import time

import jwt
import pytest
from fastapi.testclient import TestClient

from backend.app.main import create_app
from backend.core.auth import TokenVerifier, get_verifier
from backend.core.config import Settings
from backend.repositories.memory import memory_repositories

SECRET = "test-secret-at-least-32-bytes-long!!"
USER_A = "aaaaaaaa-0000-0000-0000-000000000001"
USER_B = "bbbbbbbb-0000-0000-0000-000000000002"


def auth(user_id: str) -> dict:
    claims = {"sub": user_id, "aud": "authenticated", "exp": int(time.time()) + 600}
    return {"Authorization": f"Bearer {jwt.encode(claims, SECRET, algorithm='HS256')}"}


@pytest.fixture
def repos():
    return memory_repositories()


@pytest.fixture
def client(repos):
    app = create_app(repos)
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(Settings(supabase_jwt_secret=SECRET))
    return TestClient(app)
