"""11/21: 모닝콜 말로 미루기(11/20 회의 1). 함수 선언·프롬프트 규칙·점검 규칙을 본다. 모델 호출은 없다."""
from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import check
from ai.devclient.scenarios import SCENARIOS
from ai.live.inputs import BriefingInput, CoachingInput, Persona
from ai.live.prompts.briefing import briefing_prompt
from ai.live.prompts.coaching import coaching_prompt
from ai.live.tools import RESCHEDULE_ALARM, SNOOZE_MAX_MINUTES, SNOOZE_MAX_PER_DAY, tools_for


def names(kind, inp=None):
    return [d["name"] for d in tools_for(kind, inp)[0]["functionDeclarations"]]


def test_reschedule_alarm_is_only_in_the_briefing_session():
    assert "reschedule_alarm" in names("briefing", BriefingInput())
    for kind, inp in (("night", None), ("fortune", None), ("coaching", CoachingInput())):
        assert "reschedule_alarm" not in names(kind, inp)


def test_function_is_removed_after_the_daily_limit():
    assert "reschedule_alarm" in names("briefing", BriefingInput(snoozes_today=SNOOZE_MAX_PER_DAY - 1))
    assert "reschedule_alarm" not in names("briefing", BriefingInput(snoozes_today=SNOOZE_MAX_PER_DAY))


def test_declaration_has_one_of_minutes_or_time():
    props = RESCHEDULE_ALARM["parameters"]["properties"]
    assert set(props) == {"minutes_later", "at_time"} and props["minutes_later"]["type"] == "INTEGER" and props["at_time"]["type"] == "STRING"
    assert f"1~{SNOOZE_MAX_MINUTES}" in props["minutes_later"]["description"]
    assert "먼저 말한 뒤" in RESCHEDULE_ALARM["description"]      # 말을 먼저 하고 응답 끝에서 호출(호출하면 앱이 응답 없이 닫으므로)


def test_prompt_rules_by_tone_and_limit():
    formal = briefing_prompt(BriefingInput(persona=Persona("예준", "FORMAL")))
    casual = briefing_prompt(BriefingInput(persona=Persona("예준", "CASUAL")))
    assert "[말로 알람 미루기(오늘만)]" in formal and "reschedule_alarm을 호출한다" in formal
    assert "다시 맞췄어요. 지금 울리는 알람은 직접 종료해 주세요" in formal and "다시 맞췄어. 지금 울리는 알람은 직접 종료해 줘" in casual
    assert "졸리다는 말만으로는 미루는 요청이 아니다" in formal and "먼저 말하고, 그 응답의 맨 끝에서 reschedule_alarm을 호출한다" in formal
    over = briefing_prompt(BriefingInput(snoozes_today=SNOOZE_MAX_PER_DAY))
    assert "알람 미루기 불가" in over and "reschedule_alarm을 호출한다" not in over and "절대 말하지 않는다" in over


def test_coaching_prompt_has_no_snooze_rules():
    assert "알람 미루기" not in coaching_prompt(CoachingInput(persona=Persona("예준")))


def _turn(heard="", said="", calls=()):
    return TurnLog(label=heard or "시작", heard=heard, said=said, calls=list(calls))


def _run(name, turns, ended_by):
    return check(SessionLog(kind="briefing", turns=turns, ended_by=ended_by), SCENARIOS[name])


def by(results, rule):
    return next(r for r in results if r.rule == rule)


CALL = {"name": "reschedule_alarm", "args": {"minutes_later": 10}}
START = _turn(said="좋은 아침이에요. 지금 좀 개운해요, 아니면 아직 졸려요?")


def test_good_snooze_passes_every_check():
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 10분 뒤에 다시 맞췄어요. 지금 울리는 알람은 직접 종료해 주세요.", [CALL])], "reschedule_alarm")
    assert not [x for x in r if not x.passed and x.severity == "HARD"], [x for x in r if not x.passed]


def test_silent_announcement_or_wrong_value_fails():
    r = _run("snooze_minutes", [START, _turn("10분만 더", "", [CALL])], "reschedule_alarm")
    assert not by(r, "미루기: 호출한 응답에서 다시 맞췄다고 알리고 직접 종료를 안내함").passed
    wrong = {"name": "reschedule_alarm", "args": {"minutes_later": 30}}
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 30분 뒤에 다시 맞췄어요. 지금 울리는 알람은 직접 종료해 주세요.", [wrong])], "reschedule_alarm")
    assert not by(r, "미루기: 시간 값이 기대와 같음").passed


def test_both_or_neither_time_value_is_a_bad_call():
    both = {"name": "reschedule_alarm", "args": {"minutes_later": 10, "at_time": "08:30"}}
    r = _run("snooze_minutes", [START, _turn("10분만 더", "알람을 다시 맞췄어요. 직접 종료해 주세요.", [both])], "reschedule_alarm")
    assert not by(r, "함수 호출 형식").passed
    too_long = {"name": "reschedule_alarm", "args": {"minutes_later": 90}}
    r = _run("snooze_minutes", [START, _turn("90분", "알람을 다시 맞췄어요. 직접 종료해 주세요.", [too_long])], "reschedule_alarm")
    assert not by(r, "함수 호출 형식").passed


def test_unrequested_snooze_and_false_claim_are_caught():
    r = check(SessionLog(kind="briefing", turns=[START, _turn("아직 졸려요", "알람을 다시 맞췄어요.", [CALL])], ended_by="reschedule_alarm"), SCENARIOS["briefing_sleepy"])
    assert not by(r, "알람 미루기: 요청이 없는데 미루지 않음").passed
    r = _run("snooze_daily_limit", [START, _turn("10분만 더", "알람을 10분 뒤에 다시 맞췄어요.")], "turnComplete")
    assert not by(r, "미루기: 함수 없이 다시 맞췄다고 거짓으로 말하지 않음").passed
