"""앱 대역 PC 클라이언트: 서버가 하는 일(지시문 생성·임시 토큰 발급)과 앱이 하는 일(토큰으로 Live 연결, 시작 신호,
음성 전송, 함수 호출 처리, 받아쓰기 수집)을 한 번에 흉내 낸다. 수집한 원문은 정의서 RS_NIGHT_RAW.TRANSCRIPT 형식이다.
"""
import base64
import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from websockets.sync.client import connect

from ..live.config import LIVE_MODEL, build_setup, start_signal, token_request_body
from . import tts

AUTH_URL = "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
WS_URL = ("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha."
          "GenerativeService.BidiGenerateContentConstrained?access_token={name}")
KST = timezone(timedelta(hours=9))


class DevClientError(Exception):
    pass


@dataclass
class TurnLog:
    label: str                     # "시작" 또는 사용자가 보낸 말
    heard: str = ""                # AI가 받아쓴 사용자 말 (시작 신호에는 없음)
    said: str = ""                 # AI가 한 말(출력 받아쓰기)
    calls: list = field(default_factory=list)   # [{"name":..., "args":...}]
    first_audio: float | None = None
    seconds: float = 0.0


@dataclass
class SessionLog:
    kind: str
    turns: list = field(default_factory=list)
    transcript: list = field(default_factory=list)   # [{role, text, at}]  (정의서 RS_NIGHT_RAW.TRANSCRIPT)
    started_at: str = ""
    ended_at: str = ""
    ended_by: str = ""             # end_session / turnComplete
    error: str = ""

    def calls(self, name: str | None = None) -> list:
        return [c for t in self.turns for c in t.calls if name is None or c["name"] == name]


def _now() -> str:
    return datetime.now(KST).isoformat(timespec="seconds")


class DevClient:
    def __init__(self, api_key: str | None = None, synth=tts.synthesize, first_timeout: float = 40.0, drain: float = 2.0):
        self.key = api_key or os.environ.get("GEMINI_API_KEY", "")
        if not self.key:
            raise DevClientError("GEMINI_API_KEY가 없어요")
        self.synth, self.first_timeout, self.drain = synth, first_timeout, drain

    def scrub(self, msg) -> str:
        return str(msg).replace(self.key, "***")

    # ----- 서버 역할 -----
    def _create_token(self, setup: dict) -> str:
        req = urllib.request.Request(AUTH_URL, data=json.dumps(token_request_body(setup)).encode(),
                                     headers={"Content-Type": "application/json", "x-goog-api-key": self.key})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)["name"]
        except urllib.error.HTTPError as e:
            raise DevClientError(self.scrub(f"토큰 발급 실패 HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}")) from None

    # ----- 앱 역할 -----
    def run(self, kind: str, inp, utterances=()) -> SessionLog:
        """kind: night/briefing/coaching. utterances: 가상 사용자가 차례로 하는 말(합성 음성으로 보낸다)."""
        log = SessionLog(kind=kind, started_at=_now())
        setup = build_setup(kind, inp)
        url = WS_URL.format(name=self._create_token(setup))
        try:
            with connect(url, max_size=None, open_timeout=20) as ws:
                ws.send(json.dumps({"setup": {"model": f"models/{LIVE_MODEL}"}}))   # 앱은 모델만 보내고 나머지는 토큰에 고정된 값을 쓴다
                if "setupComplete" not in json.loads(ws.recv(timeout=30)):
                    raise DevClientError("setupComplete가 오지 않았어요")
                self._turn(ws, log, "시작", text=start_signal(kind))
                for u in utterances:
                    if log.ended_by == "end_session":
                        break
                    self._turn(ws, log, u, audio=u)
        except DevClientError:
            raise
        except Exception as e:  # 연결 오류는 로그에 남기고 규칙 검사에서 실패로 보이게 한다
            log.error = self.scrub(f"{type(e).__name__}: {e}")[:300]
        log.ended_at = _now()
        if not log.ended_by:
            log.ended_by = "turnComplete"
        return log

    def _send_audio(self, ws, pcm: bytes) -> None:
        for k in range(0, len(pcm), 3200):
            ws.send(json.dumps({"realtimeInput": {"audio": {"mimeType": "audio/pcm;rate=16000",
                                                            "data": base64.b64encode(pcm[k:k + 3200]).decode()}}}))
            time.sleep(0.01)
        ws.send(json.dumps({"realtimeInput": {"audioStreamEnd": True}}))

    def _turn(self, ws, log: SessionLog, label: str, text: str | None = None, audio: str | None = None) -> None:
        if audio is not None:
            self._send_audio(ws, self.synth(audio))
        else:
            ws.send(json.dumps({"realtimeInput": {"text": text}}))
        t0 = time.monotonic()
        turn = TurnLog(label=label if audio is not None else "(시작 신호)")
        done = False
        while True:
            try:
                m = json.loads(ws.recv(timeout=self.drain if done else (self.first_timeout if not turn.said and not turn.calls else 60)))
            except TimeoutError:
                break
            sc = m.get("serverContent")
            if sc:
                for p in (sc.get("modelTurn") or {}).get("parts", []):
                    if p.get("inlineData") and turn.first_audio is None:
                        turn.first_audio = time.monotonic() - t0
                if sc.get("inputTranscription"):
                    turn.heard += sc["inputTranscription"].get("text", "")
                if sc.get("outputTranscription"):
                    turn.said += sc["outputTranscription"].get("text", "")
                if sc.get("turnComplete"):
                    done = True
            if m.get("toolCall"):
                replies = []
                for c in m["toolCall"].get("functionCalls", []):
                    turn.calls.append({"name": c.get("name"), "args": c.get("args") or {}})
                    if c.get("name") == "end_session":   # 앱은 응답하지 않고 마무리 음성이 끝나면 연결을 닫는다
                        log.ended_by = "end_session"
                        continue
                    replies.append({"id": c.get("id"), "name": c.get("name"), "response": {"result": "ok"}})
                if replies:
                    ws.send(json.dumps({"toolResponse": {"functionResponses": replies}}))
                    done = False
                else:
                    done = True
        turn.seconds = time.monotonic() - t0
        log.turns.append(turn)
        at = _now()
        if turn.heard.strip():
            log.transcript.append({"role": "user", "text": turn.heard.strip(), "at": at})
        if turn.said.strip():
            log.transcript.append({"role": "assistant", "text": turn.said.strip(), "at": at})
