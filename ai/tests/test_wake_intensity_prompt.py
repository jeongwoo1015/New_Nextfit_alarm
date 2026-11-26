"""11/26: 깨우는 말 강도는 프롬프트 밖(서버가 부르는 intensity.today_intensity)에서만 정한다. 프롬프트는 통화 중에 강도를 올리지 않는다."""
from ai.live.inputs import Persona
from ai.live.prompts.common import REWAKE_NOTICE, wake_intensity_block


def test_prompt_uses_the_given_intensity_and_never_raises_it_inside_the_call():
    for auto in (True, False):
        for n in (1, 2, 3):
            b = wake_intensity_block(Persona(personality="FRIENDLY", intensity=n, auto_intensity=auto))
            assert f"지금 강도: {n}" in b and REWAKE_NOTICE in b and "지금 강도 그대로 유지" in b
            assert "그 뒤로 이 통화의 깨우는 말은" not in b and "강도는 그대로)" not in b
