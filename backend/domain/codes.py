"""정의서(11/01 수정판) 값 목록 — DB의 CHECK 제약과 같은 값."""

MORNING_CALL_TYPES = ("ROUTINE", "CONTENT")
# 11/18 DB 14_bedtime_alarm.sql: 굿나잇콜도 RS_ALARM에 (방향 B). BEDTIME은 모닝콜 칸(종류·프리셋·콘텐츠)을 비우고 ON_TIME만
ALARM_KINDS = ("WAKE", "BEDTIME")
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
SPEECH_STYLES = ("KIND", "FRIENDLY", "FIRM")   # 11/13부터 PERSONALITY로 대체, DB가 칸을 지우면 함께 뺀다
# 11/16 DB 12_persona_stretch.sql: 말투를 성격 + 강도로 나눔 (AI CONTRACTS B17)
PERSONALITIES = ("KIND", "FRIENDLY", "STRICT", "CHEERFUL")
SPEECH_TO_PERSONALITY = {"KIND": "KIND", "FRIENDLY": "FRIENDLY", "FIRM": "STRICT"}   # 예전 앱이 speech_style만 보낼 때
INTENSITIES = (1, 2, 3)                          # 깨우는 말의 강도 1 약 / 2 중 / 3 강
ENGLISH_LEVELS = ("BEGINNER", "INTERMEDIATE")
# 11/18 AI stretch_sets: 부위 12개 (ai.tasks.stretch_sets.AREAS와 같게)
STRETCH_AREAS = ("FULL", "NECK", "SHOULDER", "CHEST_BACK", "WRIST_HAND", "ELBOW_ARM",
                 "LOW_BACK", "PELVIS", "GLUTE", "THIGH", "CALF", "ANKLE_FOOT")
# 11/13~17에 쓰던 부위 5개 → 가까운 새 부위 (ai.tasks.stretch_sets.LEGACY_AREAS와 같게).
# 예전 앱이 보내면 바꿔 읽고, 이미 저장된 세션 값은 그대로 둔다
LEGACY_STRETCH_AREAS = {"NECK_SHOULDER": "NECK", "ARM_WRIST": "WRIST_HAND", "BACK_HIP": "LOW_BACK", "LEG": "THIGH"}
STRETCH_AREA_INPUTS = STRETCH_AREAS + tuple(LEGACY_STRETCH_AREAS)   # 요청에서 받는 값
# 미루기 (11/20 회의, 11/22 DB): 버튼·말 모두 snooze_count 하나로 세고 하루 3번까지. AI ai.live.tools의 SNOOZE_MAX_*와 같은 값
SNOOZE_DEFAULT_MIN = 5              # 버튼 "5분 뒤 다시 울리기" (시간을 보내지 않을 때)
SNOOZE_MAX_MIN = 60                 # 지금부터 60분 안
SNOOZE_MAX_PER_DAY = 3              # 그날 모든 모닝 세션의 미루기 합계
WAKE_LEVELS = (1, 2, 3, 4, 5)       # 기상 알람 볼륨 단계 (11/22 RS_USER_SETTING.WAKE_*_LEVEL, 앱 AlarmSoundPlayer.MAX_LEVEL = 5)
# 대화로 바꿀 수 있는 설정 (11/20 회의 4, AI update_setting). value는 AI가 글자로 보낸다
AI_SETTING_FIELDS = ("music_genre", "personality", "intensity", "wake_volume")
AI_SETTING_SCOPES = ("ALWAYS", "TODAY")
DEFAULT_STRETCH_DURATION_SEC = 180  # RS_APP_CONFIG.STRETCH_DURATION_SEC가 없을 때 (11/18 AI: 4단계 × 45초, DB 기본값과 같음). 영어·게임은 CONTENT_DURATION_SEC

VOICE_TYPES = ("FEMALE", "MALE", "NEUTRAL")
# 11/18 DB 13_stretch_theme.sql: SYSTEM → AUTO(해가 떠 있으면 라이트, 지면 다크). 예전 앱이 SYSTEM을 보내면 AUTO로 저장
THEMES = ("LIGHT", "DARK", "AUTO")
LEGACY_THEMES = {"SYSTEM": "AUTO"}
THEME_INPUTS = THEMES + tuple(LEGACY_THEMES)
MUSIC_GENRES = ("POP", "JAZZ", "CLASSIC", "DANCE", "ACOUSTIC")
CONDITION_LABELS = ("GOOD", "NORMAL", "TIRED", "STRESSED")   # 임시, AI 파트와 확정
MUSIC_TYPES = ("REWAKE", "SLEEP")
EXTRACT_STATUSES = ("PENDING", "DONE", "FAILED")


def normalize_stretch_area(area: str) -> str:
    """옛 부위 값을 새 부위로 (새 값은 그대로)."""
    return LEGACY_STRETCH_AREAS.get(area, area)


def normalize_theme(theme: str) -> str:
    """예전 테마 값(SYSTEM)을 새 값(AUTO)으로."""
    return LEGACY_THEMES.get(theme, theme)
