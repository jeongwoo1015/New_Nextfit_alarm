"""사용자·설정 저장 전 검사 (정의서 RS_USER, RS_USER_SETTING 값 목록)."""
from backend.core.errors import ValidationFailed

from . import codes
from .models import User, UserSetting


def _check(errors: list, name: str, value, allowed) -> None:
    if value not in allowed:
        errors.append(f"{name}는 {'/'.join(allowed)} 중 하나예요")


def validate_user(u: User) -> None:
    errors = []
    if not (u.user_name or "").strip() or len(u.user_name) > 50:
        errors.append("user_name은 1~50자예요")
    _check(errors, "gender", u.gender, codes.GENDERS)
    if errors:
        raise ValidationFailed(" / ".join(errors))


def validate_setting(s: UserSetting) -> None:
    errors = []
    if not (s.user_call_name or "").strip() or len(s.user_call_name) > 30:
        errors.append("user_call_name은 1~30자예요")
    _check(errors, "tone", s.tone, codes.TONES)
    _check(errors, "speech_style", s.speech_style, codes.SPEECH_STYLES)
    _check(errors, "voice_type", s.voice_type, codes.VOICE_TYPES)
    _check(errors, "music_genre", s.music_genre, codes.MUSIC_GENRES)
    _check(errors, "theme", s.theme, codes.THEMES)
    if not s.app_language or len(s.app_language) > 10:
        errors.append("app_language는 1~10자예요")
    if errors:
        raise ValidationFailed(" / ".join(errors))
