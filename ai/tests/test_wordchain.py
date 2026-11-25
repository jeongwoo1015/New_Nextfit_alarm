"""끝말잇기 글자 판정 테스트 (Gemini 호출 없음): 규칙 검사, 두음법칙, 넥스티 단어 검사·재시도, 이을 단어가 없을 때, 실패 처리, 출력 JSON 모양."""
import pytest

from ai.tasks.llm import LLMError
from ai.tasks.wordchain import check_rules, judge_wordchain, start_letters


class FakeLLM:
    def __init__(self, *results):
        self.results, self.calls, self.users = list(results), 0, []

    def generate_json(self, system, user):
        self.calls += 1
        self.users.append(user)
        r = self.results.pop(0) if len(self.results) > 1 else self.results[0]
        if isinstance(r, Exception):
            raise r
        return r


OK = {"valid": True, "reason": "", "next": "표정"}


# ---------- 두음법칙 ----------
@pytest.mark.parametrize("last,expected", [
    ("리", ["리", "이"]), ("라", ["라", "나"]), ("녀", ["녀", "여"]), ("니", ["니", "이"]), ("로", ["로", "노"]), ("류", ["류", "유"]),
    ("기", ["기"]), ("차", ["차"]), ("내", ["내"]), ("여", ["여"]), ("A", []), ("", []),
])
def test_start_letters_with_initial_sound_rule(last, expected):
    assert start_letters(last) == expected


# ---------- 코드로 거르는 규칙 (AI를 부르지 않는다) ----------
@pytest.mark.parametrize("words,word,expect", [
    (["기차"], "차표", ""),
    (["기차", "차리"], "이불", ""),
    (["기차", "차리"], "리본", ""),
    (["기차"], "표", "두 글자 이상"),
    (["기차"], "abc", "한글 단어만"),
    (["기차"], "차 표", "한글 단어만"),            # 공백은 호출 쪽(judge)에서 지운다. check_rules는 정리된 글자를 받는다
    (["기차", "차표"], "차표", "이미 나온"),
    (["기차"], "나무", "'차'(으)로"),
    (["기차", "차리"], "허브", "'리' 또는 '이'"),
])
def test_check_rules(words, word, expect):
    got = check_rules(words, word)
    assert (expect in got) if expect else got == ""


def test_rule_violation_skips_the_llm():
    llm = FakeLLM(OK)
    for words, word in ((["기차"], "나무"), (["기차"], "차"), (["기차", "차표"], "차표"), (["기차"], "")):
        r = judge_wordchain(words, word, llm)
        assert r.valid is False and r.reason and r.status == "DONE"
    assert llm.calls == 0


# ---------- 정상 흐름 ----------
def test_valid_word_returns_next_word():
    llm = FakeLLM({"valid": True, "reason": "", "next": "정원"})
    r = judge_wordchain(["기차", "차표"], "표정", llm)
    assert r.to_dict() == {"valid": True, "next": "정원"} and llm.calls == 1
    assert "기차, 차표" in llm.users[0] and "표정" in llm.users[0] and "[이을 수 있는 시작 글자] 정" in llm.users[0]


def test_spaces_are_ignored_and_initial_sound_rule_is_allowed():
    llm = FakeLLM({"valid": True, "reason": "", "next": "마을"})
    r = judge_wordchain([" 기 차", "차 리"], " 이 마 ", llm)           # 앞 단어가 '리'로 끝나면 '이'로 시작해도 된다
    assert r.next == "마을" and llm.calls == 1
    assert "기차, 차리" in llm.users[0] and "이마" in llm.users[0] and "이 마" not in llm.users[0]


def test_initial_sound_rule_for_next_word():
    llm = FakeLLM({"valid": True, "reason": "", "next": "이불"})
    r = judge_wordchain(["기차", "차머"], "머리", llm)               # 머리 → 리/이로 이을 수 있다
    assert r.to_dict() == {"valid": True, "next": "이불"} and llm.calls == 1
    assert "[이을 수 있는 시작 글자] 리, 이" in llm.users[0]
    llm = FakeLLM({"valid": True, "reason": "", "next": "리본"})
    assert judge_wordchain(["기차", "차머"], "머리", llm).next == "리본"


# ---------- 틀린 단어 ----------
def test_invalid_word_gives_short_reason():
    llm = FakeLLM({"valid": False, "reason": "사전에 없는 말이에요", "next": None})
    r = judge_wordchain(["기차"], "차랑이", llm)
    assert r.to_dict() == {"valid": False, "reason": "사전에 없는 말이에요"} and r.next is None


def test_invalid_reason_is_trimmed_and_defaulted():
    r = judge_wordchain(["기차"], "차랑이", FakeLLM({"valid": False, "reason": "  " + "가" * 80, "next": None}))
    assert len(r.reason) == 40
    assert judge_wordchain(["기차"], "차랑이", FakeLLM({"valid": False})).reason == "사전에 없는 말이에요"


def test_next_is_ignored_when_word_is_invalid():
    r = judge_wordchain(["기차"], "차랑이", FakeLLM({"valid": False, "reason": "사전에 없는 말이에요", "next": "랑데부"}))
    assert r.valid is False and r.next is None and "next" not in r.to_dict()


# ---------- 넥스티 단어 검사와 재시도 ----------
@pytest.mark.parametrize("bad", ["나무", "표", "기차", "표정", "pyo", "표 정 판"])
def test_illegal_next_word_is_asked_again(bad):
    llm = FakeLLM({"valid": True, "reason": "", "next": bad}, {"valid": True, "reason": "", "next": "정원"})
    r = judge_wordchain(["기차", "차표"], "표정", llm)
    assert r.next == "정원" and llm.calls == 2
    assert "[다시 확인]" in llm.users[1] and "쓸 수 없어요" in llm.users[1]


def test_next_word_that_never_becomes_legal_fails():
    llm = FakeLLM({"valid": True, "reason": "", "next": "나무"})
    r = judge_wordchain(["기차", "차표"], "표정", llm)
    assert r.status == "FAILED" and r.to_dict() == {"status": "FAILED"} and llm.calls == 3


def test_next_word_cannot_repeat_the_users_word_or_earlier_words():
    llm = FakeLLM({"valid": True, "reason": "", "next": "정정"}, {"valid": True, "reason": "", "next": "정원"})
    assert judge_wordchain(["기차", "차정"], "정정", llm).next == "정원"       # 정정 = 방금 사용자가 낸 단어
    llm = FakeLLM({"valid": True, "reason": "", "next": "말투"}, {"valid": True, "reason": "", "next": "말씨"})
    r = judge_wordchain(["말투", "투정"], "정말", llm)                         # 말투는 앞에서 이미 나온 단어
    assert r.next == "말씨" and llm.calls == 2


# ---------- 이을 단어가 없을 때 ----------
def test_no_next_word_is_confirmed_by_asking_once_more():
    llm = FakeLLM({"valid": True, "reason": "", "next": None})
    r = judge_wordchain(["기차", "차표"], "표정", llm)
    assert r.to_dict() == {"valid": True, "next": None} and llm.calls == 2          # 한 번 더 찾아 보게 한 뒤 그래도 없으면 없는 것
    assert "[다시 확인]" in llm.users[1] and "정말" in llm.users[1]


def test_no_next_word_first_but_found_on_second_try():
    llm = FakeLLM({"valid": True, "reason": "", "next": None}, {"valid": True, "reason": "", "next": "정원"})
    assert judge_wordchain(["기차", "차표"], "표정", llm).next == "정원"


def test_empty_string_next_counts_as_none():
    assert judge_wordchain(["기차"], "차표", FakeLLM({"valid": True, "next": ""})).next is None


# ---------- 호출 실패 ----------
def test_llm_failure_gives_failed_not_a_wrong_answer():
    llm = FakeLLM(LLMError("시간 초과"))
    r = judge_wordchain(["기차"], "차표", llm)
    assert r.status == "FAILED" and r.valid is None and "시간 초과" in r.note and r.to_dict() == {"status": "FAILED"}
    assert llm.calls == 2                       # 호출 실패는 두 번까지만(사용자가 기다리는 중)


def test_one_failure_then_success():
    llm = FakeLLM(LLMError("503"), {"valid": True, "reason": "", "next": "정원"})
    assert judge_wordchain(["기차", "차표"], "표정", llm).next == "정원" and llm.calls == 2


def test_bad_valid_value_is_retried_then_succeeds():
    llm = FakeLLM({"valid": "yes"}, {"valid": True, "reason": "", "next": "정원"})
    r = judge_wordchain(["기차", "차표"], "표정", llm)
    assert r.next == "정원" and llm.calls == 2


def test_failure_after_a_valid_null_answer_is_failed():
    llm = FakeLLM({"valid": True, "next": None}, LLMError("503"))
    assert judge_wordchain(["기차"], "차표", llm).status == "FAILED"


def test_first_word_with_empty_history():
    llm = FakeLLM({"valid": True, "reason": "", "next": "정원"})
    assert judge_wordchain([], "표정", llm).next == "정원"
    assert check_rules([], "표") != ""
