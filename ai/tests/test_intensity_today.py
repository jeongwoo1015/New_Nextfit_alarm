"""11/26 회의 4-5: 깨우는 말 강도는 당일 상태 기준. 그날 미루기·재기상이 있으면 다음 통화부터 +1(최대 3), 다음 날은 다시 기본. 통화 중에는 바꾸지 않는다."""
from datetime import date

from ai.live.inputs import Persona
from ai.live.prompts.common import REWAKE_NOTICE, wake_intensity_block
from ai.personalization.intensity import WakeDay, today_counts, today_intensity

D = date(2026, 11, 26)


def test_base_is_the_users_choice_and_old_history_is_ignored():
    assert [today_intensity(n) for n in (1, 2, 3)] == [1, 2, 3]
    assert today_intensity(1, [WakeDay(True)] * 6) == 1             # 지난날 힘들었던 기록으로 올리지 않는다(옛 호출 방식도 그대로 받는다)
    assert today_intensity(None) == 2 and today_intensity(9) == 3 and today_intensity(0) == 1


def test_snooze_or_rewake_today_raises_one_level_until_tomorrow():
    assert today_intensity(1, snoozes_today=1) == 2
    assert today_intensity(1, rewakes_today=1) == 2
    assert today_intensity(1, snoozes_today=3, rewakes_today=2) == 2   # 횟수가 늘어도 한 단계까지만
    assert today_intensity(3, snoozes_today=1) == 3                    # 최대 3
    assert today_intensity(2, snoozes_today=1, auto=False) == 2        # 자동 강도를 끄면 그대로


def test_today_counts_only_counts_today():
    class S:
        def __init__(self, d, sn, rw):
            self.session_date, self.snooze_count, self.rewake_count = d, sn, rw
    sessions = [S(date(2026, 11, 25), 2, 1), S(D, 1, 0), S(D, 0, 2), S(D, None, None)]
    assert today_counts(sessions, D) == (1, 2) and today_counts([], D) == (0, 0) and today_counts(None, D) == (0, 0)


def test_prompt_no_longer_raises_intensity_inside_the_call():
    for auto in (True, False):
        b = wake_intensity_block(Persona(personality="FRIENDLY", intensity=2, auto_intensity=auto))
        assert "지금 강도: 2" in b and REWAKE_NOTICE in b and "지금 강도 그대로 유지" in b
        assert "강도 3(강)로" not in b and "그 뒤로 이 통화의 깨우는 말은" not in b
