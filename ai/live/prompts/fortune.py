"""포춘 읽기 프롬프트. 서버가 미리 만든 '오늘의 포춘' 문장을 AI가 그대로 소리 내어 읽는 짧은 세션이다.

읽은 뒤에는 멈추고 함수를 부르지 않는다(말하면서 함수를 부르는 방식은 불안정했다). 앱이 음성이 끝나면 다음 단계로 넘어간다.
순서표에서 포춘이 마지막이면 읽은 뒤 작별 인사 한 문장으로 아침 대화를 마친다."""
from ..inputs import FortuneInput
from ...tasks.morning_plan import COACHING, FORTUNE, neighbors
from .common import MORNING_RULE, SAFETY_RULES, VOICE_RULES, clean, join_blocks, morning_flow, persona_block, resolve_plan

FORTUNE_RULES = """당신은 사용자가 아침에 '오늘의 포춘'을 듣게 해 주는 친근한 아침 파트너입니다. 사용자는 막 일어난 상태입니다.

진행:
1. 시작 신호를 받으면 짧은 소개 한 문장을 한 뒤 [오늘의 포춘 문장]을 이어서 읽는다. 소개는 "{intro}" 이 문장을 그대로 쓴다(이름을 넣거나 말을 바꾸지 않는다). 소개에 인사, 날씨, 일정, 질문을 덧붙이지 않는다.
2. [오늘의 포춘 문장]은 읽어야 할 대사이므로, [대괄호] 블록을 읽지 않는다는 공통 규칙의 예외다. 한 글자도 바꾸거나 줄이거나 덧붙이지 않고 한 번만 읽는다. 어조 설정과 달라 보여도 문장은 그대로 읽는다. 풀이, 해설, 칭찬, 응원, 질문을 덧붙이지 않는다.
3. {after_reading}

읽은 뒤 사용자가 말하면: 한 문장으로 짧게 받아 주고 새로운 이야기를 시작하지 않는다. 포춘을 다시 읽어 달라고 하면 한 번 더 그대로 읽는다.
[오늘의 포춘 문장]이 '없음'이면 문장을 지어내지 않고 "오늘은 포춘을 준비하지 못했어요"라는 뜻을 어조에 맞춰 한 문장으로만 말한다."""

AFTER_NEXT = "읽은 뒤에는 멈추고 더 말하지 않는다. 앱이 다음 단계로 넘어간다."
# 포춘이 아침의 마지막 단계일 때(새 순서): 읽은 뒤 작별 인사 한 문장으로 아침 대화를 마친다
AFTER_LAST = ("포춘이 오늘 아침 대화의 마지막 순서다. 문장을 다 읽은 뒤 어조에 맞춘 짧은 작별 인사 한 문장(예: \"{farewell}\")을 덧붙이고 멈춘다. "
              "이 인사는 2번 규칙(포춘 문장에 덧붙이지 않기)의 예외이며, 포춘을 풀이하거나 질문하지 않는다. 함수는 부르지 않는다(앱이 음성이 끝나면 통화를 마친다).")
FAREWELL = {"FORMAL": "오늘 하루도 응원할게요, 좋은 하루 보내세요.", "CASUAL": "오늘 하루도 응원할게, 좋은 하루 보내."}

# 소개 문장: 포춘을 만든 재료가 있으면 그것을 밝혀 앞 단계(어젯밤 한마디 듣기)와 이어지게 한다
INTRO = {
    "FORMAL": {"": "오늘의 포춘이에요.", "ONE_LINER": "어젯밤 남긴 한마디로 만든 오늘의 포춘이에요.", "DIARY": "어젯밤 이야기로 만든 오늘의 포춘이에요."},
    "CASUAL": {"": "오늘의 포춘이야.", "ONE_LINER": "어젯밤 남긴 한마디로 만든 오늘의 포춘이야.", "DIARY": "어젯밤 이야기로 만든 오늘의 포춘이야."},
}
BASED_ON = {"ONE_LINER": "ONE_LINER", "ONE-LINER": "ONE_LINER", "VOICE": "ONE_LINER", "DIARY": "DIARY"}      # compose_fortune의 used_input("voice"=받아쓴 한마디 / "diary" / "none")도 그대로 받는다


def intro_sentence(inp: FortuneInput) -> str:
    by_tone = INTRO.get(inp.persona.tone, INTRO["FORMAL"])
    text = by_tone[BASED_ON.get((inp.based_on or "").strip().upper(), "")]
    prev, _ = neighbors(resolve_plan(inp, FORTUNE), FORTUNE)
    return "마지막으로 " + text if prev == COACHING else text      # 콘텐츠·루틴을 마친 뒤 마지막 순서로 듣는 포춘


def fortune_prompt(inp: FortuneInput) -> str:
    text = clean(inp.fortune_text, 160)
    plan = resolve_plan(inp, FORTUNE)
    _, nxt = neighbors(plan, FORTUNE)
    tone = inp.persona.tone if inp.persona.tone in FAREWELL else "FORMAL"
    after = AFTER_NEXT if nxt else AFTER_LAST.format(farewell=FAREWELL[tone])
    return join_blocks(
        FORTUNE_RULES.format(intro=intro_sentence(inp), after_reading=after), morning_flow(plan, FORTUNE), MORNING_RULE, SAFETY_RULES, VOICE_RULES,
        persona_block(inp.persona),
        "[오늘의 포춘 문장]\n" + (text or "없음 (포춘을 가져오지 못했다)"),
    )
