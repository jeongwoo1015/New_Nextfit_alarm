"""RealAIGateway를 실제 `ai` 패키지 함수로 확인한다 (feat/ai 병합 후).

네트워크는 쓰지 않는다: Gemini 텍스트 호출은 FakeLLM, Live 토큰 발급 HTTP는 httpx.MockTransport로 바꿔 끼운다.
서버가 넘기는 입력이 AI 쪽 데이터 클래스·지시문 조립과 맞는지, 결과를 정의서 11/01 형식으로 바꾸는지를 본다.
"""
import json
from datetime import date, datetime, time

import httpx
import pytest

pytest.importorskip("ai.live.config")

from backend.clients.ai_gateway import (BriefingContext, CalendarEvent, CoachingContext, CoachStep, ItemChange,  # noqa: E402
                                        NightContext, NightView, Persona, RealAIGateway, Weather, to_item_changes)
from backend.core.timeutil import KST  # noqa: E402
from backend.domain.models import AlarmRoutineItem, MorningSession, RecentRecords, SessionStep  # noqa: E402

PERSONA = Persona("정우님", "CASUAL", "FRIENDLY", "MALE")
NIGHT = NightView("발표를 잘 마쳤다", "보고서 끝내기", ("10시 회의",), "TIRED", "지쳤지만 뿌듯함", "잘했어요", "")
TALK = [{"role": "assistant", "text": "오늘 어땠어?"}, {"role": "user", "text": "발표 잘 끝났어. 스트레칭은 빼 줘"}]


class FakeLLM:
    """generate_json(system, user) — 받은 지시문을 기록하고 정해 둔 JSON을 돌려준다."""

    def __init__(self, *answers):
        self.answers, self.calls = list(answers), []

    def generate_json(self, system, user):
        self.calls.append((system, user))
        return self.answers.pop(0)


def gateway(llm=None, tokens=None):
    tokens = tokens if tokens is not None else []

    def handler(request: httpx.Request):
        body = json.loads(request.content)
        tokens.append((request.headers.get("x-goog-api-key"), body))
        return httpx.Response(200, json={"name": f"auth_tokens/{len(tokens)}"})
    return RealAIGateway("test-key", http=httpx.Client(transport=httpx.MockTransport(handler)), llm=llm), tokens


def system_text(body):
    return body["bidiGenerateContentSetup"]["systemInstruction"]["parts"][0]["text"]


def tool_names(body):
    return [f["name"] for f in body["bidiGenerateContentSetup"]["tools"][0]["functionDeclarations"]]


def test_night_token_builds_real_setup():
    gw, tokens = gateway()
    t = gw.issue_night_token(NightContext(PERSONA, "23:30", (CalendarEvent("발표", "14:00", "15:00"),), (),
                                          "07:00", ("스트레칭 (축소)", "물 한 잔"), (180, 60)))
    key, body = tokens[0]
    assert key == "test-key" and t.token == "auth_tokens/1" and body["uses"] == 1
    assert t.start_signal and t.model.startswith("models/")
    text = system_text(body)
    assert "정우님" in text and "발표" in text and "스트레칭 (축소)" in text and "3분" in text
    assert body["bidiGenerateContentSetup"]["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"] == "Puck"
    assert tool_names(body) == ["end_session"]


def test_briefing_token_routine_and_skip():
    gw, tokens = gateway()
    gw.issue_briefing_token(BriefingContext(PERSONA, "07:02", (CalendarEvent("회의", "10:00"),), NIGHT,
                                            program_name="현실 모닝루틴", strategy_block="[오늘의 전략]\n- 테스트"))
    body = tokens[-1][1]
    assert "07:02" in system_text(body) and "보고서 끝내기" in system_text(body) and "[오늘의 전략]" in system_text(body)
    assert tool_names(body) == ["start_routine", "end_session"]                 # 루틴이 이어진다
    gw.issue_briefing_token(BriefingContext(PERSONA, "07:02", night=NIGHT, program_name="현실 모닝루틴", coaching_skipped=True))
    assert tool_names(tokens[-1][1]) == ["end_session"]                         # 코칭 안 하는 날은 start_routine 없음


def test_briefing_token_weather():
    gw, tokens = gateway()
    gw.issue_briefing_token(BriefingContext(PERSONA, "07:02", program_name="현실 모닝루틴",
                                            weather=Weather("비", 11.0, 14.0, 8.0, 80, "광진구")))
    text = system_text(tokens[-1][1])
    assert "[오늘 날씨]\n- 지역 광진구, 비, 현재 11도, 최고 14도 / 최저 8도, 강수 확률 80%" in text
    gw.issue_briefing_token(BriefingContext(PERSONA, "07:02", program_name="현실 모닝루틴"))
    assert "[오늘 날씨]\n-" not in system_text(tokens[-1][1])                        # 날씨가 없으면 날씨 칸 없음


def test_briefing_token_content_alarm():
    gw, tokens = gateway()
    gw.issue_briefing_token(BriefingContext(PERSONA, "06:31", content_name="퀴즈"))
    assert "퀴즈" in system_text(tokens[-1][1]) and tool_names(tokens[-1][1]) == ["start_routine", "end_session"]


def test_coaching_token_has_no_following_session():
    gw, tokens = gateway()
    steps = (CoachStep(2, "물 한 잔", 60, "QA"), CoachStep(3, "스트레칭 (축소)", 180, "PLAY", "1. 기지개"))
    gw.issue_coaching_token(CoachingContext(PERSONA, "ROUTINE", steps, resumed=True, night=NIGHT))
    body = tokens[-1][1]
    assert "물 한 잔" in system_text(body) and "1. 기지개" in system_text(body)
    assert tool_names(body) == ["report_step_result", "adjust_step_time", "end_session"]   # 11/01: 이어지는 세션 없음
    gw.issue_coaching_token(CoachingContext(PERSONA, "CONTENT", steps))
    assert tool_names(tokens[-1][1]) == ["report_step_result", "end_session"]   # 콘텐츠는 고정 시간이라 시간 변경 없음


def test_token_http_failure_is_503():
    def handler(request):
        return httpx.Response(500, json={"error": "boom"})
    gw = RealAIGateway("k", http=httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(Exception) as e:
        gw.issue_night_token(NightContext(PERSONA, "23:00"))
    assert getattr(e.value, "code", "") == "AI_UNAVAILABLE"


def test_extract_maps_to_1101_columns():
    llm = FakeLLM({"ai_summary": "발표를 마쳤다", "tomorrow_goal": "보고서", "tomorrow_notes": ["10시 회의"],
                   "condition_label": "tired", "condition_text": "지침", "encouragement": "잘했어", "insight": "",
                   "diary_text": "발표 끝", "override_wake_time": "06:40", "override_skip_coaching": True})
    gw, _ = gateway(llm)
    r = gw.extract_night_record(TALK)
    assert r.status == "DONE" and llm.calls
    f = r.fields
    assert (f["condition_label"], f["override_wake_time"], f["override_skip_coaching"]) == ("TIRED", time(6, 40), True)
    assert f["tomorrow_notes"] == ["10시 회의"] and "override_coaching_min" not in f


def test_routine_changes_use_item_ids():
    items = [AlarmRoutineItem("i1", 1, "스트레칭 (축소)", 180, 180, "PLAY"), AlarmRoutineItem("i2", 2, "물 한 잔", 60, 0, "QA"),
             AlarmRoutineItem("i3", 3, "세수", 60, 60, "QA")]
    llm = FakeLLM({"changes": [{"item": 2, "seconds": None}, {"item": 1, "seconds": 0, "scope": "TOMORROW"}]})
    gw, _ = gateway(llm)
    r = gw.parse_routine_changes(TALK, items)
    # 0초 항목 = AI에는 '뺀 상태', 사용 중 항목은 현재 시간을 함께 본다. 빼기가 먼저 온다
    assert "3분" in llm.calls[0][1] and "뺀 상태" in llm.calls[0][1]
    assert r.status == "DONE" and r.changes == [ItemChange("i1", 0, "TOMORROW"), ItemChange("i2", None, "TOMORROW")]


def test_content_steps_fit_fixed_duration():
    answer = {"steps": [{"name": f"퀴즈 {n}번", "seconds": 200, "response_type": "SPEECH",
                         "script": f"문제: 문제 {n}\n정답: 정답 {n}"} for n in range(1, 4)]}
    gw, _ = gateway(FakeLLM(answer))
    steps = gw.compose_content_steps("QUIZ", "HISTORY", 480, date(2026, 11, 6))
    assert sum(s.planned_sec for s in steps) == 480 and steps[0].response_type == "SPEECH" and "정답:" in steps[0].script


def test_content_steps_fall_back_when_llm_fails():
    class Broken:
        def generate_json(self, *_):
            from ai.tasks.llm import LLMError
            raise LLMError("503")
    gw, _ = gateway(Broken())
    steps = gw.compose_content_steps("EXERCISE", "STRETCH", 600, date(2026, 11, 6))
    assert steps and sum(s.planned_sec for s in steps) == 600


def test_decide_strategy_with_real_records():
    d0 = date(2026, 11, 6)
    sessions, steps = [], []
    for i in range(1, 6):
        day = date(2026, 11, i)
        sid = f"s{i}"
        sessions.append(MorningSession(sid, "u1", "a1", day, "ROUTINE", "t", None, None, time(7), time(7),
                                       alarm_rang_at=datetime(2026, 11, i, 7, 0, tzinfo=KST),
                                       first_response_at=datetime(2026, 11, i, 7, 3, tzinfo=KST), wake_success=i % 2 == 0))
        steps.append(SessionStep(f"st{i}", "u1", sid, 1, "물 한 잔", "i2", 60, step_status="DONE"))
    gw, _ = gateway()
    d = gw.decide_strategy("u1", RecentRecords(sessions, steps, []), d0, "TIRED", "KIND", "rule", None)
    assert d.log_row["user_id"] == "u1" and d.log_row["mode"] == "rule"
    assert set(d.strategy) >= {"coaching_intensity", "briefing_length", "encouragement", "strategy_id"}
    assert d.strategy["coaching_intensity"] != "firm"                           # 피곤한 날·KIND에는 강한 전략 금지
    assert gw.strategy_blocks(d.strategy) == (d.briefing_block, d.coaching_block)   # 재연결 때 같은 문장


def test_new_ai_format_with_scope_and_duration():
    class Res:
        changes = [{"template_item_id": "i1", "duration_sec": 60, "scope": "always"},
                   {"template_item_id": "i2", "duration_sec": 0, "scope": "weird"}]
    assert to_item_changes(Res()) == [ItemChange("i1", 60, "ALWAYS"), ItemChange("i2", 0, "TOMORROW")]
