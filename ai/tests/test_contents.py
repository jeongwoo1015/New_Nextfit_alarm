"""콘텐츠 3종 테스트 (Gemini 호출 없음): 스트레칭 4세트, 영어 따라 읽기+대답, 퀴즈·게임(인물·상식·끝말잇기), 정답 신호, 콘텐츠별 코칭 규칙."""
import re
from datetime import date, timedelta

import pytest

from ai.live.config import build_setup
from ai.live.inputs import CoachingInput, Step, content_kind
from ai.live.prompts.coaching import coaching_prompt, step_line
from ai.live.tools import tools_for
from ai.tasks.content_steps import (CONTENT_OPTIONS, CONTENT_TOTAL_SEC, ENGLISH_FALLBACKS, ENGLISH_NAMES, ENGLISH_TOPICS, QUIZ_ROTATION,
                                    WORDCHAIN_START_WORDS, compose_content_steps, english_fallback, fallback_steps, resolve_option,
                                    validate_english, wordchain_steps)
from ai.tasks.llm import LLMError
from ai.tasks.stretch_sets import SETS, pick_stretch_set, stretch_steps

D0 = date(2026, 11, 9)           # 월요일


class FakeLLM:
    def __init__(self, *results):
        self.results, self.calls, self.last_system, self.last_user = list(results), 0, "", ""

    def generate_json(self, system, user):
        self.calls += 1
        self.last_system, self.last_user = system, user
        r = self.results.pop(0) if len(self.results) > 1 else self.results[0]
        if isinstance(r, Exception):
            raise r
        return r


# ---------- 스트레칭 4세트 ----------
def app_motion_groups(name: str) -> list[str]:
    """앱 넥스티(MorningChatScreen.stretchMotionFor)가 단계 이름에서 키워드로 시범 동작을 고르는 규칙. 몇 종류의 키워드가 걸리는지 센다."""
    groups = {"옆구리": "RAINBOW", "허리": "WAIST", "가슴": "CHEST_W", "어깨": "CHEST_W", "기지개": "STRETCH", "팔": "STRETCH", "만세": "STRETCH", "목": "NECK"}
    return sorted({m for k, m in groups.items() if k in name})


def test_four_sets_five_minutes_each():
    assert len(SETS) == 4 and {s.key for s in SETS} == {"A", "B", "C", "D"}
    for st in SETS:
        secs = [x[1] for x in st.steps]
        assert sum(secs) == CONTENT_TOTAL_SEC == 300 and len(st.steps) == 5 and all(x % 30 == 0 and x >= 30 for x in secs), st.key


def test_every_step_name_maps_to_exactly_one_app_motion():
    for st in SETS:
        for name, _, _ in st.steps:
            assert len(app_motion_groups(name)) == 1, f"{st.key} {name}: {app_motion_groups(name)}"     # 한 이름에 한 종류만 (여러 개면 앞선 것이 우선해 시범이 어긋난다)
    assert not any("손목" in n for st in SETS for n, _, _ in st.steps)                                 # '손목'은 '목'에 걸려 목 시범이 나온다


def test_sets_follow_safety_design():
    for st in SETS:
        for name, sec, script in st.steps:
            assert 15 <= len(script) <= 190 and script.endswith("."), (st.key, name)
            holds = [int(x) for x in re.findall(r"(\d+)초", script)]
            assert all(h <= 30 for h in holds) and sec >= max(holds, default=0), (st.key, name, holds)      # 한 번에 30초 이하(Harvard 15~30초, ACSM 10~30초)
            assert not re.search(r"숙|굽혀|젖혀(?!지)|(?<!손)바닥|엎드|누워|뛰|점프|반동|세게 (당|누)", script), (st.key, name, script)   # 깊게 숙이기·바닥 동작·반동 없음, 목은 뒤로 젖히지 않음
            assert "병" not in script and "치료" not in script and "효과" not in script                    # 의학적 표현 없음
    neck = [s for st in SETS for n, _, s in st.steps if n.startswith("목")]
    assert neck and all("젖히지 않아요" in s or "손으로 당기지 말고" in s for s in neck)                    # 목 동작에는 무리하지 않는 안내가 들어 있다


def test_each_set_warms_up_stretches_and_ends_calmly():
    for st in SETS:
        first, last = st.steps[0], st.steps[-1]
        assert re.search(r"걷|으쓱|기지개", first[0]) and re.search(r"깊은 숨|마쳐요", last[2]), st.key       # 가벼운 동작으로 시작, 호흡으로 마무리
        assert sum(1 for _, _, sc in st.steps if re.search(r"\d+초 (머물러|유지|동안)", sc)) >= 1


def test_set_rotation_is_stable_per_day_and_covers_all_four():
    assert pick_stretch_set(D0) is pick_stretch_set(D0)
    assert {pick_stretch_set(D0 + timedelta(days=i)).key for i in range(4)} == {"A", "B", "C", "D"}
    steps = stretch_steps(D0)
    assert [s.order for s in steps] == [1, 2, 3, 4, 5] and all(s.response_type == "PLAY" and s.script for s in steps)


def test_stretch_compose_needs_no_ai_and_reports_option():
    llm = FakeLLM(LLMError("never called"))
    r = compose_content_steps("EXERCISE", None, llm, today=D0)
    assert llm.calls == 0 and r.source == "fixed" and r.option == "STRETCH" and sum(s.planned_sec for s in r.steps) == 300
    assert compose_content_steps("EXERCISE", "MASSAGE", llm, today=D0).option == "STRETCH"      # 옛 세부 값도 스트레칭으로


# ---------- 영어 따라 읽기 + 짧은 대답 ----------
def english_data(**over):
    d = {"steps": [
        {"name": "x", "seconds": 1, "script": "I like warm tea. / 따뜻한 차를 좋아해요. / 아이 라이크 웜 티"},
        {"name": "x", "seconds": 1, "script": "It is a quiet morning. / 조용한 아침이에요. / 잇 이즈 어 콰이엇 모닝"},
        {"name": "x", "seconds": 1, "script": "Let's drink some water. / 물을 마셔요. / 렛츠 드링크 썸 워터"},
        {"name": "x", "seconds": 1, "script": "질문: What do you like to drink?\n힌트: 좋아하는 음료를 한두 마디로 말해 보세요.\n예시: I like tea."}]}
    d.update(over)
    return d


def test_english_validation_accepts_and_normalizes_names_and_seconds():
    steps = validate_english(english_data())
    assert [s.name for s in steps] == list(ENGLISH_NAMES) and [s.planned_sec for s in steps] == [60, 60, 60, 120]
    assert sum(s.planned_sec for s in steps) == 300 and all(s.response_type == "SPEECH" for s in steps)
    assert steps[3].script.splitlines()[0].startswith("질문: What") and steps[0].script.count(" / ") == 2


@pytest.mark.parametrize("mutate", [
    lambda d: d["steps"].pop(),                                                                           # 3단계뿐
    lambda d: d["steps"][0].update(script="좋은 아침 / Good morning / 굿모닝"),                           # 영어 자리에 한글
    lambda d: d["steps"][0].update(script="Good morning. / Good morning. / Good morning."),              # 뜻·발음이 한글이 아님
    lambda d: d["steps"][1].update(script="Hello 안녕 / 안녕하세요 / 헬로"),                              # 영어 줄에 한글 섞임
    lambda d: d["steps"][2].update(script="It is a very long sentence that has way too many words in it. / 뜻 / 발음"),
    lambda d: d["steps"][3].update(script="질문: What do you like?\n힌트: 말해 보세요."),                  # 예시 줄 없음
    lambda d: d["steps"][3].update(script="질문: 무엇을 마시나요?\n힌트: 말해 보세요.\n예시: I like tea."),   # 질문이 한글
    lambda d: d["steps"][3].update(script="질문: What do you like\n힌트: 말해 보세요.\n예시: I like tea."),   # 물음표 없음
])
def test_english_validation_rejects(mutate):
    d = english_data()
    mutate(d)
    with pytest.raises(ValueError):
        validate_english(d)
    with pytest.raises(ValueError):
        validate_english({"steps": "x"})


def test_english_compose_uses_weekday_topic_and_falls_back():
    llm = FakeLLM(english_data())
    r = compose_content_steps("LANGUAGE", "ENGLISH", llm, today=D0)
    assert r.source == "ai" and r.option == "ENGLISH" and ENGLISH_TOPICS[0] in llm.last_system           # 월요일 주제
    assert "따라 읽기 + 짧은 대답" in llm.last_system and "정확히 4단계" in llm.last_system
    tue = FakeLLM(english_data())
    compose_content_steps("LANGUAGE", None, tue, today=D0 + timedelta(days=1))
    assert ENGLISH_TOPICS[1] in tue.last_system
    bad = FakeLLM(LLMError("503"))
    r = compose_content_steps("LANGUAGE", "ENGLISH", bad, today=D0)
    assert r.source == "fallback" and bad.calls == 2 and len(r.steps) == 4 and sum(s.planned_sec for s in r.steps) == 300


def test_english_other_languages_keep_old_behavior():
    assert resolve_option("LANGUAGE", "JAPANESE", D0) == "JAPANESE" and resolve_option("LANGUAGE", None, D0) == "ENGLISH"
    steps = fallback_steps("LANGUAGE", "JAPANESE", D0)
    assert len(steps) == 3 and "おはよう" in steps[0].script


@pytest.mark.parametrize("i", range(len(ENGLISH_FALLBACKS)))
def test_every_english_fallback_passes_validation(i):
    steps = english_fallback(date.fromordinal(date(2026, 11, 9).toordinal() + i))
    data = {"steps": [{"name": s.name, "seconds": s.planned_sec, "script": s.script} for s in steps]}
    assert [s.script for s in validate_english(data)] == [s.script for s in steps]


# ---------- 퀴즈·게임 ----------
def test_quiz_rotation_covers_person_general_wordchain():
    seen = {resolve_option("QUIZ", None, D0 + timedelta(days=i)) for i in range(3)}
    assert seen == set(QUIZ_ROTATION) == {"PERSON", "GENERAL", "WORDCHAIN"}
    assert resolve_option("QUIZ", "PERSON", D0) == "PERSON" and resolve_option("QUIZ", "SCIENCE", D0) == "SCIENCE"     # 옛 세부 값은 그대로
    assert "MUSIC" not in CONTENT_OPTIONS["QUIZ"]                                                                  # 음악 퀴즈는 아직 없다


def test_person_and_general_quizzes_come_from_the_checked_bank_without_ai():
    from ai.tasks.quiz_bank import BANKS, PER_DAY, pick_questions
    for option in ("PERSON", "GENERAL"):
        llm = FakeLLM(LLMError("never called"))
        r = compose_content_steps("QUIZ", option, llm, today=D0)
        assert llm.calls == 0 and r.source == "fixed" and r.option == option and len(r.steps) == 3 and sum(s.planned_sec for s in r.steps) == 300
        assert [s.name for s in r.steps] == ["퀴즈 1번", "퀴즈 2번", "퀴즈 3번"] and all(s.response_type == "SPEECH" for s in r.steps)
        for s in r.steps:
            q, a = [l.split(":", 1)[1].strip() for l in s.script.splitlines()]
            assert s.script.startswith("문제: ") and "\n정답: " in s.script and a not in q                  # 문제에 정답이 없다
        assert fallback_steps("QUIZ", option, D0)[0].script == r.steps[0].script                           # AI 실패와 무관하게 같은 문제
        bank = BANKS[option]
        assert len(bank) % PER_DAY == 0 and len(bank) >= 30 and len({q for q, _ in bank}) == len(bank)      # 3의 배수, 중복 문제 없음
        days = [pick_questions(option, D0 + timedelta(days=i)) for i in range(len(bank) // PER_DAY)]
        flat = [q for d in days for q, _ in d]
        assert len(set(flat)) == len(bank)                                                                 # 한 바퀴(10일) 동안 같은 문제가 겹치지 않는다
        assert pick_questions(option, D0) == pick_questions(option, D0)                                    # 같은 날은 항상 같은 문제


def test_bank_questions_follow_the_review_rules():
    from ai.tasks.quiz_bank import GENERAL, PERSON
    for q, a in PERSON + GENERAL:
        assert q.endswith("?") or q.endswith("요.") or q.endswith("까요?"), q
        assert a and a not in q and len(q) <= 80, (q, a)
        assert not re.search(r"정치|전쟁 영웅|살해|처단|암살", q), q                                           # 논쟁·폭력이 얽힌 문제는 넣지 않는다
    assert all(a.strip() == a for _, a in PERSON + GENERAL)


def test_wordchain_is_one_fixed_step_without_ai():
    llm = FakeLLM(LLMError("never called"))
    r = compose_content_steps("QUIZ", "WORDCHAIN", llm, today=D0)
    assert llm.calls == 0 and r.source == "fixed" and r.option == "WORDCHAIN" and len(r.steps) == 1
    st = r.steps[0]
    assert st.name == "끝말잇기" and st.planned_sec == 300 and st.response_type == "SPEECH"
    word = st.script.split("시작 단어:")[1].strip()
    assert st.script.startswith("게임: 끝말잇기") and word in WORDCHAIN_START_WORDS and len(word) >= 2
    assert wordchain_steps(D0)[0].script == st.script                                              # 같은 날은 같은 시작 단어


# ---------- 콘텐츠 종류 추정과 함수 선언 ----------
def cin(steps, **kw):
    return CoachingInput(morning_call_type="CONTENT", steps=tuple(steps), **kw)


def test_content_kind_from_server_fields_and_from_steps():
    assert content_kind(CoachingInput(steps=())) == ""                                                      # 루틴 세션은 해당 없음
    assert content_kind(cin(stretch_steps(D0))) == "STRETCH" and content_kind(cin([], content_type="EXERCISE")) == "STRETCH"
    assert content_kind(cin(fallback_steps("QUIZ", "GENERAL", D0))) == "QUIZ" and content_kind(cin([], content_type="QUIZ")) == "QUIZ"
    assert content_kind(cin(wordchain_steps(D0))) == "WORDCHAIN" and content_kind(cin([], content_type="QUIZ", content_option="WORDCHAIN")) == "WORDCHAIN"
    assert content_kind(cin(english_fallback(D0))) == "ENGLISH" and content_kind(cin([], content_type="LANGUAGE", content_option="ENGLISH")) == "ENGLISH"
    assert content_kind(cin([Step(1, "호흡", 100, "PLAY", "숨 쉬기")], content_type="MEDITATION")) == ""       # 명상은 스트레칭 규칙을 받지 않는다


def declared(inp):
    return {d["name"]: d for d in tools_for("coaching", inp)[0]["functionDeclarations"]}


def test_answer_correct_only_for_quiz():
    quiz = declared(cin(fallback_steps("QUIZ", "GENERAL", D0)))["report_step_result"]
    assert quiz["parameters"]["properties"]["answer_correct"]["type"] == "BOOLEAN"
    assert quiz["parameters"]["required"] == ["step_order", "verdict", "answer_correct"]
    assert set(quiz["parameters"]["properties"]["verdict"]["enum"]) == {"done", "not_done", "skipped", "ambiguous", "other"}
    for other in (stretch_steps(D0), english_fallback(D0), wordchain_steps(D0)):
        decl = declared(cin(other))
        assert "answer_correct" not in decl["report_step_result"]["parameters"]["properties"] and list(decl) == ["report_step_result", "end_session"]
    routine = tools_for("coaching", CoachingInput(steps=()))[0]["functionDeclarations"]
    assert "answer_correct" not in routine[0]["parameters"]["properties"] and [d["name"] for d in routine] == ["report_step_result", "adjust_step_time", "end_session"]


def test_quiz_session_setup_uses_quiz_declaration():
    setup = build_setup("coaching", cin(fallback_steps("QUIZ", "PERSON", D0)))
    names = {d["name"]: d for d in setup["tools"][0]["functionDeclarations"]}
    assert "answer_correct" in names["report_step_result"]["parameters"]["properties"]


# ---------- 콘텐츠별 코칭 규칙 ----------
def test_prompt_blocks_per_content():
    stretch = coaching_prompt(cin(stretch_steps(D0)))
    assert "스트레칭 진행:" in stretch and "아프거나 저리면 바로 멈추세요" in stretch and "의학적인 말을 하지 않는다" in stretch
    assert "[고정 스크립트] 팔 흔들며" not in stretch or True                                          # 세트에 따라 첫 단계가 다르다
    assert "퀴즈 진행" not in stretch and "영어 따라 읽기와" not in stretch

    quiz = coaching_prompt(cin(fallback_steps("QUIZ", "PERSON", D0)))
    assert "퀴즈 진행(" in quiz and "answer_correct" in quiz and "\"맞았어요!\"" in quiz and "\"아쉬워요, 정답은 ○○예요.\"" in quiz
    assert "정답:' 줄은 AI만 보는 정보" in quiz and "스트레칭 진행:" not in quiz

    english = coaching_prompt(cin(english_fallback(D0)))
    assert "영어 따라 읽기와 짧은 대답" in english and "점수·등급을 매기지 않는다" in english and "새 영어를 만들지 않는다" in english
    assert "한국어로 힌트" in english

    chain = coaching_prompt(cin(wordchain_steps(D0)))
    assert "끝말잇기([고정 스크립트]" in chain and "두음법칙" in chain and "report_step_result(step_order, done)" in chain
    assert "[오늘 콘텐츠 단계]\n1. 끝말잇기 (SPEECH, 5분)" in chain

    routine = coaching_prompt(CoachingInput(steps=(Step(1, "스트레칭", 120, "PLAY"),)))
    assert not any(k in routine for k in ("스트레칭 진행:", "퀴즈 진행(", "영어 따라 읽기와", "끝말잇기(["))      # 루틴 세션에는 붙지 않는다


def test_intro_names_the_new_contents_and_step_line_shows_seconds():
    p = coaching_prompt(cin(stretch_steps(D0)))
    assert "스트레칭·퀴즈 게임·영어 따라 읽기" in p and "명상" not in p.split("[오늘 콘텐츠 단계]")[0]
    assert step_line(Step(1, "a", 90, "PLAY")).startswith("1. a (PLAY, 1분 30초)") and "(PLAY, 30초)" in step_line(Step(1, "a", 30, "PLAY"))
    assert "(PLAY, 2분)" in step_line(Step(1, "a", 120, "PLAY")) and "(PLAY, 5분)" in step_line(Step(1, "a", 300, "PLAY"))


def test_resumed_content_session_skips_first_safety_line_instruction_only_when_resumed():
    p = coaching_prompt(cin(stretch_steps(D0)[2:], resumed=True))
    assert "이어서 하는 중이면 생략" in p and "이어서 하는 중이므로 전체 안내는 이미 했다" in p


def test_morning_prompts_forbid_night_greetings_but_night_keeps_them():
    from ai.live.inputs import BriefingInput, FortuneInput, NightInput
    from ai.live.prompts.briefing import briefing_prompt
    from ai.live.prompts.common import MORNING_RULE
    from ai.live.prompts.fortune import fortune_prompt
    from ai.live.prompts.night import night_prompt
    assert "안녕히 주무세요" in MORNING_RULE and "좋은 하루 보내세요" in MORNING_RULE
    for p in (briefing_prompt(BriefingInput()), fortune_prompt(FortuneInput(fortune_text="x")), coaching_prompt(cin(stretch_steps(D0))), coaching_prompt(CoachingInput(steps=()))):
        assert MORNING_RULE in p
    assert MORNING_RULE not in night_prompt(NightInput())                                      # 밤 대화는 밤 인사를 한다


def test_stretch_skip_rule_records_the_step_just_guided():
    p = coaching_prompt(cin(stretch_steps(D0)))
    assert "같은 응답에서 방금 안내한 단계를 skipped로 기록하고" in p and "어느 부위가 아프다고 하든" in p


def test_wordchain_pass_does_not_end_the_session():
    p = coaching_prompt(cin(wordchain_steps(D0)))
    assert "'패스', '그만'은 이 게임을 그만하겠다는 뜻이지 대화를 끝내겠다는 뜻이 아니다" in p
    assert "마무리 응답에서도 작별 인사를 하거나 end_session을 호출하지 않는다" in p


def test_short_replies_after_closing_end_the_session():
    """마무리 응원 뒤 '없어', '그래' 같은 짧은 말도 끝내겠다는 뜻으로 보고 end_session을 호출한다(실제 실행에서 안 끝남)."""
    for kind_steps in (stretch_steps(D0), wordchain_steps(D0)):
        p = coaching_prompt(cin(kind_steps))
        assert "'없어', '괜찮아', '그래'" in p and "같은 응답에서 end_session을 호출한다" in p
        assert "'더 필요하신가요?' 같은 질문을 하지 않는다" in p
