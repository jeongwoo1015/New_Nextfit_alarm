"""11/26 피드백: 밤에 "내일 챙길 일정·걱정"으로 말한 것(tomorrow_notes)은 캘린더가 비어 있어도 아침 브리핑에서 빠짐없이 리마인드한다."""
from ai.live.inputs import BriefingInput, CalendarEvent, NightRecordView, Persona
from ai.live.prompts.briefing import briefing_prompt
from ai.personalization.bandit import ARMS
from ai.personalization.strategy import strategy_block

NOTE = "오전 11시까지 장학금 서류 학과 사무실 제출"
NIGHT = NightRecordView(ai_summary="과제를 많이 해서 뿌듯함", tomorrow_goal="과제 마무리하기", tomorrow_notes=(NOTE,))


def _p(**kw):
    return briefing_prompt(BriefingInput(persona=Persona("예준"), program_name="현실 모닝루틴", now_hhmm="07:02", **kw))


def test_notes_become_a_separate_must_say_block_even_without_calendar():
    p = _p(night=NIGHT)
    assert "[오늘 챙길 일 (어젯밤 사용자가 직접 말함, 캘린더와 별개)]\n- " + NOTE in p
    assert "[오늘 챙길 일]이 있으면 하나도 빼지 않고 반드시 말한다" in p and "리마인드" in p
    assert "캘린더에 등록된 일정이 없다. [오늘 챙길 일]은 이와 별개로 반드시 말한다" in p      # "일정이 없다"로 끝내지 않게
    assert "오늘 특이사항: 아래 [오늘 챙길 일] 참고" in p and p.count(NOTE) == 1                # 어젯밤 기록 블록에 같은 내용을 두 번 넣지 않는다
    assert "c에서 말한 챙길 일은 되풀이하지 않는다" in p


def test_with_calendar_events_both_are_kept():
    p = _p(night=NIGHT, events_today=(CalendarEvent("주간 회의", "09:30", "10:30"),))
    assert "주간 회의" in p and NOTE in p and "캘린더에 등록된 일정이 없다" not in p


def test_short_briefing_strategy_still_must_say_the_notes():
    p = _p(night=NIGHT, strategy_block=strategy_block(ARMS["push"], "briefing"))
    assert "오늘은 짧게" in p and "짧게 하는 날에도 빼지 않는다" in p and NOTE in p


def test_without_notes_nothing_changes():
    p = _p(night=NightRecordView(ai_summary="평범한 하루", tomorrow_goal="", tomorrow_notes=()))
    assert "[오늘 챙길 일 (" not in p and "없음 (등록된 일정이 없거나 가져오지 못했다)" in p and "오늘 특이사항: 없음" in p
    q = _p(night=None)
    assert "[오늘 챙길 일 (" not in q and "밤 대화를 하지 않았다" in q
