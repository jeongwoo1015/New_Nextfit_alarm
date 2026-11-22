"""11/21: 대화로 설정 바꾸기(11/20 회의 4). 함수 선언·프롬프트 규칙·점검 규칙을 본다. 모델 호출은 없다."""
from ai.devclient.client import SessionLog, TurnLog
from ai.devclient.rules import check
from ai.devclient.scenarios import SCENARIOS
from ai.live.inputs import BriefingInput, CoachingInput, NightInput, Persona, SettingsView
from ai.live.prompts.briefing import briefing_prompt
from ai.live.prompts.coaching import coaching_prompt
from ai.live.prompts.night import night_prompt
from ai.live.tools import SETTING_SCOPES, SETTING_VALUES, UPDATE_SETTING, tools_for


def names(kind, inp=None):
    return [d["name"] for d in tools_for(kind, inp)[0]["functionDeclarations"]]


def test_update_setting_is_in_night_and_briefing_only():
    assert "update_setting" in names("night") and "update_setting" in names("briefing", BriefingInput())
    for kind, inp in (("fortune", None), ("coaching", CoachingInput())):
        assert "update_setting" not in names(kind, inp)


def test_only_the_four_settings_can_be_changed():
    props = UPDATE_SETTING["parameters"]["properties"]
    assert props["field"]["enum"] == ["music_genre", "personality", "intensity", "wake_volume"]       # 어조·호칭 등은 없다
    assert props["scope"]["enum"] == list(SETTING_SCOPES) == ["ALWAYS", "TODAY"]
    assert SETTING_VALUES["personality"] == ("KIND", "FRIENDLY", "STRICT", "CHEERFUL") and SETTING_VALUES["wake_volume"] == ("1", "2", "3", "4", "5")
    assert UPDATE_SETTING["parameters"]["required"] == ["field", "value"]


def test_prompt_rules_confirm_then_call_then_announce():
    for p in (night_prompt(NightInput()), briefing_prompt(BriefingInput())):
        assert "[설정 변경(대화로)]" in p and "한 번 말해 확인받는다" in p
        assert "먼저 update_setting을 호출하고, 호출한 뒤에 바꿨다고 한 문장으로 알린다" in p
        assert "호출하지 않고 바꿨다고 말하지 않는다" in p and "(어조·호칭·알람 시각 등)은 바꾸지 않고" in p
    assert "설정 변경(대화로)" not in coaching_prompt(CoachingInput(persona=Persona("예준")))


def test_current_settings_block_and_user_first_wording():
    view = SettingsView(music_genre="JAZZ", wake_start_level=3, wake_state="RECOMMENDED", wake_max_level=4)
    p = briefing_prompt(BriefingInput(persona=Persona("예준", "FORMAL", "KIND", "FEMALE", "STRICT", 3), settings=view))
    assert "[현재 설정]" in p and "AI 음악(장르): 재즈" in p and "AI 성격: 엄격한" in p and "AI 강도: 강하게" in p
    assert "시작 3단계 (개인화가 정한 값)" in p and "사용자가 정한 시작 단계 상한: 4단계" in p
    assert "설득하지 않는다. 결정은 사용자에게 있고" in p
    assert "[현재 설정]\n- " not in briefing_prompt(BriefingInput())        # 값이 없으면 현재 설정 블록(목록)은 없다(규칙 문장에는 이름만 나온다)
    explore = briefing_prompt(BriefingInput(settings=SettingsView(wake_state="EXPLORING")))
    assert "기상 알람 볼륨: 아직 맞는 단계를 찾는 중" in explore


def test_today_vs_always_scope_is_explained():
    p = briefing_prompt(BriefingInput())
    assert "scope=TODAY" in p and "scope=ALWAYS" in p and "분명하지 않을 때만 한 번 묻는다" in p


def _turn(heard="", said="", calls=()):
    return TurnLog(label=heard or "시작", heard=heard, said=said, calls=list(calls))


def _check(name, turns):
    return check(SessionLog(kind=SCENARIOS[name].kind, turns=turns, ended_by="turnComplete"), SCENARIOS[name])


def by(results, rule):
    return next(r for r in results if r.rule == rule)


START = _turn(said="좋은 아침이에요. 지금 좀 개운해요, 아니면 아직 졸려요?")
CALL = {"name": "update_setting", "args": {"field": "music_genre", "value": "JAZZ"}}


def test_confirmed_call_passes_and_unconfirmed_or_false_claim_fails():
    good = [START, _turn("재즈로", "재즈로 바꿔 드릴까요?"), _turn("네", "재즈로 바꿨어요. 다음 모닝콜부터 적용돼요.", [CALL])]
    r = _check("setting_music_briefing", good)
    assert by(r, "설정 변경: 호출이 기대와 같음").passed and by(r, "설정 변경: 호출 전에 바꿀 값을 확인받음").passed and by(r, "설정 변경: 바꿨다고 알림").passed
    no_confirm = [START, _turn("재즈로", "재즈로 바꿨어요.", [CALL]), _turn("네", "네.")]
    assert not by(_check("setting_music_briefing", no_confirm), "설정 변경: 호출 전에 바꿀 값을 확인받음").passed
    false_claim = [START, _turn("재즈로", "재즈로 바꿔 드릴까요?"), _turn("네", "재즈로 바꿨어요.")]
    r = _check("setting_music_briefing", false_claim)
    assert not by(r, "설정 변경: 함수를 부르지 않고 바꿨다고 말하지 않음").passed and not by(r, "설정 변경: 호출이 기대와 같음").passed


def test_invalid_values_are_bad_calls_and_unsupported_settings_expect_no_call():
    bad = {"name": "update_setting", "args": {"field": "tone", "value": "CASUAL"}}
    r = _check("setting_unsupported", [START, _turn("반말로", "어조는 설정 화면에서 바꿀 수 있어요.", [bad])])
    assert not by(r, "함수 호출 형식").passed and not by(r, "설정 변경: 호출이 기대와 같음").passed
    bad_value = {"name": "update_setting", "args": {"field": "wake_volume", "value": "9", "scope": "TODAY"}}
    assert not by(_check("setting_volume_exploring", [START, _turn("4", "네", [bad_value])]), "함수 호출 형식").passed
    ok = _check("setting_unsupported", [START, _turn("반말로", "어조는 설정 화면에서 바꿀 수 있어요.")])
    assert by(ok, "설정 변경: 호출이 기대와 같음").passed
