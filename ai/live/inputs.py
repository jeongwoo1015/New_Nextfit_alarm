"""서버가 AI 쪽에 넘기는 입력 형식.

컬럼 이름과 값 목록은 DB 정의서(10/31수정판)를 따른다. 일정(캘린더)과 날씨는 DB에 저장하지 않는
실행 시점 입력이며 정의서에는 없는 값이다(팀 확인 필요).
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Persona:
    """RS_USER_SETTING: USER_CALL_NAME, TONE(FORMAL/CASUAL), SPEECH_STYLE(KIND/FRIENDLY/FIRM), VOICE_TYPE(FEMALE/MALE/NEUTRAL)."""
    call_name: str = ""
    tone: str = "FORMAL"
    speech_style: str = "KIND"
    voice_type: str = "FEMALE"


@dataclass(frozen=True)
class CalendarEvent:
    """폰 캘린더의 일정 하나. start/end는 "HH:MM"(KST), 시간이 없거나 종일이면 all_day=True."""
    title: str
    start: str = ""
    end: str = ""
    all_day: bool = False


@dataclass(frozen=True)
class Weather:
    """날씨 API 결과를 서버가 정리해 넘긴 값. 없는 값은 None/빈 문자열로 둔다."""
    summary: str = ""            # 예: "맑음", "비"
    temp_c: float | None = None  # 현재 기온
    high_c: float | None = None
    low_c: float | None = None
    precip_prob: int | None = None  # 강수 확률 0~100


@dataclass(frozen=True)
class NightRecordView:
    """RS_NIGHT_RECORD 중 아침 대화에 쓰는 컬럼 (APPLY_DATE = 오늘인 행)."""
    ai_summary: str = ""
    tomorrow_goal: str = ""
    tomorrow_notes: tuple[str, ...] = ()
    condition_label: str = ""   # GOOD / NORMAL / TIRED / STRESSED
    condition_text: str = ""
    encouragement: str = ""
    insight: str = ""


@dataclass(frozen=True)
class Step:
    """RS_SESSION_STEP 한 줄 + 프리셋 항목의 RESPONSE_TYPE, SCRIPT.

    script는 프리셋의 고정 안내문이거나, 콘텐츠 알람에서 AI가 그날 구성한 내용(퀴즈 문제 등)이다.
    콘텐츠 단계의 상세 내용을 어디에 보관하는지는 정의서에 없다(팀 확인 필요).
    """
    order: int                         # STEP_ORDER (1부터)
    name: str                          # STEP_NAME
    planned_sec: int | None = None     # PLANNED_DURATION_SEC
    response_type: str = "QA"          # PLAY / QA / SPEECH
    script: str = ""


@dataclass(frozen=True)
class NightInput:
    persona: Persona = Persona()
    events_today: tuple[CalendarEvent, ...] = ()
    now_hhmm: str = ""                 # 대화 시작 시각(KST). 아직 지나지 않은 일정을 끝난 것처럼 묻지 않기 위해 쓴다
    tomorrow_wake_time: str = ""       # 내일 울릴 알람 시각 "HH:MM" (하루 한정 변경 제안용)
    tomorrow_coaching_min: int | None = None  # 내일 알람의 기본 COACHING_MIN (10/20)
    events_tomorrow: tuple[CalendarEvent, ...] = ()
    routine_items: tuple[str, ...] = ()  # ROUTINE_MODE=CUSTOM 루틴 알람의 현재 사용 중인 항목 이름 (빼기 요청 확인용)
    allow_skip_coaching: bool = True   # 사용자가 먼저 '코칭 안 할래'라고 하면 받아들인다(정의서: 하루 한정 0 허용). AI가 먼저 권하지는 않는다


@dataclass(frozen=True)
class BriefingInput:
    persona: Persona = Persona()
    morning_call_type: str = "ROUTINE"  # (지시문에서는 쓰지 않음. 콘텐츠와 기본 루틴이 함께 진행될 수 있어 content_steps / routine_follows로 구분한다)
    program_name: str = ""             # 기본 루틴 프리셋 이름 (전환 문장에 사용)
    coaching_min: int = 20             # 적용 코칭 길이: 10 / 20 / 0(수행 안 함, 하루 한정 변경). 0이면 콘텐츠·루틴 모두 진행하지 않는다
    content_name: str = ""             # 콘텐츠 이름 (예: 퀴즈). 있으면 브리핑 뒤에 콘텐츠 세션이 먼저 이어진다
    routine_follows: bool = True       # 기본 루틴 세션이 (콘텐츠가 있으면 그 뒤에) 이어지는가. 콘텐츠만 있는 아침이면 False
    now_hhmm: str = ""                 # 브리핑 시작 시각(KST) "HH:MM" — 이미 일어난 뒤라 알람 시각 대신 현재 시각을 말한다
    target_wake_time: str = ""         # (지시문에서는 쓰지 않음. 서버 호환용으로 남김)
    events_today: tuple[CalendarEvent, ...] = ()
    weather: Weather | None = None
    night: NightRecordView | None = None


@dataclass(frozen=True)
class CoachingInput:
    """단계를 하나씩 진행하는 세션의 입력. 기본 루틴 세션과 콘텐츠 세션이 같은 형식을 쓴다."""
    persona: Persona = Persona()
    morning_call_type: str = "ROUTINE"  # ROUTINE(기본 루틴 세션) / CONTENT(콘텐츠 세션) — 지시문의 호칭과 안내문만 달라진다
    steps: tuple[Step, ...] = ()       # 아직 PENDING인 단계만 (재연결이면 이어서). STEP_ORDER는 세션 전체 번호(콘텐츠가 먼저, 루틴이 이어짐)
    routine_follows: bool = False      # 콘텐츠 세션 뒤에 기본 루틴 세션이 이어지는가. True면 마지막 단계 뒤에 루틴 시작 준비를 묻고 start_routine을 부른다
    next_program_name: str = ""        # 이어지는 기본 루틴 프리셋 이름
    resumed: bool = False              # True면 전체 안내를 이미 했으므로 생략
    night: NightRecordView | None = None
