"""11/21: 모닝콜 말로 미루기(11/20 회의 1). 함수 선언·프롬프트 규칙·점검 규칙을 본다. 모델 호출은 없다."""
from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import check
from ai.devclient.scenarios import SCENARIOS
from ai.live.inputs import BriefingInput, CoachingInput, Persona
from ai.live.prompts.briefing import briefing_prompt
from ai.live.prompts.coaching import coaching_prompt
from ai.live.tools import RESCHEDULE_ALARM, SNOOZE_MAX_MINUTES, SNOOZE_MAX_PER_ALARM, tools_for


def names(kind, inp=None):
    return [d["name"] for d in tools_for(kind, inp)[0]["functionDeclarations"]]


def test_reschedule_alarm_is_in_briefing_and_coaching_sessions():
    """11/24 시연: 영어 콘텐츠 통화에서 미루기가 안 됐다. 브리핑뿐 아니라 루틴·콘텐츠 통화에도 둔다. 밤·포춘에는 없다."""
    assert "reschedule_alarm" in names("briefing", BriefingInput())
    for inp in (CoachingInput(), CoachingInput(morning_call_type="CONTENT")):
        assert "reschedule_alarm" in names("coaching", inp)
    for kind in ("night", "fortune"):
        assert "reschedule_alarm" not in names(kind)


def test_coaching_function_is_removed_after_the_daily_limit():
    for call_type in ("ROUTINE", "CONTENT"):
        assert "reschedule_alarm" in names("coaching", CoachingInput(morning_call_type=call_type, snoozes_today=SNOOZE_MAX_PER_ALARM - 1))
        left = names("coaching", CoachingInput(morning_call_type=call_type, snoozes_today=SNOOZE_MAX_PER_ALARM))
        assert "reschedule_alarm" not in left and left[-1] == "end_session"


def test_function_is_removed_after_the_daily_limit():
    assert "reschedule_alarm" in names("briefing", BriefingInput(snoozes_today=SNOOZE_MAX_PER_ALARM - 1))
    assert "reschedule_alarm" not in names("briefing", BriefingInput(snoozes_today=SNOOZE_MAX_PER_ALARM))


def test_declaration_has_one_of_minutes_or_time():
    props = RESCHEDULE_ALARM["parameters"]["properties"]
    assert set(props) == {"minutes_later", "at_time"} and props["minutes_later"]["type"] == "INTEGER" and props["at_time"]["type"] == "STRING"
    assert f"1~{SNOOZE_MAX_MINUTES}" in props["minutes_later"]["description"]
    assert "먼저 안내한 뒤" in RESCHEDULE_ALARM["description"]      # 말을 먼저 하고 응답 끝에서 호출(호출하면 앱이 응답 없이 닫으므로)
    assert "종료" not in RESCHEDULE_ALARM["description"]             # 앱이 통화를 저절로 끝내므로 직접 종료하라고 하지 않는다(11/27)


def test_prompt_rules_by_tone_and_limit():
    formal = briefing_prompt(BriefingInput(persona=Persona("예준", "FORMAL")))
    casual = briefing_prompt(BriefingInput(persona=Persona("예준", "CASUAL")))
    assert "[말로 알람 미루기(오늘만)]" in formal and "reschedule_alarm을 호출한다" in formal
    assert "\"알람을 ○분 뒤에 다시 맞췄어요.\"" in formal and "\"알람을 ○분 뒤에 다시 맞췄어.\"" in casual
    assert "직접 종료" not in formal and "직접 종료" not in casual
    assert "알람마다 최대 3번" in formal and "하루 최대" not in formal
    assert "졸리다는 말만으로는 미루는 요청이 아니다" in formal and "먼저 말하고, 그 응답의 맨 끝에서 reschedule_alarm을 호출한다" in formal
    over = briefing_prompt(BriefingInput(snoozes_today=SNOOZE_MAX_PER_ALARM))
    assert "알람 미루기 불가" in over and "reschedule_alarm을 호출한다" not in over and "절대 말하지 않는다" in over
    assert "이 알람은 이미 3번" in over and "오늘" not in over.split("[알람 미루기 불가")[1].split("\n\n")[0]


def test_coaching_prompt_has_snooze_rules_for_steps():
    for call_type in ("ROUTINE", "CONTENT"):
        p = coaching_prompt(CoachingInput(persona=Persona("예준"), morning_call_type=call_type))
        assert "[말로 알람 미루기(오늘만)]" in p and "단계를 진행하는 중이어도" in p
        assert "report_step_result" in p.split("단계를 진행하는 중이어도", 1)[1] and "미루는 요청이 아니다" in p
    over = coaching_prompt(CoachingInput(persona=Persona("예준"), snoozes_today=SNOOZE_MAX_PER_ALARM))
    assert "알람 미루기 불가" in over and "reschedule_alarm을 호출한다" not in over
    briefing = briefing_prompt(BriefingInput())
    assert "단계를 진행하는 중이어도" not in briefing           # 브리핑에는 단계 설명이 붙지 않는다


def _turn(heard="", said="", calls=()):
    return TurnLog(label=heard or "시작", heard=heard, said=said, calls=list(calls))


def _run(name, turns, ended_by):
    return check(SessionLog(kind="briefing", turns=turns, ended_by=ended_by), SCENARIOS[name])


def by(results, rule):
    return next(r for r in results if r.rule == rule)


CALL = {"name": "reschedule_alarm", "args": {"minutes_later": 10}}
START = _turn(said="좋은 아침이에요. 지금 좀 개운해요, 아니면 아직 졸려요?")


def test_good_snooze_passes_every_check():
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 10분 뒤에 다시 맞췄어요.", [CALL])], "reschedule_alarm")
    assert not [x for x in r if not x.passed and x.severity == "HARD"], [x for x in r if not x.passed]


def test_silent_announcement_or_wrong_value_fails():
    r = _run("snooze_minutes", [START, _turn("10분만 더", "", [CALL])], "reschedule_alarm")
    assert not by(r, "미루기: 호출한 응답에서 다시 맞췄다고 알림(앱이 통화를 저절로 끝내므로 직접 종료하라고 하지 않음)").passed
    wrong = {"name": "reschedule_alarm", "args": {"minutes_later": 30}}
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 30분 뒤에 다시 맞췄어요.", [wrong])], "reschedule_alarm")
    assert not by(r, "미루기: 시간 값이 기대와 같음").passed


def test_both_or_neither_time_value_is_a_bad_call():
    both = {"name": "reschedule_alarm", "args": {"minutes_later": 10, "at_time": "08:30"}}
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 다시 맞췄어요.", [both])], "reschedule_alarm")
    assert not by(r, "함수 호출 형식").passed
    too_long = {"name": "reschedule_alarm", "args": {"minutes_later": 90}}
    r = _run("snooze_minutes", [START, _turn("90분", "알람을 다시 맞췄어요.", [too_long])], "reschedule_alarm")
    assert not by(r, "함수 호출 형식").passed


def test_unrequested_snooze_and_false_claim_are_caught():
    r = check(SessionLog(kind="briefing", turns=[START, _turn("아직 졸려요", "알람을 다시 맞췄어요.", [CALL])], ended_by="reschedule_alarm"), SCENARIOS["briefing_sleepy"])
    assert not by(r, "알람 미루기: 요청이 없는데 미루지 않음").passed
    r = _run("snooze_daily_limit", [START, _turn("10분만 더", "알람을 10분 뒤에 다시 맞췄어요.")], "turnComplete")
    assert not by(r, "미루기: 함수 없이 다시 맞췄다고 거짓으로 말하지 않음").passed

