"""서버용 AI 함수 테스트 (Gemini 호출 없음: 가짜 llm으로 검증 규칙·재시도·실패 처리를 확인한다)."""
import pytest

from ai.tasks import music
from ai.tasks.content_steps import (CONTENT_OPTIONS, CONTENT_TOTAL_SEC, compose_content_steps, fallback_steps, validate_steps, _fit_seconds)
from ai.tasks.llm import GeminiText, HTTPFailure, LLMError, parse_json, response_text
from ai.tasks.night_record import EMPTY, extract_night_record
from ai.tasks.routine_changes import RoutineItem, parse_routine_changes

# ---------- 가짜 llm ----------
class FakeLLM:
    def __init__(self, *results):
        self.results, self.calls, self.last_user = list(results), 0, ""

    def generate_json(self, system, user):
        self.calls += 1
        self.last_user = user
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
        "override_wake_time": "6:40", "override_skip_coaching": True}


def test_extract_normalizes():
    r = extract_night_record(TRANSCRIPT, FakeLLM(GOOD))
    f = r.fields
    assert r.status == "DONE" and f["condition_label"] == "TIRED" and f["tomorrow_notes"] == ["10시 반 치과"]
    assert f["override_wake_time"] == "06:40" and f["override_skip_coaching"] is True and f["insight"] is None
    assert r.to_row()["extract_status"] == "DONE" and "override_alarm_id" not in r.to_row()   # 알람 id는 서버가 정한다


def test_extract_rejects_bad_values_softly():
    bad = {**GOOD, "condition_label": "SAD", "override_wake_time": "25:99", "override_skip_coaching": "true", "tomorrow_goal": "가" * 500}
    f = extract_night_record(TRANSCRIPT, FakeLLM(bad)).fields
    assert f["condition_label"] is None and f["override_wake_time"] is None and f["override_skip_coaching"] is False   # 문자열 "true"는 true가 아니다
    assert len(f["tomorrow_goal"]) == 200
    assert extract_night_record(TRANSCRIPT, FakeLLM({**GOOD, "override_skip_coaching": None})).fields["override_skip_coaching"] is False


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


def test_content_steps_sum_equals_fixed_five_minutes():
    r = compose_content_steps("QUIZ", "HISTORY", FakeLLM(quiz_steps(5, 77)))
    assert r.source == "ai" and sum(s.planned_sec for s in r.steps) == CONTENT_TOTAL_SEC == 300
    assert [s.order for s in r.steps] == [1, 2, 3, 4, 5] and all(s.planned_sec >= 10 for s in r.steps)


def test_fit_seconds_exact_and_min():
    for raw in ([1, 1, 1], [5, 1, 1, 1], [100, 1], [1, 100]):
        out = _fit_seconds(raw, 600)
        assert sum(out) == 600 and min(out) >= 10


def test_content_invalid_input():
    with pytest.raises(ValueError):
        compose_content_steps("DANCE", None, FakeLLM({}))


def test_content_falls_back_when_ai_fails():
    llm = FakeLLM(LLMError("503"))
    r = compose_content_steps("QUIZ", "HISTORY", llm)
    assert r.source == "fallback" and llm.calls == 2 and len(r.steps) == 3 and sum(s.planned_sec for s in r.steps) == 300
    assert all("문제:" in s.script and "정답:" in s.script for s in r.steps)


def test_content_falls_back_on_bad_shape():
    bad_quiz = {"steps": [{"name": "퀴즈 1번", "seconds": 300, "response_type": "SPEECH", "script": "그냥 문제"},
                          {"name": "퀴즈 2번", "seconds": 300, "response_type": "SPEECH", "script": "그냥 문제2"}]}
    assert compose_content_steps("QUIZ", "HISTORY", FakeLLM(bad_quiz)).source == "fallback"
    assert compose_content_steps("MEDITATION", "BREATH", FakeLLM({"steps": [{"name": "x", "seconds": 600, "script": "y"}]})).source == "fallback"  # 1단계뿐


def test_content_unknown_response_type_gets_default():
    data = {"steps": [{"name": "목 돌리기", "seconds": 300, "response_type": "???", "script": "돌려요"},
                      {"name": "어깨", "seconds": 300, "script": "돌려요"}]}
    steps = compose_content_steps("MEDITATION", "BREATH", FakeLLM(data)).steps
    assert [s.response_type for s in steps] == ["PLAY", "PLAY"]


@pytest.mark.parametrize("ctype", CONTENT_OPTIONS)
def test_every_fallback_is_valid(ctype):
    for option in (*CONTENT_OPTIONS[ctype], None):
        steps = fallback_steps(ctype, option)
        assert len(steps) >= (1 if option == "WORDCHAIN" else 2) and sum(s.planned_sec for s in steps) == CONTENT_TOTAL_SEC and all(s.script for s in steps)


def test_validate_steps_requires_script_and_name():
    with pytest.raises(ValueError):
        validate_steps({"steps": [{"name": "a", "seconds": 1, "script": ""}, {"name": "b", "seconds": 1, "script": "x"}]}, "EXERCISE", 600)


# ---------- parse_routine_changes ----------
ITEMS = [RoutineItem("id-stretch", "스트레칭", True, 120), RoutineItem("id-sun", "햇빛 보기", True, 60), RoutineItem("id-read", "독서", True, 300),
         RoutineItem("id-diary", "모닝일기", False, 0)]


def ch(r):
    return [(c.template_item_id, c.duration_sec, c.scope) for c in r.changes]


def parse(changes, items=ITEMS):
    return parse_routine_changes(TRANSCRIPT, items, FakeLLM({"changes": changes}))


def test_routine_remove_time_and_revert_with_scope():
    r = parse([{"item": 1, "seconds": 180, "scope": "ALWAYS"}, {"item": 4, "seconds": None, "scope": "TOMORROW"}, {"item": 3, "seconds": 0, "scope": "TOMORROW"}])
    assert r.status == "DONE"
    assert ch(r) == [("id-read", 0, "TOMORROW"), ("id-stretch", 180, "ALWAYS"), ("id-diary", None, "TOMORROW")]   # 빼기가 먼저, 되돌리기(null)는 시간으로 바꾸는 것과 구분된다


def test_routine_reductions_come_before_increases():
    r = parse([{"item": 1, "seconds": 300, "scope": "TOMORROW"}, {"item": 3, "seconds": 120, "scope": "TOMORROW"}])
    assert ch(r) == [("id-read", 120, "TOMORROW"), ("id-stretch", 300, "TOMORROW")]


def test_routine_seconds_snap_to_30_and_scope_defaults_to_tomorrow():
    r = parse([{"item": 2, "seconds": 100}, {"item": 3, "seconds": 10, "scope": "weird"}])
    assert ch(r) == [("id-read", 30, "TOMORROW"), ("id-sun", 90, "TOMORROW")]   # 줄이는 것이 먼저


def test_routine_ignores_unusable_requests():
    r = parse([{"item": 9, "seconds": 0}, {"item": 0, "seconds": 0}, {"item": "x", "seconds": 0}, {"item": True, "seconds": 0},
               {"item": 4, "seconds": 0},                       # 이미 뺀 항목은 또 뺄 수 없다
               {"item": 3},                                       # 시간을 모르는 '줄여 줘'는 되돌리기(null)가 아니다
               {"item": 3, "seconds": 5000}, {"item": 3, "seconds": -30}, {"item": 3, "seconds": "abc"},
               {"item": 1, "seconds": 120, "scope": "TOMORROW"},   # 지금과 같은 시간이면 바꿀 것이 없다
               "문자열", None])
    assert r.status == "DONE" and ch(r) == []


def test_routine_same_item_same_scope_keeps_the_last_one():
    r = parse([{"item": 1, "seconds": 60, "scope": "TOMORROW"}, {"item": 1, "seconds": 90, "scope": "TOMORROW"}, {"item": 1, "seconds": 180, "scope": "ALWAYS"}])
    assert sorted(ch(r), key=str) == sorted([("id-stretch", 90, "TOMORROW"), ("id-stretch", 180, "ALWAYS")], key=str)


def test_routine_never_removes_everything():
    items = [RoutineItem("a", "스트레칭", True, 120), RoutineItem("b", "독서", True, 60)]
    r = parse([{"item": 1, "seconds": 0}, {"item": 2, "seconds": 0}], items)
    assert ch(r) == [] and "모든 항목" in r.note
    # 다시 넣거나 시간을 정하는 항목이 같이 있으면 루틴이 비지 않으므로 적용된다
    items = [RoutineItem("a", "스트레칭", True, 120), RoutineItem("b", "독서", False, 0)]
    assert ch(parse([{"item": 1, "seconds": 0}, {"item": 2, "seconds": 60}], items)) == [("a", 0, "TOMORROW"), ("b", 60, "TOMORROW")]


def test_routine_items_without_durations_still_work():
    items = [RoutineItem("a", "스트레칭"), RoutineItem("b", "독서", enabled=False)]    # 서버가 시간을 채워 주기 전의 형식
    r = parse([{"item": 1, "seconds": 0}, {"item": 2, "seconds": 60}], items)
    assert ch(r) == [("a", 0, "TOMORROW"), ("b", 60, "TOMORROW")]


def test_routine_changes_are_readable_by_the_server_adapter():
    c = parse([{"item": 1, "seconds": 0, "scope": "ALWAYS"}]).changes[0]
    assert (getattr(c, "template_item_id"), getattr(c, "duration_sec"), getattr(c, "scope")) == ("id-stretch", 0, "ALWAYS")   # ai_gateway.to_item_changes가 getattr로 읽는다


def test_routine_no_items_or_no_speech_skips_ai():
    llm = FakeLLM({"changes": [{"item": 1, "seconds": 0}]})
    assert parse_routine_changes(TRANSCRIPT, [], llm).changes == []
    assert parse_routine_changes([], ITEMS, llm).changes == []
    assert llm.calls == 0


def test_routine_failure_status():
    llm = FakeLLM({"changes": "배열 아님"})
    r = parse_routine_changes(TRANSCRIPT, ITEMS, llm)
    assert r.status == "FAILED" and r.changes == [] and llm.calls == 2


def test_routine_prompt_shows_current_times():
    llm = FakeLLM({"changes": []})
    parse_routine_changes(TRANSCRIPT, ITEMS, llm)
    assert "1. 스트레칭 (2분)" in llm.last_user and "3. 독서 (5분)" in llm.last_user and "4. 모닝일기 (뺀 상태)" in llm.last_user


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
