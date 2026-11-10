"""정의서(11/01 수정판) 값 목록 — DB의 CHECK 제약과 같은 값."""

MORNING_CALL_TYPES = ("ROUTINE", "CONTENT")
CONTENT_TYPES = ("MEDITATION", "EXERCISE", "LANGUAGE", "QUIZ")
# 예시 값(팀 확정 전). 확정되면 여기만 고친다
# 11/10 AI CONTRACTS B8·B14: 콘텐츠 3종 = 스트레칭(EXERCISE/STRETCH)·영어(LANGUAGE/ENGLISH)·퀴즈 게임(QUIZ/PERSON·GENERAL·WORDCHAIN).
# 팀이 목록을 줄이기 전까지 옛 값(MASSAGE 등)도 받는다 (AI도 옛 값을 그대로 처리)
CONTENT_OPTIONS = {
    "MEDITATION": ("BREATH", "BODY_SCAN"),
    "EXERCISE": ("STRETCH", "MASSAGE"),
    "LANGUAGE": ("ENGLISH", "JAPANESE", "CHINESE"),
    "QUIZ": ("PERSON", "GENERAL", "WORDCHAIN", "SCIENCE", "HISTORY", "NONSENSE"),
}

DURATION_STEP_SEC = 30              # 항목 시간 단위 (0 = 그 알람에서 항목 빼기)
DEFAULT_CONTENT_DURATION_SEC = 300  # RS_APP_CONFIG.CONTENT_DURATION_SEC가 비어 있을 때 (11/01 팀 확정 5분, DB 시드와 같은 값)

# Storage 버킷 허용 형식 (backend-db 02_security.sql / 08_storage_limits.sql과 같게)
VOICE_MIME_TYPES = ("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac")
PHOTO_MIME_TYPES = ("image/jpeg",)

ALARM_MODES = ("ON_TIME", "BEFORE_10", "BEFORE_30")
STEP_STATUSES = ("PENDING", "DONE", "SKIPPED")
AI_VERDICTS = ("done", "not_done", "skipped", "ambiguous", "other")
RESPONSE_TYPES = ("PLAY", "QA", "SPEECH")

GENDERS = ("MALE", "FEMALE", "NONE")
TONES = ("FORMAL", "CASUAL")
SPEECH_STYLES = ("KIND", "FRIENDLY", "FIRM")
VOICE_TYPES = ("FEMALE", "MALE", "NEUTRAL")
THEMES = ("LIGHT", "DARK", "SYSTEM")
MUSIC_GENRES = ("POP", "JAZZ", "CLASSIC", "DANCE", "ACOUSTIC")
CONDITION_LABELS = ("GOOD", "NORMAL", "TIRED", "STRESSED")   # 임시, AI 파트와 확정
MUSIC_TYPES = ("REWAKE", "SLEEP")
EXTRACT_STATUSES = ("PENDING", "DONE", "FAILED")
