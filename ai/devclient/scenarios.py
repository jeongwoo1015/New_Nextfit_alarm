"""가상 사용자 시나리오. 사용자 발화는 고정 스크립트라 AI의 말에 따라 바뀌지 않는다."""
from dataclasses import dataclass, field

from ..live.inputs import (BriefingInput, CalendarEvent, CoachingInput, NightInput, NightRecordView, Persona,
                           Step, Weather)
from ..tasks.content_steps import fallback_steps
from ..tasks.routine_changes import RoutineItem

EVENTS = (CalendarEvent("주간 회의", "09:30", "10:30"), CalendarEvent("발표 리허설", "15:00", "16:00"),
          CalendarEvent("치과", "18:30", "19:30"))
WEATHER = Weather("비", temp_c=11, high_c=14, low_c=8, precip_prob=80)
NIGHT_REC = NightRecordView(ai_summary="회의가 많아 피곤했지만 발표 준비를 마무리함", tomorrow_goal="발표 잘 마치기",
                            tomorrow_notes=("오전 10시 반 치과",), condition_label="TIRED", condition_text="지쳤지만 뿌듯함",
                            encouragement="오늘도 수고 많았어요", insight="준비한 만큼 충분히 해낼 수 있어요")
ROUTINE_ITEMS = [RoutineItem("id-stretch", "스트레칭"), RoutineItem("id-sun", "햇빛 보기"), RoutineItem("id-water", "물 한 잔"),
                 RoutineItem("id-read", "독서"), RoutineItem("id-diary", "모닝일기")]


@dataclass
class Scenario:
    name: str
    kind: str                    # night / briefing / coaching
    inp: object
    utterances: tuple = ()
    tone: str = "FORMAL"
    expect: dict = field(default_factory=dict)
    routine_items: list | None = None   # 항목 빼기 파싱을 확인할 때


def _night(**kw):
    return NightInput(persona=Persona("예준", "FORMAL", "KIND"), now_hhmm="22:10", tomorrow_wake_time="07:00",
                      tomorrow_coaching_min=20, **kw)


QUIZ = fallback_steps("QUIZ", "GENERAL", 10)
COACH_STEPS = (Step(1, "물 한 잔", 60, "QA"), Step(2, "스트레칭", 120, "PLAY", "1. 목을 천천히 좌우로 돌려요.\n2. 어깨를 크게 돌려요."),
               Step(3, "오늘 할 일 3개 말하기", 120, "SPEECH"))

SCENARIOS = {s.name: s for s in [
    Scenario("night_events", "night", _night(events_today=EVENTS),
             ("발표는 잘 끝났는데 많이 피곤하네요", "내일은 아침 열 시 반에 치과 예약이 있어요", "발표를 잘 마치는 게 내일 목표예요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"],
                     "extract": {"override_wake_time": None, "override_coaching_min": None,
                                 "tomorrow_goal": lambda v: bool(v) and "발표" in v and len(v) <= 30 and "예요" not in v}}),
    Scenario("night_override_consent", "night", _night(events_today=EVENTS),
             ("오늘 너무 피곤했어요", "내일은 알람을 일곱 시 반으로 늦추고 코칭은 십 분만 하고 싶어요", "네 맞아요 그렇게 해 주세요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"],
                     "extract": {"override_wake_time": "07:30", "override_coaching_min": 10}}),
    Scenario("night_override_decline", "night", _night(events_today=EVENTS),
             ("오늘 많이 피곤했어요", "아니요 내일 알람이랑 코칭은 그대로 둘게요", "내일은 특별한 일정은 없어요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"],
                     "extract": {"override_wake_time": None, "override_coaching_min": None}}),
    Scenario("night_routine_remove", "night", _night(events_today=EVENTS, routine_items=tuple(i.name for i in ROUTINE_ITEMS)),
             ("오늘은 괜찮았어요", "내일부터 독서는 아침 루틴에서 빼 주세요", "네 맞아요"),
             expect={"routine_remove": ["id-read"], "extract": {"override_wake_time": None}}, routine_items=ROUTINE_ITEMS),
    Scenario("briefing_full", "briefing",
             BriefingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="ROUTINE", program_name="현실 모닝루틴",
                           coaching_min=20, target_wake_time="07:00", events_today=EVENTS, weather=WEATHER, night=NIGHT_REC),
             expect={"coaching_min": 20, "weather_words": ["비", "우산"], "event_words": ["회의", "리허설"], "wake_words": ["7시", "일곱"]}),
    Scenario("briefing_casual_nocoach", "briefing",
             BriefingInput(persona=Persona("예준", "CASUAL", "FIRM", "MALE"), morning_call_type="CONTENT", program_name="퀴즈",
                           coaching_min=0, target_wake_time="07:00", events_today=EVENTS, weather=WEATHER, night=None),
             tone="CASUAL", expect={"coaching_min": 0, "weather_words": ["비", "우산"], "event_words": ["회의", "리허설"]}),
    Scenario("briefing_no_data", "briefing",
             BriefingInput(persona=Persona("예준"), morning_call_type="ROUTINE", program_name="현실 모닝루틴", coaching_min=20,
                           target_wake_time="07:00"),
             expect={"coaching_min": 20, "wake_words": ["7시", "일곱"]}),
    Scenario("coaching_routine", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "스트레칭은 오늘 건너뛸게요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요"),
             expect={"verdicts": {1: "done", 2: "skipped", 3: "done"}, "step_names": [s.name for s in COACH_STEPS]}),
    Scenario("coaching_quiz", "coaching", CoachingInput(persona=Persona("예준"), steps=tuple(QUIZ)),
             ("서울이에요", "열두 달이요", "세종대왕이요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "step_names": [s.name for s in QUIZ],
                     "answers": ["서울", "열두 달", "세종대왕"]}),
]}
