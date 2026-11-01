"""서버용 AI 함수 테스트 (Gemini 호출 없음: 가짜 llm으로 검증 규칙·재시도·실패 처리를 확인한다)."""
import pytest

from ai.tasks import music
from ai.tasks.content_steps import (CONTENT_OPTIONS, compose_content_steps, fallback_steps, validate_steps, _fit_seconds)
from ai.tasks.llm import GeminiText, HTTPFailure, LLMError, parse_json, response_text
from ai.tasks.night_record import EMPTY, extract_night_record
from ai.tasks.routine_changes import RoutineItem, parse_routine_changes

# ---------- 가짜 llm ----------
class FakeLLM:
    def __init__(self, *results):
        self.results, self.calls = list(results), 0

    def generate_json(self, system, user):
        self.calls += 1
        r = self.results.pop(0) if len(self.results) > 1 else self.results[0]
        if isinstance(r, Exception):
            raise r
        return r

TRANSCRIPT = [{"role": "assistant", "text": "오늘 하루 어땠어요?", "at": "22:00"},
              {"role": "user", "text": "회의가 많아서 피곤했어요", "at": "22:01"}]


# ---------- llm.py ----------
def test_parse_json_variants():
    assert parse_json('```json\n{"a": 1}\n```') == {"a": 1}
    assert parse_json('설명 {"a": 1} 끝') == {"a": 1}
    for bad in ("", "no json", "{broken", "[1, 2]"):
        with pytest.raises(LLMError):
            parse_json(bad)


def test_response_text_joins_all_parts_and_skips_thoughts():
    resp = {"candidates": [{"content": {"parts": [{"text": '{"a":', "thought": False}, {"text": "생각", "thought": True}, {"text": ' 1}'}]}}]}
    assert response_text(resp) == '{"a": 1}'
    with pytest.raises(LLMError):
        response_text({"candidates": []})
    with pytest.raises(LLMError):
        response_text({"candidates": [{"content": {"parts": [{"text": "  "}]}, "finishReason": "SAFETY"}]})


def ok(text):
    return {"candidates": [{"content": {"parts": [{"text": text}]}}]}


def test_gemini_falls_back_to_next_model_on_503():
    seen = []

    def transport(model, body):
        seen.append(model)
        if model == "m1":
            raise HTTPFailure(503)
        return ok('{"x": 1}')

    g = GeminiText(models=("m1", "m2"), transport=transport)
    assert g.generate_json("s", "u") == {"x": 1}
    assert seen == ["m1", "m2"] and g.calls == 2


def test_gemini_drops_thinking_config_on_400():
    bodies = []

    def transport(model, body):
        bodies.append(body)
        if "thinkingConfig" in body["generationConfig"]:
            raise HTTPFailure(400)
        return ok('{"x": 1}')

    assert GeminiText(models=("m1",), transport=transport).generate_json("s", "u") == {"x": 1}
    assert "thinkingConfig" in bodies[0]["generationConfig"] and "thinkingConfig" not in bodies[1]["generationConfig"]


def test_gemini_all_models_fail_and_non_retryable():
    with pytest.raises(LLMError, match="모든 모델"):
        GeminiText(models=("m1", "m2"), transport=lambda m, b: (_ for _ in ()).throw(HTTPFailure(503))).generate_json("s", "u")
    with pytest.raises(LLMError, match="HTTP 403"):
        GeminiText(models=("m1", "m2"), transport=lambda m, b: (_ for _ in ()).throw(HTTPFailure(403, "denied"))).generate_json("s", "u")


# ---------- extract_night_record ----------
GOOD = {"ai_summary": "회의가 많아 피곤했음", "tomorrow_goal": "발표 잘 마치기", "tomorrow_notes": ["10시 반 치과", "", "  "],
        "condition_label": "tired", "condition_text": "지침", "encouragement": "수고했어요", "insight": "", "diary_text": "회의가 많아 피곤한 하루",
        "override_wake_time": "6:40", "override_coaching_min": 10}


def test_extract_normalizes():
    r = extract_night_record(TRANSCRIPT, FakeLLM(GOOD))
    f = r.fields
    assert r.status == "DONE" and f["condition_label"] == "TIRED" and f["tomorrow_notes"] == ["10시 반 치과"]
    assert f["override_wake_time"] == "06:40" and f["override_coaching_min"] == 10 and f["insight"] is None
    assert r.to_row()["extract_status"] == "DONE" and "override_alarm_id" not in r.to_row()   # 알람 id는 서버가 정한다


def test_extract_rejects_bad_values_softly():
    bad = {**GOOD, "condition_label": "SAD", "override_wake_time": "25:99", "override_coaching_min": 5, "tomorrow_goal": "가" * 500}
    f = extract_night_record(TRANSCRIPT, FakeLLM(bad)).fields
    assert f["condition_label"] is None and f["override_wake_time"] is None and f["override_coaching_min"] is None
    assert len(f["tomorrow_goal"]) == 200
    assert extract_night_record(TRANSCRIPT, FakeLLM({**GOOD, "override_coaching_min": 0})).fields["override_coaching_min"] == 0


def test_extract_retries_then_succeeds_or_fails():
    llm = FakeLLM(LLMError("깨짐"), GOOD)
    assert extract_night_record(TRANSCRIPT, llm).status == "DONE" and llm.calls == 2
    llm = FakeLLM({**GOOD, "tomorrow_notes": "배열 아님"})
    r = extract_night_record(TRANSCRIPT, llm)
    assert r.status == "FAILED" and r.fields == EMPTY and llm.calls == 2 and "배열" in r.error


def test_extract_without_user_speech_skips_ai():
    llm = FakeLLM(GOOD)
    for t in ([], None, "not json", [{"role": "assistant", "text": "안녕"}], [{"role": "user", "text": "  "}]):
        r = extract_night_record(t, llm)
        assert r.status == "DONE" and r.fields == EMPTY
    assert llm.calls == 0


def test_extract_accepts_role_names_and_json_string():
    import json
    llm = FakeLLM(GOOD)
    t = json.dumps([{"role": "model", "text": "안녕하세요"}, {"role": "user", "text": "네"}])
    assert extract_night_record(t, llm).status == "DONE" and llm.calls == 1


def test_empty_defaults_are_not_shared():
    a = extract_night_record([], FakeLLM(GOOD)).fields
    a["tomorrow_notes"].append("x")
    assert extract_night_record([], FakeLLM(GOOD)).fields["tomorrow_notes"] == []


# ---------- compose_content_steps ----------
def quiz_steps(n=5, secs=100):
    return {"steps": [{"name": f"퀴즈 {i}번", "seconds": secs, "response_type": "SPEECH", "script": f"문제: Q{i}\n정답: A{i}"} for i in range(1, n + 1)]}


def test_content_steps_sum_equals_coaching_length():
    for minutes in (10, 20):
        r = compose_content_steps("QUIZ", "HISTORY", minutes, FakeLLM(quiz_steps(5, 77)))
        assert r.source == "ai" and sum(s.planned_sec for s in r.steps) == minutes * 60
        assert [s.order for s in r.steps] == [1, 2, 3, 4, 5] and all(s.planned_sec >= 10 for s in r.steps)


def test_fit_seconds_exact_and_min():
    for raw in ([1, 1, 1], [5, 1, 1, 1], [100, 1], [1, 100]):
        out = _fit_seconds(raw, 600)
        assert sum(out) == 600 and min(out) >= 10


def test_content_zero_minutes_no_steps_and_no_call():
    llm = FakeLLM(quiz_steps())
    assert compose_content_steps("QUIZ", "GENERAL", 0, llm).steps == [] and llm.calls == 0


def test_content_invalid_input():
    with pytest.raises(ValueError):
        compose_content_steps("DANCE", None, 10, FakeLLM({}))
    with pytest.raises(ValueError):
        compose_content_steps("QUIZ", None, 15, FakeLLM({}))


def test_content_falls_back_when_ai_fails():
    llm = FakeLLM(LLMError("503"))
    r = compose_content_steps("QUIZ", "GENERAL", 10, llm)
    assert r.source == "fallback" and llm.calls == 2 and len(r.steps) == 3 and sum(s.planned_sec for s in r.steps) == 600
    assert all("문제:" in s.script and "정답:" in s.script for s in r.steps)


def test_content_falls_back_on_bad_shape():
    bad_quiz = {"steps": [{"name": "퀴즈 1번", "seconds": 300, "response_type": "SPEECH", "script": "그냥 문제"},
                          {"name": "퀴즈 2번", "seconds": 300, "response_type": "SPEECH", "script": "그냥 문제2"}]}
    assert compose_content_steps("QUIZ", None, 10, FakeLLM(bad_quiz)).source == "fallback"
    assert compose_content_steps("EXERCISE", "STRETCH", 10, FakeLLM({"steps": [{"name": "x", "seconds": 600, "script": "y"}]})).source == "fallback"  # 1단계뿐


def test_content_unknown_response_type_gets_default():
    data = {"steps": [{"name": "목 돌리기", "seconds": 300, "response_type": "???", "script": "돌려요"},
                      {"name": "어깨", "seconds": 300, "script": "돌려요"}]}
    steps = compose_content_steps("EXERCISE", "STRETCH", 10, FakeLLM(data)).steps
    assert [s.response_type for s in steps] == ["PLAY", "PLAY"]


@pytest.mark.parametrize("ctype", CONTENT_OPTIONS)
@pytest.mark.parametrize("minutes", (10, 20))
def test_every_fallback_is_valid(ctype, minutes):
    for option in (*CONTENT_OPTIONS[ctype], None):
        steps = fallback_steps(ctype, option, minutes)
        assert len(steps) >= 2 and sum(s.planned_sec for s in steps) == minutes * 60 and all(s.script for s in steps)


def test_validate_steps_requires_script_and_name():
    with pytest.raises(ValueError):
        validate_steps({"steps": [{"name": "a", "seconds": 1, "script": ""}, {"name": "b", "seconds": 1, "script": "x"}]}, "EXERCISE", 600)


# ---------- parse_routine_changes ----------
ITEMS = [RoutineItem("id-stretch", "스트레칭"), RoutineItem("id-sun", "햇빛 보기"), RoutineItem("id-read", "독서"),
         RoutineItem("id-diary", "모닝일기", enabled=False)]


def test_routine_remove_and_restore():
    r = parse_routine_changes(TRANSCRIPT, ITEMS, FakeLLM({"remove": [3], "restore": [4]}))
    assert r.status == "DONE" and r.remove == ["id-read"] and r.restore == ["id-diary"]


def test_routine_ignores_invalid_numbers_and_wrong_state():
    r = parse_routine_changes(TRANSCRIPT, ITEMS, FakeLLM({"remove": [9, 0, "x", True, 4, 3, 3], "restore": [1, 2]}))
    assert r.remove == ["id-read"] and r.restore == []     # 이미 뺀 항목(4)은 뺄 수 없고, 사용 중인 항목(1,2)은 다시 넣을 수 없다


def test_routine_never_removes_everything():
    items = [RoutineItem("a", "스트레칭"), RoutineItem("b", "독서")]
    r = parse_routine_changes(TRANSCRIPT, items, FakeLLM({"remove": [1, 2], "restore": []}))
    assert r.remove == [] and "모든 항목" in r.note
    # 다시 넣는 항목이 같이 있으면 루틴이 비지 않으므로 적용된다
    items = [RoutineItem("a", "스트레칭"), RoutineItem("b", "독서", enabled=False)]
    r = parse_routine_changes(TRANSCRIPT, items, FakeLLM({"remove": [1], "restore": [2]}))
    assert r.remove == ["a"] and r.restore == ["b"]


def test_routine_no_items_or_no_speech_skips_ai():
    llm = FakeLLM({"remove": [1]})
    assert parse_routine_changes(TRANSCRIPT, [], llm).remove == []
    assert parse_routine_changes([], ITEMS, llm).remove == []
    assert llm.calls == 0


def test_routine_failure_status():
    llm = FakeLLM({"remove": "배열 아님"})
    r = parse_routine_changes(TRANSCRIPT, ITEMS, llm)
    assert r.status == "FAILED" and r.remove == [] and llm.calls == 2


# ---------- music ----------
def test_music_prompts_cover_spec_values():
    assert set(music.REWAKE_GENRES) == {"POP", "JAZZ", "CLASSIC", "DANCE", "ACOUSTIC"}
    assert set(music.SLEEP_MOODS) == {"GOOD", "NORMAL", "TIRED", "STRESSED"}
    assert "no vocals" in music.build_music_prompt("REWAKE", genre="JAZZ")
    assert "sleep" in music.build_music_prompt("SLEEP", mood="TIRED").lower()


@pytest.mark.parametrize("args", [("REWAKE", "BLUES", None), ("REWAKE", "POP", "GOOD"), ("SLEEP", None, "SAD"),
                                  ("SLEEP", "POP", "GOOD"), ("REWAKE", None, None), ("OTHER", "POP", None)])
def test_music_invalid_combinations(args):
    with pytest.raises(ValueError):
        music.build_music_prompt(*args)


def test_music_rows_match_table_constraint():
    rows = music.all_music_rows()
    assert len(rows) == 9
    for r in rows:   # 정의서 제약: REWAKE이면 GENRE만, SLEEP이면 MOOD만
        assert (r["genre"] is None) == (r["music_type"] == "SLEEP") and (r["mood"] is None) == (r["music_type"] == "REWAKE")
        assert r["music_prompt"]


def test_prompts_forbid_invention():
    from ai.tasks import night_record, content_steps
    assert "AI가 만들어 내지 않는다" in night_record.SYSTEM and "일반적인 조언을 만들어 쓰지 않는다" in night_record.SYSTEM
    assert "정답이 들어 있지 않아야" in content_steps.SYSTEM
