"""가상 사용자 시나리오. 사용자 발화는 고정 스크립트라 AI의 말에 따라 바뀌지 않는다."""
import re
from dataclasses import dataclass, field, replace
from datetime import date

from ..live.inputs import (BriefingInput, CalendarEvent, CoachingInput, FortuneInput, NightInput, NightRecordView, Persona,
                           Step, Weather)
from ..personalization.strategy import Strategy, strategy_block
from ..tasks.content_steps import english_fallback, fallback_steps, wordchain_steps
from ..tasks.stretch_sets import stretch_steps
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

QUIZ = fallback_steps("QUIZ", "HISTORY")             # 옛 방식의 기본 문제(서울·열두 달·세종대왕): 오래된 시나리오와 정답 누출 검사용
CDAY = date(2026, 11, 9)                      # 콘텐츠 시나리오의 고정 날짜(세트·문제가 항상 같게)
STRETCH = stretch_steps(CDAY)
PERSON_QUIZ = fallback_steps("QUIZ", "PERSON", CDAY)
PERSON_ANSWERS = [x.script.split("정답:")[1].strip() for x in PERSON_QUIZ]
ENGLISH = english_fallback(CDAY)
WORDCHAIN = wordchain_steps(CDAY)
WORDCHAIN_REPLY = {"기차": "차표", "사과": "과일", "바다": "다리", "나무": "무지개", "커피": "피아노", "여행": "행복", "하늘": "늘보", "오리": "리본"}   # 시작 단어 → 사용자가 이을 단어
WC_START = WORDCHAIN[0].script.split("시작 단어:")[1].strip()
COACH_STEPS = (Step(1, "물 한 잔", 60, "QA"), Step(2, "스트레칭", 120, "PLAY", "1. 목을 천천히 좌우로 돌려요.\n2. 어깨를 크게 돌려요."),
               Step(3, "오늘 할 일 3개 말하기", 120, "SPEECH"))

COACH_STEPS_MINUTES = (Step(1, "독서", 900, "PLAY"), Step(2, "스트레칭", 150, "PLAY", "1. 목을 천천히 좌우로 돌려요.\n2. 어깨를 크게 돌려요."), Step(3, "물 한 잔", 60, "QA"),
                       Step(4, "오늘 할 일 3개 말하기", 120, "SPEECH"))       # 15분, 2분 30초처럼 분 단위 시간이 많은 루틴
EVENTS_MINUTES = (CalendarEvent("주간 회의", "09:15", "10:45"), CalendarEvent("치과", "18:45", "19:30"))                 # 15분·45분처럼 분이 있는 일정

COACH_STEPS_RESUMED = tuple(Step(s.order + 3, s.name, s.planned_sec, s.response_type, s.script) for s in COACH_STEPS)   # 재연결: 앞 3단계를 끝내고 남은 단계 (번호가 4부터)

SCENARIOS = {s.name: s for s in [
    Scenario("night_events", "night", _night(events_today=EVENTS, events_tomorrow=(CalendarEvent("팀 미팅", "09:30", "10:30"),)),
             ("발표는 잘 끝났는데 많이 피곤하네요", "회의가 길어서 좀 지치긴 했어요", "저녁에는 그냥 쉬었어요", "내일은 발표 자료를 마무리하는 게 목표예요",
              "아침 열 시 반에 치과 예약이 있어요", "이제 잘게요 잘 자요"),
             expect={"event_words": ["회의", "리허설", "발표", "치과"], "farewell": True, "diary": True, "asks_tomorrow": True,
                     "open_day_q": True, "tomorrow_first_words": ["팀 미팅", "미팅", "9시 30분", "아홉 시 반"],
                     "extract": {"override_wake_time": None, "override_skip_coaching": False,
                                 "tomorrow_goal": lambda v: bool(v) and "발표" in v,
                                 "tomorrow_notes": lambda v: any("치과" in x for x in (v or []))}}),
    Scenario("night_tired_proposal", "night", _night(events_today=EVENTS, events_tomorrow=(CalendarEvent("팀 미팅", "09:30", "10:30"),)),
             ("네 발표는 잘 끝났어요", "너무 피곤해요 오늘 진이 다 빠졌어요", "회의랑 리허설이 계속 이어져서 정신이 없었어요", "내일은 발표 자료를 마무리하는 게 목표예요",
              "특별한 건 없어요", "알람은 그대로 둘게요", "응 괜찮아요", "알았어요"),
             expect={"ends_cleanly": True, "tomorrow_after_talk": True, "asks_notes": True, "proposes_override": True,
                     "tomorrow_first_words": ["팀 미팅", "미팅", "9시 30분", "아홉 시 반"]}),
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
             ("별일 없었어요", "점심에 김치찌개 먹은 거 정도요", "맛있었어요", "없어요", "없어요", "응 그래요", "알았다고"),
             expect={"ends_cleanly": True, "tomorrow_after_talk": True, "asks_notes": True}),
    Scenario("night_quiet_day_declines", "night", _night(),      # 말하기 싫어하면 더 캐묻지 않고 지어내지도 않는다
             ("별일 없었어요", "정말 없어요 그냥 피곤해요", "네 맞아요", "이제 잘게요"),
             expect={"farewell": True, "tomorrow_after_talk": True, "small_stop_from": 2,
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
             ("네 잘 잤어요", "네 준비됐어요"),
             expect={"weather_words": ["비", "우산"], "event_words": ["회의", "리허설"], "now_words": ["7시", "일곱"],
                     "ready_words": ["포춘"], "expect_start": True}),
    Scenario("briefing_flow_one_liner", "briefing",
             BriefingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="CONTENT", content_name="스트레칭", one_liner_follows=True,
                           now_hhmm="07:02", events_today=EVENTS, weather=WEATHER, night=NIGHT_REC),
             ("네 잘 잤어요", "네 준비됐어요"),
             expect={"weather_words": ["비", "우산"], "event_words": ["회의", "리허설"], "now_words": ["7시", "일곱"],
                     "ready_all": ["한마디", "포춘", "스트레칭"], "expect_start": True}),
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
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "step_names": [s.name for s in QUIZ], "answers": ["서울", "열두 달", "세종대왕"], "farewell": True, "bridge_words": ["퀴즈"]}),
    # 포춘 읽기: 서버가 만든 문장을 AI가 그대로 읽는지 본다 (사용자는 말하지 않고 시작 신호만 보낸다)
    Scenario("fortune_cookie", "fortune",
             FortuneInput(persona=Persona("예준", "FORMAL", "KIND"), style="COOKIE",
                          fortune_text="용기는 떨림이 사라진 뒤가 아니라, 첫마디를 꺼낼 때 찾아옵니다."),
             (), expect={"fortune_text": "용기는 떨림이 사라진 뒤가 아니라, 첫마디를 꺼낼 때 찾아옵니다."}),
    Scenario("fortune_one_liner", "fortune",
             FortuneInput(persona=Persona("예준", "FORMAL", "KIND"), style="COOKIE", based_on="ONE_LINER",
                          fortune_text="용기는 떨림이 사라진 뒤가 아니라, 첫마디를 꺼낼 때 찾아옵니다."),
             (), expect={"fortune_text": "용기는 떨림이 사라진 뒤가 아니라, 첫마디를 꺼낼 때 찾아옵니다.", "intro_words": ["한마디"]}),
    Scenario("fortune_horoscope", "fortune",
             FortuneInput(persona=Persona("예준", "FORMAL", "KIND"), style="HOROSCOPE",
                          fortune_text="오늘의 관계운 키워드는 '여백'. 답을 서두르기보다 내 마음부터 살펴보세요."),
             (), expect={"fortune_text": "오늘의 관계운 키워드는 '여백'. 답을 서두르기보다 내 마음부터 살펴보세요."}),
    Scenario("fortune_casual", "fortune",
             FortuneInput(persona=Persona("예준", "CASUAL", "FRIENDLY"), style="HOROSCOPE",
                          fortune_text="오늘의 행운 자세는 기지개야. 어깨를 펴고 하루를 열어 봐."),
             (), tone="CASUAL", expect={"fortune_text": "오늘의 행운 자세는 기지개야. 어깨를 펴고 하루를 열어 봐."}),
    Scenario("fortune_replay", "fortune",
             FortuneInput(persona=Persona("예준", "FORMAL", "KIND"), style="COOKIE",
                          fortune_text="어제 비워둔 칸은 오늘의 가능성이 들어갈 자리입니다."),
             ("한 번만 다시 읽어 주세요", "고마워요 이만 끝낼게요"),
             expect={"fortune_text": "어제 비워둔 칸은 오늘의 가능성이 들어갈 자리입니다.", "replay": True, "farewell": True}),
    # ----- 콘텐츠 3종 -----
    Scenario("content_stretch", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH", steps=tuple(STRETCH)),
             ("했어요", "했어요", "했어요", "했어요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {i: "done" for i in range(1, 5)}, "step_names": [x.name for x in STRETCH], "safety_first": True, "farewell": True, "bridge_words": ["스트레칭"]}),
    Scenario("content_stretch_pain", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH", steps=tuple(STRETCH)),
             ("했어요", "아파서 이건 건너뛸게요", "했어요", "했어요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "skipped", 3: "done", 4: "done"}, "step_names": [x.name for x in STRETCH],
                     "safety_first": True, "pain_note": 2, "farewell": True, "bridge_words": ["스트레칭"]}),
    Scenario("content_quiz_person", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="QUIZ", content_option="PERSON", steps=tuple(PERSON_QUIZ)),
             (f"{PERSON_ANSWERS[0]}이요", "음 홍길동이요", f"{PERSON_ANSWERS[2]}이요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done", 2: "done", 3: "done"}, "answer_correct": {1: True, 2: False, 3: True}, "reactions": {1: "맞", 2: "아쉬"},
                     "step_names": [x.name for x in PERSON_QUIZ], "answers": PERSON_ANSWERS, "farewell": True, "bridge_words": ["인물 퀴즈"]}),
    Scenario("content_english", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="LANGUAGE", content_option="ENGLISH", steps=tuple(ENGLISH)),
             tuple([x.script.split(" / ")[0] for x in ENGLISH[:3]] + [ENGLISH[3].script.split("예시:")[1].strip(), "고마워요 이만 끝낼게요"]),
             expect={"verdicts": {1: "done", 2: "done", 3: "done", 4: "done"}, "step_names": [x.name for x in ENGLISH], "allow_english": True, "farewell": True, "bridge_words": ["영어"],
                     "english_repeat": {i + 1: ENGLISH[i].script.split(" / ")[0] for i in range(3)}}),
    Scenario("content_wordchain", "coaching",
             CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="QUIZ", content_option="WORDCHAIN", steps=tuple(WORDCHAIN)),
             (WORDCHAIN_REPLY[WC_START], "음 모르겠어요 패스할게요", "고마워요 이만 끝낼게요"),
             expect={"verdicts": {1: "done"}, "verdict_any_of": {1: ("skipped",)}, "pass_ends": True, "report_turn": {1: 2}, "extra_turns": 1,
                     "step_names": ["끝말잇기"], "start_word": WC_START, "farewell": True, "bridge_words": ["끝말잇기"]}),
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


# ----- 말투 유지(11/18 시연 영상 01 3:35: 사용자가 반말로 말하자 AI가 따라 하고 끝까지 유지) — 로컬 전용 -----
# 설정 어조는 사용자가 어떤 말투로 말해도 바뀌지 않아야 한다. 사용자 발화를 일부러 설정과 반대 말투로 길게 이어 간다.
_STRETCH_TONE_USER_CASUAL = ("응 했어", "못 들었어, 다시 설명해 줘", "했어", "어렵네, 좀 천천히 말해 줘", "했어", "응 했어", "고마워 이만 끊을게")
_STRETCH_TONE_USER_POLITE = ("네 했어요", "못 들었어요, 다시 설명해 주세요", "했어요", "조금 어려워요, 천천히 말해 주세요", "했어요", "네 했어요", "고마워요 이만 끊을게요")
_TONE_EXPECT = {"verdicts": {i: "done" for i in range(1, 5)}, "no_verdict_turns": (2, 4),      # 2·4번째 발화는 "다시 설명해 줘"라 판정하지 않는다
                "step_names": [x.name for x in STRETCH], "farewell": True}
SCENARIOS.update({s.name: s for s in [
    Scenario("tone_formal_user_casual", "coaching",
             CoachingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH",
                           steps=tuple(STRETCH), night=NIGHT_REC),
             _STRETCH_TONE_USER_CASUAL, tone="FORMAL", expect=_TONE_EXPECT),
    Scenario("tone_casual_user_polite", "coaching",
             CoachingInput(persona=Persona("예준", "CASUAL", "FRIENDLY"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH",
                           steps=tuple(STRETCH), night=NIGHT_REC),
             _STRETCH_TONE_USER_POLITE, tone="CASUAL", expect=_TONE_EXPECT),
]})


# ----- 낭독 점검(11/18 영상 01 2:04: "튼구름"을 "뜬구름", 일정 "연가(…2.5~7.5)"를 "연가 안에서 …2.5에서 7.5"로 읽음) — 로컬 전용 -----
SCENARIOS.update({s.name: s for s in [
    Scenario("briefing_odd_title", "briefing",
             BriefingInput(persona=Persona("예준"), morning_call_type="ROUTINE", program_name="현실 모닝루틴", now_hhmm="07:02",
                           events_today=(CalendarEvent("연가(권현우 2.5~7.5)", all_day=True),),        # 영상처럼 이 일정 하나뿐인 날(다른 일정이 있으면 AI가 중요한 쪽만 읽는다)
                           weather=Weather("튼구름", temp_c=4, high_c=7, low_c=1, precip_prob=10, location_name="하남시")),
             ("음... 아직 많이 졸려요. 어제는 한 시쯤 잤어요", "네 준비됐어요"),       # 첫 대답에 잠든 시각을 넣어 바로 브리핑까지 가게 한다
             expect={"now_words": ["7시", "일곱"], "event_words": ["연가"], "expect_start": True}),
]})


# ----- 실제 앱 순서(코칭 → 포춘): 서버가 순서표를 넘기면 마지막 단계 뒤에 앱이 알아서 다음 순서로 넘어간다(작별 없음, end_session 안 부름) — 로컬 전용 -----
# 위 tone_* 시나리오는 순서표를 안 넘겨 옛 순서(코칭이 마지막)로 돌았다. 서버는 코칭 세션에 순서표를 실제로 넘기므로(AI B15) 실제 앱과 같은 흐름은 이쪽이다.
_PLAN_EXPECT = {**_TONE_EXPECT, "farewell": False, "no_farewell": True, "handoff_words": ["포춘"]}
SCENARIOS.update({s.name: s for s in [
    Scenario("plan_formal_user_casual", "coaching",
             CoachingInput(persona=Persona("예준", "FORMAL", "KIND"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH",
                           steps=tuple(STRETCH), night=NIGHT_REC, plan=("BRIEFING", "COACHING", "FORTUNE")),
             _STRETCH_TONE_USER_CASUAL[:-1], tone="FORMAL", expect=_PLAN_EXPECT),
    Scenario("plan_casual_user_polite", "coaching",
             CoachingInput(persona=Persona("예준", "CASUAL", "FRIENDLY"), morning_call_type="CONTENT", content_type="EXERCISE", content_option="STRETCH",
                           steps=tuple(STRETCH), night=NIGHT_REC, plan=("BRIEFING", "COACHING", "FORTUNE")),
             _STRETCH_TONE_USER_POLITE[:-1], tone="CASUAL", expect=_PLAN_EXPECT),
]})


# ----- 말로 알람 미루기(11/20 회의 1): 전화를 받은 직후 브리핑 세션에서 "~분 뒤에 다시 울려 줘"/"~시에 다시 맞춰 줘" — 로컬 전용 -----
def _snooze_briefing(now="08:20", used=0, tone="FORMAL"):
    return BriefingInput(persona=Persona("예준", tone), morning_call_type="ROUTINE", program_name="현실 모닝루틴", now_hhmm=now, events_today=EVENTS, snoozes_today=used)


SCENARIOS.update({s.name: s for s in [
    Scenario("snooze_minutes", "briefing", _snooze_briefing(), ("10분만 더 잘래요. 10분 뒤에 다시 울려 주세요", "네 준비됐어요"), expect={"snooze": {"minutes_later": 10}}),
    Scenario("snooze_at_time", "briefing", _snooze_briefing(), ("아직 너무 졸려요. 9시에 다시 맞춰 주세요", "네 준비됐어요"), expect={"snooze": {"at_time": "09:00"}}),
    Scenario("snooze_vague", "briefing", _snooze_briefing(), ("조금만 더 잘래요", "10분이요"), expect={"snooze": {"minutes_later": 10}, "asks_first": True}),
    Scenario("snooze_over_limit", "briefing", _snooze_briefing(now="07:00"), ("한 시간 반 뒤에 다시 울려 주세요", "네 60분으로 해 주세요"),
             expect={"snooze": {"minutes_later": 60}, "asks_over_limit": True}),
    Scenario("snooze_daily_limit", "briefing", _snooze_briefing(used=3), ("10분만 더 잘게요. 다시 울려 주세요", "네 준비됐어요"), expect={"snooze": {}}),
    Scenario("snooze_casual", "briefing", _snooze_briefing(tone="CASUAL"), ("10분 뒤에 다시 울려 줘", "응 준비됐어"), tone="CASUAL", expect={"snooze": {"minutes_later": 10}}),
]})


# ----- 11/24: 브리핑이 끝난 뒤 콘텐츠·루틴 통화에서도 말로 미루기(시연에서 영어 통화는 안 됐다) -----
def _english_call(used=0):
    return CoachingInput(persona=Persona("예준"), morning_call_type="CONTENT", content_type="LANGUAGE", content_option="ENGLISH", steps=tuple(ENGLISH), snoozes_today=used)


SCENARIOS.update({s.name: s for s in [
    Scenario("snooze_in_english", "coaching", _english_call(), ("아직 좀 졸려요. 10분 뒤에 다시 울려 주세요", "네"), expect={"snooze": {"minutes_later": 10}}),
    Scenario("snooze_in_english_midway", "coaching", _english_call(),
             (ENGLISH[0].script.split(" / ")[0], "잠깐만요, 1분 뒤에 다시 깨워 주세요", "네"), expect={"snooze": {"minutes_later": 1}}),
    Scenario("snooze_in_routine", "coaching", CoachingInput(persona=Persona("예준"), steps=COACH_STEPS, night=NIGHT_REC),
             ("물 한 잔 마셨어요", "아 너무 졸려요. 5분 뒤에 다시 울려 주세요", "네"), expect={"snooze": {"minutes_later": 5}}),
    Scenario("snooze_in_english_daily_limit", "coaching", _english_call(used=3), ("10분만 더 잘게요. 다시 울려 주세요", "네"), expect={"snooze": {}}),
]})


# ----- 대화로 설정 바꾸기(11/20 회의 4): 굿나잇콜·모닝콜에서 AI 음악·성격·강도·기상 볼륨 — 로컬 전용 -----
from ai.live.inputs import SettingsView                                    # noqa: E402

_SET_VIEW = SettingsView(music_genre="POP", wake_start_level=3, wake_state="RECOMMENDED")
_SET_VIEW_EXPLORING = SettingsView(music_genre="POP", wake_start_level=None, wake_state="EXPLORING")


def _set_briefing(view=_SET_VIEW, tone="FORMAL"):
    return BriefingInput(persona=Persona("예준", tone, "KIND", "FEMALE", "KIND", 2), morning_call_type="ROUTINE", program_name="현실 모닝루틴", now_hhmm="07:02",
                         events_today=EVENTS, settings=view)


def _set_night(tone="FORMAL"):
    return NightInput(persona=Persona("예준", tone, "KIND", "FEMALE", "KIND", 2), now_hhmm="22:10", tomorrow_wake_time="07:00", events_today=EVENTS, settings=_SET_VIEW)


SCENARIOS.update({s.name: s for s in [
    Scenario("setting_personality_night", "night", _set_night(),
             ("오늘은 별일 없었어요. 그런데 AI 성격을 엄격한 걸로 바꾸고 싶어요", "네 바꿔 주세요", "고마워요 잘게요"),
             expect={"settings": [{"field": "personality", "value": "STRICT"}], "farewell": True}),
    Scenario("setting_music_briefing", "briefing", _set_briefing(), ("개운해요. 기상 음악을 재즈로 바꿔 주세요", "네 바꿔 주세요"),
             expect={"settings": [{"field": "music_genre", "value": "JAZZ"}]}),
    Scenario("setting_volume_recommended", "briefing", _set_briefing(), ("개운해요. 알람 소리를 앞으로 2단계보다 높게는 시작하지 말아 주세요", "네 그렇게 해 주세요"),
             expect={"settings": [{"field": "wake_volume", "value": "2", "scope": "ALWAYS"}]}),
    Scenario("setting_volume_exploring", "briefing", _set_briefing(_SET_VIEW_EXPLORING), ("개운해요. 내일 알람은 4단계로 시작해 주세요", "네 그렇게 해 주세요"),
             expect={"settings": [{"field": "wake_volume", "value": "4", "scope": "TODAY"}]}),
    Scenario("setting_unsupported", "briefing", _set_briefing(), ("개운해요. 그런데 AI 말투를 반말로 바꿔 주세요", "네 알겠어요"), expect={"settings": []}),
    Scenario("setting_casual", "briefing", _set_briefing(tone="CASUAL"), ("개운해. AI 강도를 강하게 바꿔 줘", "응 바꿔 줘"), tone="CASUAL",
             expect={"settings": [{"field": "intensity", "value": "3"}]}),
]})


# ---- 밤 대화: 내일 알람 볼륨 상승 알림(11/21 결정 4) ----
_RAISE_VIEW = SettingsView(music_genre="POP", wake_start_level=3, wake_state="RECOMMENDED", wake_raise_from=1)


def _raise_night(tone="FORMAL"):
    return NightInput(persona=Persona("예준", tone, "KIND", "FEMALE", "KIND", 2), now_hhmm="22:10", tomorrow_wake_time="07:00", events_today=EVENTS, settings=_RAISE_VIEW)


_RAISE_TALK = ("발표는 잘 끝났어요", "그냥 저녁 먹고 좀 쉬었어요", "내일은 보고서 마무리가 목표예요", "딱히 없어요")
SCENARIOS.update({s.name: s for s in [
    Scenario("night_raise_ok", "night", _raise_night(), _RAISE_TALK + ("네 괜찮아요", "고마워요 잘게요"),
             expect={"wake_raise": {"from": 1, "to": 3, "objects": False}, "farewell": True}),
    Scenario("night_raise_object", "night", _raise_night(), _RAISE_TALK + ("소리가 너무 클 것 같아요 그대로 두세요", "고마워요 잘게요"),
             expect={"wake_raise": {"from": 1, "to": 3, "objects": True, "scope": "TODAY"}, "farewell": True}),
    Scenario("night_raise_object_always", "night", _raise_night(), _RAISE_TALK + ("앞으로도 그렇게 크게는 시작하지 말아 주세요", "고마워요 잘게요"),
             expect={"wake_raise": {"from": 1, "to": 3, "objects": True, "scope": "ALWAYS"}, "farewell": True}),
    Scenario("night_raise_casual", "night", _raise_night("CASUAL"), _RAISE_TALK + ("응 괜찮아", "고마워 잘게"), tone="CASUAL",
             expect={"wake_raise": {"from": 1, "to": 3, "objects": False}, "farewell": True}),
]})
