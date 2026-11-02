"""서버가 AI 쪽에 넘기는 입력 형식.

컬럼 이름과 값 목록은 DB 정의서(11/01수정판)를 따른다. 일정(캘린더)과 날씨는 DB에 저장하지 않는
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
    location_name: str = ""      # 날씨 지역 이름(예: "광진구"). 있으면 브리핑이 "오늘 광진구 날씨는"처럼 지역을 함께 말한다


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
    events_tomorrow: tuple[CalendarEvent, ...] = ()
    routine_items: tuple[str, ...] = ()  # 내일 진행할 루틴 항목 이름 (시간 변경·빼기 요청 확인용)
    routine_item_secs: tuple[int, ...] = ()  # routine_items와 같은 순서의 현재 시간(초). 비어 있으면 시간 없이 이름만 보여 준다
    allow_skip_coaching: bool = True   # 사용자가 먼저 '코칭 안 할래'라고 하면 받아들인다(정의서: 하루 한정 0 허용). AI가 먼저 권하지는 않는다


@dataclass(frozen=True)
class BriefingInput:
    persona: Persona = Persona()
    morning_call_type: str = "ROUTINE"  # ROUTINE / CONTENT. 알람은 둘 중 하나만이라 프롬프트에서는 content_name 유무로 구분한다
    program_name: str = ""             # 루틴 알람의 프리셋 이름 (전환 문장에 사용)
    content_name: str = ""             # 콘텐츠 알람의 콘텐츠 이름 (예: 퀴즈). 있으면 브리핑 뒤에 이어질 세션이 콘텐츠다
    coaching_skipped: bool = False     # 밤 대화에서 '내일 코칭 안 하기'에 동의한 날. True면 브리핑만 하고 이어서 시작하지 않는다
    now_hhmm: str = ""                 # 브리핑 시작 시각(KST) "HH:MM" — 이미 일어난 뒤라 알람 시각 대신 현재 시각을 말한다
    target_wake_time: str = ""         # (지시문에서는 쓰지 않음. 서버 호환용으로 남김)
    events_today: tuple[CalendarEvent, ...] = ()
    weather: Weather | None = None
    night: NightRecordView | None = None
    strategy_block: str = ""           # 개인화 Agent가 만든 [오늘의 전략] 문장(AgentDecision.briefing_block). 비어 있으면 프롬프트가 달라지지 않는다


@dataclass(frozen=True)
class CoachingInput:
    """단계를 하나씩 진행하는 세션의 입력. 기본 루틴 세션과 콘텐츠 세션이 같은 형식을 쓴다."""
    persona: Persona = Persona()
    morning_call_type: str = "ROUTINE"  # ROUTINE(기본 루틴 세션) / CONTENT(콘텐츠 세션) — 프롬프트의 호칭과 안내문만 달라진다
    steps: tuple[Step, ...] = ()       # 아직 PENDING인 단계만 (재연결이면 이어서라 번호가 1이 아닐 수 있다)
    resumed: bool = False              # True면 전체 안내를 이미 했으므로 생략
    night: NightRecordView | None = None
    strategy_block: str = ""           # 개인화 Agent가 만든 [오늘의 전략] 문장(AgentDecision.coaching_block). 비어 있으면 프롬프트가 달라지지 않는다
