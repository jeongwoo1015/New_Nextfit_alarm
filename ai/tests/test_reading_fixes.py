"""11/21 대화 안정화(로컬 전용 테스트): 어조 유지 규칙과 날씨 표현 변환이 프롬프트에 들어가는지 본다. 모델 호출은 없다."""
from ai.live.inputs import CoachingInput, Persona, Step, Weather
from ai.live.prompts.common import persona_block, plain_weather, weather_block
from ai.live.prompts.coaching import coaching_prompt

STEPS = (Step(1, "목 풀기", 45, "PLAY", "목을 천천히 돌려요."),)


def test_persona_block_keeps_tone_whatever_the_user_says():
    for tone in ("FORMAL", "CASUAL"):
        text = persona_block(Persona("예준", tone, "KIND"))
        assert "어조 유지" in text and "사용자의 말투를 따라 하지 않는다" in text


def test_coaching_prompt_reads_script_in_set_tone_even_if_user_speaks_otherwise():
    text = coaching_prompt(CoachingInput(persona=Persona("예준", "FORMAL", "KIND"), steps=STEPS))
    assert "존댓말 설정이면 그대로 읽고" in text
    assert "사용자가 반말로 말해도 존댓말 설정이면 존댓말로 다시 읽는다" in text
    assert "사용자가 반말로 말해도 존댓말 설정이면 위의 존댓말 문장을" in text      # 대답 안내 문장도 설정 어조 그대로


def test_hard_weather_words_become_plain():
    assert plain_weather("튼구름") == "구름 많음"
    assert plain_weather("튼 구름") == "구름 많음"
    assert plain_weather("온흐림") == "흐림"
    assert plain_weather("맑음") == "맑음"            # 모르는 값·쉬운 값은 그대로
    assert plain_weather(None) == ""


def test_weather_block_uses_plain_words():
    block = weather_block(Weather("튼구름", temp_c=4, high_c=7, low_c=1, precip_prob=10, location_name="하남시"))
    assert "구름 많음" in block and "튼구름" not in block
