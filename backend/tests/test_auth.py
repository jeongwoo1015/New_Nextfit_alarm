import time

import jwt
import pytest
from fastapi.testclient import TestClient

from backend.app.main import create_app
from backend.core.auth import TokenVerifier, ensure_owner, get_verifier
from backend.core.config import Settings
from backend.core.errors import Forbidden, Unauthorized

SECRET = "test-secret-at-least-32-bytes-long!!"
USER = "11111111-1111-1111-1111-111111111111"


def token(**over):
    claims = {"sub": USER, "aud": "authenticated", "exp": int(time.time()) + 600, **over}
    return jwt.encode(claims, SECRET, algorithm="HS256")


@pytest.fixture
def client():
    app = create_app()
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(Settings(supabase_jwt_secret=SECRET))
    return TestClient(app)


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_valid_token_gives_user_id(client):
    r = client.get("/me", headers={"Authorization": f"Bearer {token()}"})
    assert r.status_code == 200 and r.json() == {"user_id": USER}


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Basic abc"}, {"Authorization": "Bearer"}])
def test_missing_or_malformed_header(client, headers):
    r = client.get("/me", headers=headers)
    assert r.status_code == 401 and r.json()["error"]["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("bad", [
    token(exp=int(time.time()) - 60),          # 만료 (허용 범위 30초 밖)
    token(aud="anon"),                          # 다른 audience
    jwt.encode({"sub": USER, "aud": "authenticated"}, "other-secret-at-least-32-bytes-long", algorithm="HS256"),
    token(sub=None),                            # sub 없음
])
def test_invalid_tokens(client, bad):
    r = client.get("/me", headers={"Authorization": f"Bearer {bad}"})
    assert r.status_code == 401


def test_small_clock_skew_is_allowed(client):
    """서버 시계가 Supabase보다 조금 느려 iat가 미래로 보여도 30초 안이면 통과한다."""
    now = int(time.time())
    ok = token(iat=now + 10, exp=now + 600)
    assert client.get("/me", headers={"Authorization": f"Bearer {ok}"}).status_code == 200
    too_far = token(iat=now + 120, exp=now + 600)
    assert client.get("/me", headers={"Authorization": f"Bearer {too_far}"}).status_code == 401
    just_expired = token(exp=now - 10)                       # 만료 직후 10초도 허용 범위
    assert client.get("/me", headers={"Authorization": f"Bearer {just_expired}"}).status_code == 200


def test_no_verification_config_is_rejected():
    with pytest.raises(Unauthorized):
        TokenVerifier(Settings()).verify(token())


def test_ensure_owner():
    ensure_owner(USER, USER)
    with pytest.raises(Forbidden):
        ensure_owner(USER, "someone-else")
