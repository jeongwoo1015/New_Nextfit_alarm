"""가상 사용자 시나리오. 사용자 발화는 고정 스크립트라 AI의 말에 따라 바뀌지 않는다."""
import re
from dataclasses import dataclass, field, replace

from ..live.inputs import (BriefingInput, CalendarEvent, CoachingInput, NightInput, NightRecordView, Persona,
                           Step, Weather)
from ..personalization.strategy import Strategy, strategy_block
from ..tasks.content_steps import fallback_steps
from ..tasks.routine_changes import RoutineItem

EVENTS = (CalendarEvent("주간 회의", "09:30", "10:30"), CalendarEvent("발표 리허설", "15:00", "16:00"),
          CalendarEvent("치과", "18:30", "19:30"))
WEATHER = Weather("비", temp_c=11, high_c=14, low_c=8, precip_prob=80, location_name="광진구")
NIGHT_REC = NightRecordView(ai_summary="회의가 많아 피곤했지만 발표 준비를 마무리함", tomorrow_goal="발표 잘 마치기",
                            tomorrow_notes=("오전 10시 반 치과",), condition_label="TIRED", condition_text="지쳤지만 뿌듯함",
                            encouragement="오늘도 수고 많았어요", insight="준비한 만큼 충분히 해낼 수 있어요")
ROUTINE_ITEMS = [RoutineItem("id-stretch", "스트레칭", True, 120), RoutineItem("id-sun", "햇빛 보기", True, 60), RoutineItem("id-water", "물 한 잔", True, 60),
                 RoutineItem("id-read", "독서", True, 300), RoutineItem("id-diary", "모닝일기", True, 180)]


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
                      **kw)


ROUTINE_NAMES = tuple(i.name for i in ROUTINE_ITEMS)
ROUTINE_SECS = tuple(i.duration_sec for i in ROUTINE_ITEMS)

QUIZ = fallback_steps("QUIZ", "GENERAL")
COACH_STEPS = (Step(1, "물 한 잔", 60, "QA"), Step(2, "스트레칭", 120, "PLAY", "1. 목을 천천히 좌우로 돌려요.\n2. 어깨를 크게 돌려요."),
               Step(3, "오늘 할 일 3개 말하기", 120, "SPEECH"))

COACH_STEPS_MINUTES = (Step(1, "독서", 900, "PLAY"), Step(2, "스트레칭", 150, "PLAY", "1. 목을 천천히 좌우로 돌려요.\n2. 어깨를 크게 돌려요."), Step(3, "물 한 잔", 60, "QA"),
                       Step(4, "오늘 할 일 3개 말하기", 120, "SPEECH"))       # 15분, 2분 30초처럼 분 단위 시간이 많은 루틴
EVENTS_MINUTES = (CalendarEvent("주간 회의", "09:15", "10:45"), CalendarEvent("치과", "18:45", "19:30"))                 # 15분·45분처럼 분이 있는 일정

COACH_STEPS_RESUMED = tuple(Step(s.order + 3, s.name, s.planned_sec, s.response_type, s.script) for s in COACH_STEPS)   # 재연결: 앞 3단계를 끝내고 남은 단계 (번호가 4부터)

SCENARIOS = {s.name: s for s in [
    Scenario("night_events", "night", _night(events_today=EVENTS),
             ("발표는 잘 끝났는데 많이 피곤하네요", "회의가 길어서 좀 지치긴 했어요", "네 맞아요 그런 하루였어요", "내일은 발표 자료를 마무리하는 게 목표예요",
              "아침 열 시 반에 치과 예약이 있어요", "이제 잘게요 잘 자요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"], "farewell": True, "diary": True, "asks_tomorrow": True,
                     "extract": {"override_wake_time": None, "override_skip_coaching": False,
                                 "tomorrow_goal": lambda v: bool(v) and "발표" in v,
                                 "tomorrow_notes": lambda v: any("치과" in x for x in (v or []))}}),
    Scenario("night_override_consent", "night", _night(events_today=EVENTS),
             ("오늘 너무 피곤했어요", "내일은 알람을 일곱 시 반으로 늦추고 코칭은 쉬고 싶어요", "네 맞아요 그렇게 해 주세요", "고마워요 이제 잘게요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"], "farewell": True, "diary": True,
                     "extract": {"override_wake_time": "07:30", "override_skip_coaching": True}}),
    Scenario("night_override_decline", "night", _night(events_today=EVENTS),
             ("오늘 많이 피곤했어요", "아니요 내일 알람이랑 코칭은 그대로 둘게요", "이제 잘게요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"], "farewell": True,
                     "extract": {"override_wake_time": None, "override_skip_coaching": False}}),
    Scenario("night_ignore_proposal", "night", _night(events_today=EVENTS),
             ("오늘 너무 피곤했어요", "발표는 잘 끝났어요", "그냥 좀 쉬고 싶어요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"],
                     "extract": {"override_wake_time": None, "override_skip_coaching": False}}),   # 제안에 답하지 않았으면 변경으로 기록되면 안 된다
    Scenario("night_routine_remove", "night", _night(events_today=EVENTS, routine_items=ROUTINE_NAMES, routine_item_secs=ROUTINE_SECS),
             ("오늘은 괜찮았어요", "내일은 독서를 아침 루틴에서 빼 주세요", "네 맞아요", "이제 잘게요"), routine_items=ROUTINE_ITEMS,
             expect={"routine_changes": [("id-read", 0, "TOMORROW")], "farewell": True, "extract": {"override_wake_time": None}}),
    Scenario("night_routine_time", "night", _night(events_today=EVENTS, routine_items=ROUTINE_NAMES, routine_item_secs=ROUTINE_SECS),
             ("오늘은 괜찮았어요", "내일은 스트레칭을 1분으로 줄여 주세요", "네 맞아요", "이제 잘게요"), routine_items=ROUTINE_ITEMS,
             expect={"routine_changes": [("id-stretch", 60, "TOMORROW")], "farewell": True, "extract": {"override_wake_time": None}}),
    Scenario("night_routine_always", "night", _night(events_today=EVENTS, routine_items=ROUTINE_NAMES, routine_item_secs=ROUTINE_SECS),
             ("오늘은 괜찮았어요", "앞으로 스트레칭은 계속 3분으로 해 주세요", "네 맞아요", "이제 잘게요"), routine_items=ROUTINE_ITEMS,
             expect={"routine_changes": [("id-stretch", 180, "ALWAYS")], "farewell": True, "extract": {"override_wake_time": None}}),
    Scenario("night_quiet_day", "night", _night(),      # 일정이 없고 별일 없다고 하는 날: 작은 일을 물어 일기 재료를 얻는다
             ("별일 없었어요", "점심에 김치찌개 먹은 거 정도요", "맛있었어요", "네 맞아요", "이제 잘게요"),
             expect={"farewell": True, "diary": True, "asks_small": True, "asks_summary": True,
                     "extract": {"diary_text": lambda v: bool(v) and "김치찌개" in v}}),
    Scenario("night_quiet_day_vague", "night", _night(),      # 끝까지 막연하게만 답하는 날: AI가 구체적인 질문을 해서 일기 재료를 끌어내는가
             ("별일 없었어요", "그냥 평범했어요", "딱히 없어요", "네 맞아요", "이제 잘게요"),
             expect={"farewell": True, "diary": True, "asks_small": True, "asks_summary": True}),
    Scenario("night_wrap_up", "night", _night(),      # 이야기를 다 마친 뒤 "없어요", "그래", "알았다고"처럼 짧게만 답하는 날: 덕담을 되풀이하지 않고 끝나는가
             ("별일 없었어요", "점심에 김치찌개 먹은 거 정도요", "맛있었어요", "네 맞아요", "없어요", "없어요", "응 그래요", "알았다고"),
             expect={"ends_cleanly": True, "asks_summary": True}),
    Scenario("night_quiet_day_declines", "night", _night(),      # 말하기 싫어하면 더 캐묻지 않고 지어내지도 않는다
             ("별일 없었어요", "정말 없어요 그냥 피곤해요", "네 맞아요", "이제 잘게요"),
             expect={"farewell": True, "asks_summary": True, "small_stop_from": 2,
                     "extract": {"diary_text": lambda v: not v or not re.search(r"김치|점심|산책|친구|회의|발표", v)}}),
    Scenario("briefing_times", "briefing",    # 시각·일정 시각의 분을 "열다섯 분"이 아니라 숫자로 말하는가
             BriefingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="ROUTINE", program_name="현실 모닝루틴",
                           now_hhmm="07:15", events_today=EVENTS_MINUTES, weather=WEATHER, night=None),
             ("네 잘 잤어요", "네 준비됐어요"),
             expect={"event_words": ["회의", "치과"], "expect_start": True}),
    Scenario("briefing_full", "briefing",
             BriefingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="ROUTINE", program_name="현실 모닝루틴",
                           now_hhmm="07:02", events_today=EVENTS, weather=WEATHER, night=NIGHT_REC),
             ("네 잘 잤어요", "네 준비됐어요"),
             expect={"weather_words": ["비", "우산"], "location_words": ["광진구"], "event_words": ["회의", "리허설"], "now_words": ["7시", "일곱"], "expect_start": True}),
    Scenario("briefing_to_content", "briefing",
             BriefingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="CONTENT", content_name="퀴즈",
                           now_hhmm="07:02", events_today=EVENTS, weather=WEATHER, night=NIGHT_REC),
             ("네 잘 잤어요", "네 퀴즈 시작할 준비됐어요"),
             expect={"weather_words": ["비", "우산"], "event_words": ["회의", "리허설"], "now_words": ["7시", "일곱"],
                     "ready_words": ["퀴즈"], "expect_start": True}),
    Scenario("briefing_casual_nocoach", "briefing",
             BriefingInput(persona=Persona("예준", "CASUAL", "FIRM", "MALE"), morning_call_type="CONTENT", content_name="퀴즈",
                           coaching_skipped=True, now_hhmm="07:02", events_today=EVENTS, weather=WEATHER, night=None),
             ("응 잘 잤어", "고마워 이만 끊을게"), tone="CASUAL",
             expect={"coaching_skipped": True, "weather_words": ["비", "우산"], "event_words": ["회의", "리허설"], "now_words": ["7시", "일곱"], "farewell": True}),
    Scenario("briefing_no_data", "briefing",
             BriefingInput(persona=Persona("예준"), morning_call_type="ROUTINE", program_name="현실 모닝루틴", now_hhmm="07:02"),
             ("네 잘 잤어요", "네 준비됐어요"),
             expect={"now_words": ["7시", "일곱"], "expect_start": True}),
    Scenario("briefing_sleepy", "briefing",   # 졸리다고 답해도 답은 답이다: 재촉 없이 브리핑까지 간다
             BriefingInput(persona=Persona("예준"), morning_call_type="ROUTINE", program_name="현실 모닝루틴", now_hhmm="07:02", events_today=EVENTS),
             ("음... 아직 많이 졸려요", "네 준비됐어요"),
             expect={"now_words": ["7시", "일곱"], "event_words": ["회의", "리허설"], "expect_start": True}),
    Scenario("coaching_routine", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "스트레칭은 오늘 건너뛸게요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "skipped", 3: "done"}, "step_names": [s.name for s in COACH_STEPS], "farewell": True}),
    Scenario("coaching_minutes", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS_MINUTES, night=NIGHT_REC),
             ("독서 했어요", "스트레칭 했어요", "물 한 잔 마셨어요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done", 4: "done"}, "step_names": [s.name for s in COACH_STEPS_MINUTES], "encourages": True, "farewell": True}),
    Scenario("coaching_time_change", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "스트레칭은 오늘 1분만 할게요", "스트레칭 했어요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "adjust": {2: 60}, "no_verdict_turns": (2,),
                     "step_names": [s.name for s in COACH_STEPS], "farewell": True}),
    Scenario("coaching_time_ask", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "스트레칭은 오늘 좀 적게 할게요", "1분으로 할게요", "스트레칭 했어요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "adjust": {2: 60}, "no_verdict_turns": (2, 3),
                     "step_names": [s.name for s in COACH_STEPS], "farewell": True}),
    Scenario("coaching_remove_today", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "마지막 오늘 할 일 말하기는 오늘 빼 주세요", "스트레칭 했어요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done"}, "adjust": {3: 0}, "no_verdict_turns": (2,),
                     "step_names": [s.name for s in COACH_STEPS], "farewell": True}),
    Scenario("content_session", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", steps=tuple(QUIZ)),
             ("서울이에요", "열두 달이요", "세종대왕이요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "step_names": [s.name for s in QUIZ], "answers": ["서울", "열두 달", "세종대왕"], "farewell": True}),
    Scenario("coaching_resumed", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS_RESUMED, resumed=True, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "스트레칭은 오늘 건너뛸게요", "오늘 할 일은 보고서 쓰기, 운동하기, 장보기예요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {4: "done", 5: "skipped", 6: "done"}, "step_names": [s.name for s in COACH_STEPS_RESUMED], "farewell": True}),
    Scenario("coaching_quiz", "coaching", CoachingInput(persona=Persona("예준"), steps=tuple(QUIZ)),
             ("서울이에요", "열두 달이요", "세종대왕이요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "step_names": [s.name for s in QUIZ],
                     "answers": ["서울", "열두 달", "세종대왕"], "farewell": True}),
]}


def _with_strategy(base: str, name: str, strategy: Strategy, kind: str, **expect_extra) -> Scenario:
    """기존 시나리오에 개인화 전략 블록만 붙인 것. 같은 사용자 발화로 전략이 말하는 방식을 바꾸는지 비교한다."""
    b = SCENARIOS[base]
    block = strategy_block(strategy, kind)
    return Scenario(name, b.kind, replace(b.inp, strategy_block=block), b.utterances, b.tone, {**b.expect, **expect_extra}, b.routine_items)


SCENARIOS.update({s.name: s for s in [
    _with_strategy("briefing_full", "briefing_short", Strategy(briefing_length="short", strategy_id="short"), "briefing", short_briefing=True),
    _with_strategy("briefing_full", "briefing_cheer", Strategy(encouragement="high", strategy_id="cheer"), "briefing"),
    _with_strategy("coaching_routine", "coaching_soft", Strategy(coaching_intensity="soft", strategy_id="gentle"), "coaching"),
    _with_strategy("coaching_routine", "coaching_firm", Strategy(coaching_intensity="firm", strategy_id="push"), "coaching"),
]})
