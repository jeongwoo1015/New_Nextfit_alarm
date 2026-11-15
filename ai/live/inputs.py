"""서버가 AI 쪽에 넘기는 입력 형식.

컬럼 이름과 값 목록은 DB 정의서(11/01수정판)를 따른다. 일정(캘린더)과 날씨는 DB에 저장하지 않는
실행 시점 입력이며 정의서에는 없는 값이다(팀 확인 필요).
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Persona:
    """RS_USER_SETTING: USER_CALL_NAME, TONE(FORMAL/CASUAL), SPEECH_STYLE(KIND/FRIENDLY/FIRM), VOICE_TYPE(FEMALE/MALE/NEUTRAL)
    + 11/13 성격·강도(DB 새 칸 제안: PERSONALITY, INTENSITY, AUTO_INTENSITY). 서버가 앞의 4칸을 순서대로 넣으므로 새 칸은 맨 뒤에만 둔다."""
    call_name: str = ""
    tone: str = "FORMAL"
    speech_style: str = "KIND"         # 예전 값. personality가 비어 있으면 이 값으로 성격을 정한다(FIRM → STRICT)
    voice_type: str = "FEMALE"
    personality: str = ""              # KIND 다정한 / FRIENDLY 친근한 / STRICT 엄격한(화면 이름 "무서운"도 가능) / CHEERFUL 유쾌한
    intensity: int = 2                 # 깨우는 말의 강도 1 약 / 2 중 / 3 강. 서버가 자동 강도 올리기를 반영한 그날 값(ai.personalization.intensity)을 넣는다
    auto_intensity: bool = True        # 자동 강도 올리기. False면 통화 중 재기상 알림에도 강도를 올리지 않는다


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
    one_liner_follows: bool = False    # 브리핑 뒤에 앱이 어젯밤 남긴 한마디를 틀어 주면 True. 콘텐츠 알람의 마무리 문장이 이어질 순서(한마디 → 포춘 → 콘텐츠)를 알린다
    now_hhmm: str = ""                 # 브리핑 시작 시각(KST) "HH:MM" — 이미 일어난 뒤라 알람 시각 대신 현재 시각을 말한다
    target_wake_time: str = ""         # (지시문에서는 쓰지 않음. 서버 호환용으로 남김)
    events_today: tuple[CalendarEvent, ...] = ()
    weather: Weather | None = None
    night: NightRecordView | None = None
    strategy_block: str = ""           # 개인화 Agent가 만든 [오늘의 전략] 문장(AgentDecision.briefing_block). 비어 있으면 프롬프트가 달라지지 않는다
    plan: tuple[str, ...] = ()         # 모닝콜 순서표(ai.tasks.morning_plan). 비어 있으면 지금 앱 순서(LEGACY_ORDER)를 one_liner_follows·coaching_skipped·already_done으로 만든다
    already_done: bool = False         # 오늘 루틴·콘텐츠를 이미 모두 마친 날(앱 재진입 등). 브리핑에서 루틴 안내·시작 질문 없이 짧게 칭찬만 한다


@dataclass(frozen=True)
class FortuneInput:
    """'오늘의 포춘' 읽기 세션의 입력. 문장은 tasks.fortune.compose_fortune이 만든 것을 서버가 그대로 넘기고, AI는 그대로 읽는다."""
    persona: Persona = Persona()
    fortune_text: str = ""             # 읽을 포춘 문장 (비어 있으면 읽을 것이 없다고만 말한다)
    style: str = ""                    # COOKIE / HOROSCOPE (참고용, 지시문 동작은 같다)
    plan: tuple[str, ...] = ()         # 모닝콜 순서표. 포춘이 마지막이면 읽은 뒤 작별 인사 한 문장으로 아침 대화를 끝낸다. 비어 있으면 지금 앱 순서(포춘 다음 코칭)
    based_on: str = ""                 # 문장을 만든 재료: ONE_LINER(어젯밤 한마디) / DIARY(한 줄 일기). compose_fortune 결과의 used_input(voice / diary / none)을 그대로 넘겨도 된다. 소개 문장이 "어젯밤 한마디로 만든 …"으로 바뀐다


@dataclass(frozen=True)
class CoachingInput:
    """단계를 하나씩 진행하는 세션의 입력. 기본 루틴 세션과 콘텐츠 세션이 같은 형식을 쓴다."""
    persona: Persona = Persona()
    morning_call_type: str = "ROUTINE"  # ROUTINE(기본 루틴 세션) / CONTENT(콘텐츠 세션) — 프롬프트의 호칭과 안내문만 달라진다
    steps: tuple[Step, ...] = ()       # 아직 PENDING인 단계만 (재연결이면 이어서라 번호가 1이 아닐 수 있다)
    resumed: bool = False              # True면 전체 안내를 이미 했으므로 생략
    night: NightRecordView | None = None
    strategy_block: str = ""           # 개인화 Agent가 만든 [오늘의 전략] 문장(AgentDecision.coaching_block). 비어 있으면 프롬프트가 달라지지 않는다
    content_type: str = ""             # CONTENT 세션의 콘텐츠 종류(EXERCISE/QUIZ/LANGUAGE 등, 알람의 content_type). 비어 있으면 단계 내용으로 추정한다
    content_option: str = ""           # 콘텐츠 세부(알람의 content_option 또는 compose_content_steps 결과의 option)
    plan: tuple[str, ...] = ()         # 모닝콜 순서표. 첫 연결 문장(앞 단계)과 마무리(다음이 포춘이면 포춘으로 넘김)에 쓴다. 비어 있으면 지금 앱 순서(포춘 다음 코칭, 코칭이 마지막)
    stretch_routines: tuple = ()       # 스트레칭 부위별 루틴 ((부위, (Step, ...)), ...) = ai.tasks.stretch_sets.all_area_steps(). 있으면 AI가 "오늘 어디가 불편하세요?"로 부위를 묻고 select_stretch_area를 부른다. 없으면 steps 그대로 진행


def content_kind(inp) -> str:
    """콘텐츠 세션이 어떤 콘텐츠인지: STRETCH / ENGLISH / WORDCHAIN / QUIZ, 알 수 없으면 "".
    서버가 content_type·content_option을 넘기면 그 값을 쓰고, 비어 있으면 단계 문장의 형식('정답:', '질문:' 등)으로 추정한다."""
    if getattr(inp, "morning_call_type", "ROUTINE") != "CONTENT":
        return ""
    scripts = [(s.script or "") for s in inp.steps]
    if inp.content_option == "WORDCHAIN" or any(sc.startswith("게임: 끝말잇기") for sc in scripts):
        return "WORDCHAIN"
    if inp.content_type == "QUIZ" or any("정답:" in sc for sc in scripts):
        return "QUIZ"
    if (inp.content_type == "LANGUAGE" and inp.content_option in ("", "ENGLISH")) or any(sc.startswith("질문:") and "예시:" in sc for sc in scripts):
        return "ENGLISH"
    if inp.content_type == "EXERCISE" or (not inp.content_type and inp.steps and all(st.response_type == "PLAY" for st in inp.steps)):
        return "STRETCH"           # 종류를 안 넘겨도 모든 단계가 따라 하기(PLAY)면 스트레칭으로 본다(명상은 content_type=MEDITATION으로 구분)
    return ""
