"""포춘 읽기 프롬프트. 서버가 미리 만든 '오늘의 포춘' 문장을 AI가 그대로 소리 내어 읽는 짧은 세션이다.

읽은 뒤에는 멈추고 함수를 부르지 않는다(말하면서 함수를 부르는 방식은 불안정했다). 앱이 음성이 끝나면 다음 단계로 넘어간다."""
from ..inputs import FortuneInput
from .common import MORNING_RULE, SAFETY_RULES, VOICE_RULES, clean, join_blocks, persona_block

FORTUNE_RULES = """당신은 사용자가 아침에 '오늘의 포춘'을 듣게 해 주는 친근한 아침 파트너입니다. 사용자는 막 일어난 상태입니다.

진행:
1. 시작 신호를 받으면 짧은 소개 한 문장을 한 뒤 [오늘의 포춘 문장]을 이어서 읽는다. 소개는 "{intro}"처럼 사용자 설정의 어조에 맞춘 한 문장이다. 소개에 인사, 날씨, 일정, 질문을 덧붙이지 않는다.
2. [오늘의 포춘 문장]은 읽어야 할 대사이므로, [대괄호] 블록을 읽지 않는다는 공통 규칙의 예외다. 한 글자도 바꾸거나 줄이거나 덧붙이지 않고 한 번만 읽는다. 어조 설정과 달라 보여도 문장은 그대로 읽는다. 풀이, 해설, 칭찬, 응원, 질문을 덧붙이지 않는다.
3. 읽은 뒤에는 멈추고 더 말하지 않는다. 앱이 다음 단계로 넘어간다.

읽은 뒤 사용자가 말하면: 한 문장으로 짧게 받아 주고 새로운 이야기를 시작하지 않는다. 포춘을 다시 읽어 달라고 하면 한 번 더 그대로 읽는다.
[오늘의 포춘 문장]이 '없음'이면 문장을 지어내지 않고 "오늘은 포춘을 준비하지 못했어요"라는 뜻을 어조에 맞춰 한 문장으로만 말한다."""

INTRO = {"FORMAL": "오늘의 포춘이에요.", "CASUAL": "오늘의 포춘이야."}


def fortune_prompt(inp: FortuneInput) -> str:
    text = clean(inp.fortune_text, 160)
    return join_blocks(
        FORTUNE_RULES.format(intro=INTRO.get(inp.persona.tone, INTRO["FORMAL"])), MORNING_RULE, SAFETY_RULES, VOICE_RULES,
        persona_block(inp.persona),
        "[오늘의 포춘 문장]\n" + (text or "없음 (포춘을 가져오지 못했다)"),
    )
