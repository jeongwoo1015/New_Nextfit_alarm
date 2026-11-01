"""로그인 토큰(Supabase Auth JWT) 검증 → user_id.

서버는 service_role 키로 DB에 접근하므로 RLS가 적용되지 않는다. 그래서 모든 API는 여기서 꺼낸 user_id로
요청 데이터의 USER_ID를 직접 확인한다(정의서 접근 권한 규칙).
"""
from functools import lru_cache

import jwt
from fastapi import Depends, Header

from .config import Settings, get_settings
from .errors import Forbidden, Unauthorized

AUDIENCE = "authenticated"
# 서버와 Supabase 시계 차이 허용(초). 서버 시계가 조금만 느려도 막 발급된 토큰이 iat "not yet valid"로 거절된다
CLOCK_SKEW_SEC = 30


class TokenVerifier:
    """SUPABASE_JWT_SECRET이 있으면 HS256(레거시), 없으면 프로젝트 JWKS(ES256/RS256)로 검증한다."""

    def __init__(self, settings: Settings):
        self._secret = settings.supabase_jwt_secret
        self._jwks = jwt.PyJWKClient(settings.jwks_url, cache_keys=True) if not self._secret and settings.jwks_url else None

    def verify(self, token: str) -> str:
        try:
            if self._secret:
                claims = jwt.decode(token, self._secret, algorithms=["HS256"], audience=AUDIENCE, leeway=CLOCK_SKEW_SEC)
            elif self._jwks:
                key = self._jwks.get_signing_key_from_jwt(token).key
                claims = jwt.decode(token, key, algorithms=["ES256", "RS256"], audience=AUDIENCE, leeway=CLOCK_SKEW_SEC)
            else:
                raise Unauthorized("토큰 검증 설정이 없어요 (SUPABASE_URL 또는 SUPABASE_JWT_SECRET)")
        except jwt.PyJWTError as e:
            raise Unauthorized(f"로그인 토큰이 올바르지 않아요: {e}") from None
        user_id = claims.get("sub")
        if not user_id:
            raise Unauthorized("토큰에 사용자 정보(sub)가 없어요")
        return user_id


@lru_cache
def get_verifier() -> TokenVerifier:
    return TokenVerifier(get_settings())


def current_user_id(authorization: str | None = Header(default=None),
                    verifier: TokenVerifier = Depends(get_verifier)) -> str:
    """FastAPI 의존성: `Authorization: Bearer <access_token>` → user_id."""
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise Unauthorized("Authorization: Bearer <토큰> 헤더가 필요해요")
    return verifier.verify(token)


def ensure_owner(user_id: str, row_user_id: str) -> None:
    if str(row_user_id) != str(user_id):
        raise Forbidden("다른 사용자의 데이터에는 접근할 수 없어요")
