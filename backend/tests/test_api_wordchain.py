"""끝말잇기 글자 판정 POST /wordchain/turn (AI CONTRACTS B26, 앱 RespecApi.wordChainTurn)."""
import pytest

from backend.clients.ai_gateway import RealAIGateway

from .conftest import USER_A, auth

H = auth(USER_A)
BODY = {"words": ["기차", "차표"], "word": "표정"}


@pytest.mark.parametrize("result", [{"valid": True, "next": "정원"}, {"valid": True, "next": None},
                                    {"valid": False, "reason": "사전에 없는 말이에요"}])
def test_turn_returns_ai_result(client, ai, result):
    ai.wordchain = result
    r = client.post("/wordchain/turn", json=BODY, headers=H)
    assert r.status_code == 200
    assert r.json() == {"valid": result["valid"], "next": result.get("next"), "reason": result.get("reason")}
    assert ai.calls[-1] == ("wordchain", (["기차", "차표"], "표정"))


def test_failed_is_503_not_pass(client, ai):
    ai.wordchain = {"status": "FAILED"}
    r = client.post("/wordchain/turn", json=BODY, headers=H)
    assert r.status_code == 503 and r.json()["error"]["code"] == "WORDCHAIN_FAILED"


def test_input_checks(client):
    assert client.post("/wordchain/turn", json=BODY).status_code == 401
    for bad in ({"words": ["기차"]}, {"words": [], "word": ""}, {"words": ["기" * 21], "word": "차표"},
                {"words": ["기차"] * 301, "word": "차표"}):
        assert client.post("/wordchain/turn", json=bad, headers=H).status_code == 422
    assert client.post("/wordchain/turn", json={"word": "기차"}, headers=H).status_code == 200   # 첫 단어(이어진 단어 없음)


class ScriptedLLM:
    """정해 둔 응답을 순서대로 준다. 예외를 넣으면 그 예외를 던진다."""

    def __init__(self, *replies):
        self.replies, self.calls = list(replies), 0

    def generate_json(self, system, prompt):
        self.calls += 1
        r = self.replies.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def test_real_gateway_calls_ai_wordchain():
    llm = ScriptedLLM({"valid": True, "next": "정원"})
    assert RealAIGateway("k", llm=llm).judge_wordchain(["기차", "차표"], "표정") == {"valid": True, "next": "정원"}
    llm = ScriptedLLM()
    res = RealAIGateway("k", llm=llm).judge_wordchain(["기차", "차표"], "기린")       # 끝 글자 규칙 위반은 AI를 안 부름
    assert res["valid"] is False and res["reason"] and llm.calls == 0


def test_real_gateway_unexpected_error_is_failed():
    llm = ScriptedLLM(RuntimeError("network"), RuntimeError("network"))
    assert RealAIGateway("k", llm=llm).judge_wordchain(["기차"], "차표") == {"status": "FAILED"}
