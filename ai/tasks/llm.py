"""서버가 부르는 AI 함수용 텍스트 생성 호출 (Live가 아닌 generateContent REST).

10/29 시연에서 나온 문제를 반영한다: 과부하(503) 때 다른 모델로 넘어가고, 응답의 parts를 전부 이어 붙이고,
추론을 최소로 줄이고, 총 시간 상한을 둔다.
"""
import json
import os
import re
import time
import urllib.error
import urllib.request

# 위에서부터 시도한다. 환경변수 GEMINI_TEXT_MODELS(쉼표로 구분)로 바꿀 수 있다.
DEFAULT_MODELS = ("gemini-3.5-flash-lite", "gemini-3-flash-preview")
RETRYABLE = {429, 500, 502, 503, 504}
URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class LLMError(Exception):
    """호출 실패 또는 응답 형식 오류. 호출한 쪽이 재시도하거나 FAILED로 처리한다."""


class HTTPFailure(Exception):
    def __init__(self, status: int, text: str = ""):
        super().__init__(f"HTTP {status}")
        self.status, self.text = status, text


def load_env(path: str) -> None:
    """ai/.env를 읽어 환경변수로 넣는다 (이미 있는 값은 덮어쓰지 않는다). 서버는 자체 환경변수를 쓰므로 선택 사항."""
    try:
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())
    except OSError:
        pass


def parse_json(text: str) -> dict:
    """응답 글자에서 JSON 객체 하나를 꺼낸다 (코드 펜스나 앞뒤 설명이 붙어도 허용)."""
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", (text or "").strip())
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end <= start:
        raise LLMError("JSON 객체가 없어요")
    try:
        data = json.loads(t[start:end + 1])
    except json.JSONDecodeError as e:
        raise LLMError(f"JSON 형식 오류: {e}") from e
    if not isinstance(data, dict):
        raise LLMError("JSON 객체가 아니에요")
    return data


def response_text(resp: dict) -> str:
    """candidates[0]의 모든 text 부분을 이어 붙인다 (parts[0]만 읽으면 일부가 빠질 수 있다). 생각(thought) 부분은 뺀다."""
    cands = resp.get("candidates") or []
    if not cands:
        raise LLMError(f"응답 후보가 없어요 (차단 등): {str(resp.get('promptFeedback', ''))[:100]}")
    parts = (cands[0].get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    if not text.strip():
        raise LLMError(f"빈 응답 (finishReason={cands[0].get('finishReason')})")
    return text


def _http_transport(api_key: str, timeout: float):
    def call(model: str, body: dict) -> dict:
        req = urllib.request.Request(URL.format(model=model), data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", "x-goog-api-key": api_key})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            raise HTTPFailure(e.code, e.read().decode("utf-8", "replace")[:300]) from None
    return call


class GeminiText:
    """generate_json(system, user) -> dict. 테스트에서는 transport(model, body)->응답 dict를 바꿔 끼운다."""

    def __init__(self, api_key: str | None = None, models=None, timeout: float = 12.0, deadline: float = 30.0,
                 thinking_level: str | None = "minimal", transport=None):
        env_models = [m.strip() for m in os.environ.get("GEMINI_TEXT_MODELS", "").split(",") if m.strip()]
        self.models = tuple(models or env_models or DEFAULT_MODELS)
        self.deadline, self.thinking_level = deadline, thinking_level
        if transport is None:
            key = api_key or os.environ.get("GEMINI_API_KEY", "")
            if not key:
                raise LLMError("GEMINI_API_KEY가 없어요")
            transport = _http_transport(key, timeout)
        self.transport = transport
        self.calls = 0  # 호출 횟수 (호출 수를 줄였는지 확인하는 용도)

    def _body(self, system: str, user: str, thinking: str | None) -> dict:
        cfg = {"responseMimeType": "application/json", "temperature": 0.4}
        if thinking:
            cfg["thinkingConfig"] = {"thinkingLevel": thinking}
        return {"system_instruction": {"parts": [{"text": system}]},
                "contents": [{"role": "user", "parts": [{"text": user}]}], "generationConfig": cfg}

    def generate_json(self, system: str, user: str) -> dict:
        start, last = time.monotonic(), None
        for model in self.models:
            thinking = self.thinking_level
            for _ in range(2):  # 추론 설정을 거부하면(400) 설정 없이 한 번 더
                if time.monotonic() - start > self.deadline:
                    raise LLMError(f"시간 초과 ({self.deadline:g}초): {last}")
                self.calls += 1
                try:
                    resp = self.transport(model, self._body(system, user, thinking))
                except HTTPFailure as e:
                    last = f"{model} {e}"
                    if e.status == 400 and thinking:
                        thinking = None
                        continue
                    if e.status in RETRYABLE:
                        break  # 다음 모델로
                    raise LLMError(f"{model} 호출 실패: HTTP {e.status} {e.text[:120]}") from None
                except (TimeoutError, urllib.error.URLError, OSError) as e:
                    last = f"{model} {type(e).__name__}"
                    break
                return parse_json(response_text(resp))
        raise LLMError(f"모든 모델 호출 실패: {last}")
