"""11/21: 밤 대화에서 내일 알람 볼륨 상승을 알리고 반대하면 이전 단계를 유지한다(결정 4). 프롬프트·점검 규칙만 본다. 모델 호출은 없다."""
from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import check
from ai.devclient.scenarios import SCENARIOS
from ai.live.inputs import NightInput, Persona, SettingsView
from ai.live.prompts.night import night_prompt


def _night(**kw):
    return NightInput(persona=Persona("예준", kw.pop("tone", "FORMAL")), settings=SettingsView(**kw))


def test_block_only_when_the_start_level_really_goes_up():
    up = night_prompt(_night(wake_start_level=3, wake_raise_from=1))
    assert "[내일 알람 볼륨 상승 알림]" in up and "1단계 대신 3단계" in up and "반대하지 않을 때만 적용" in up
    assert "update_setting(field=wake_volume, value=1, scope=TODAY)" in up and "다시 확인받지 않고" in up
    for kw in (dict(wake_start_level=3), dict(wake_start_level=3, wake_raise_from=3), dict(wake_start_level=1, wake_raise_from=3), dict(wake_raise_from=1)):
        assert "볼륨 상승 알림" not in night_prompt(_night(**kw))
    assert "볼륨 상승 알림" not in night_prompt(NightInput())


def test_tone_follows_the_setting():
    assert "시작해 볼게. 너무 크면 말해 줘" in night_prompt(_night(wake_start_level=3, wake_raise_from=1, tone="CASUAL"))
    assert "시작해 볼게요. 너무 크게 느껴지시면" in night_prompt(_night(wake_start_level=3, wake_raise_from=1))


def _turn(heard="", said="", calls=()):
    return TurnLog(label=heard or "시작", heard=heard, said=said, calls=list(calls))


def _check(name, turns, ended_by="end_session"):
    return check(SessionLog(kind="night", turns=turns, ended_by=ended_by), SCENARIOS[name])


def by(results, rule):
    return next(r for r in results if r.rule == rule)


ANNOUNCE = _turn("딱히 없어요", "내일 아침 알람은 소리를 1단계 대신 3단계에서 시작해 볼게요. 너무 크게 느껴지시면 말씀해 주세요, 그대로 둘게요.")


def test_no_objection_needs_no_call_and_a_call_is_a_failure():
    assert by(_check("night_raise_ok", [ANNOUNCE, _turn("네 괜찮아요", "좋아요. 편히 주무세요.")]), "볼륨 상승: 반대가 없으면 호출하지 않음").passed
    call = {"name": "update_setting", "args": {"field": "wake_volume", "value": "1", "scope": "TODAY"}}
    assert not by(_check("night_raise_ok", [ANNOUNCE, _turn("네 괜찮아요", "그대로 둘게요.", [call])]), "볼륨 상승: 반대가 없으면 호출하지 않음").passed
    assert not by(_check("night_raise_ok", [_turn("네", "편히 주무세요.")]), "볼륨 상승: 내일 시작 단계 변화를 알림").passed


def test_objection_must_call_previous_level_with_the_right_scope_after_the_notice():
    today = {"name": "update_setting", "args": {"field": "wake_volume", "value": "1", "scope": "TODAY"}}
    r = _check("night_raise_object", [ANNOUNCE, _turn("그대로 두세요", "알겠어요, 1단계 그대로 둘게요.", [today])])
    assert by(r, "볼륨 상승: 반대하면 이전 단계로 호출").passed and by(r, "볼륨 상승: 알린 뒤에 호출함").passed and by(r, "볼륨 상승: 이전 단계로 둔다고 알림").passed
    r = _check("night_raise_object_always", [ANNOUNCE, _turn("앞으로도", "알겠어요, 1단계 그대로 둘게요.", [today])])
    assert not by(r, "볼륨 상승: 반대하면 이전 단계로 호출").passed           # 지속 요청인데 TODAY로 불렀다
    r = _check("night_raise_object", [ANNOUNCE, _turn("그대로 두세요", "알겠어요, 그대로 둘게요.")])
    assert not by(r, "볼륨 상승: 반대하면 이전 단계로 호출").passed           # 호출 없이 말만 함
