"""Live 연결 설정 조립. 서버가 이 값을 임시 토큰에 고정하고, 앱은 토큰으로 직접 연결한다."""
from datetime import datetime, timedelta, timezone

from .inputs import BriefingInput, CoachingInput, NightInput
from .prompts.briefing import briefing_prompt
from .prompts.coaching import coaching_prompt
from .prompts.night import night_prompt
from .tools import tools_for

LIVE_MODEL = "gemini-3.8-live"

# RS_USER_SETTING.VOICE_TYPE → Live 기본 제공 목소리. 이름과 성별 인상은 가정이며 귀로 확인하지 않았다.
VOICES = {"FEMALE": "Kore", "MALE": "Puck", "NEUTRAL": "Zephyr"}

# 앱이 연결 직후 첫 입력으로 보내는 글자. 사용자가 말한 것이 아니라 시작 신호다.
START_SIGNALS = {
    "night": "대화를 시작해 주세요.",
    "briefing": "브리핑을 시작해 주세요. 먼저 확인 질문 하나만 하고, 사용자가 대답할 때까지 멈춰서 기다려 주세요.",
    # 실험 E: 신호가 짧으면 AI가 사용자 답을 듣기 전에 1번 단계를 완료로 기록하고 2번 단계까지 안내했다
    "coaching": "코칭을 시작해 주세요. 1번 단계만 안내하고, 사용자가 대답할 때까지 멈춰서 기다려 주세요. 아직 아무것도 기록하지 마세요.",
}


def start_signal(kind: str) -> str:
    return START_SIGNALS[kind]


def build_setup(kind: str, inp) -> dict:
    """kind: night / briefing / coaching. 토큰의 bidiGenerateContentSetup에 그대로 넣는 값."""
    prompt = {"night": night_prompt, "briefing": briefing_prompt, "coaching": coaching_prompt}[kind](inp)
    return {
        "model": f"models/{LIVE_MODEL}",
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": VOICES.get(inp.persona.voice_type, VOICES["FEMALE"])}}},
        },
        "systemInstruction": {"parts": [{"text": prompt}]},
        "tools": tools_for(kind, inp),
        "inputAudioTranscription": {},
        "outputAudioTranscription": {},
        "sessionResumption": {},
    }


def token_request_body(setup: dict, now: datetime | None = None, session_minutes: int = 30, start_within_min: int = 2) -> dict:
    """POST /v1alpha/auth_tokens 본문 (실험 D에서 확인한 형식). 한 번만 쓸 수 있는 토큰이다."""
    now = now or datetime.now(timezone.utc)
    iso = lambda m: (now + timedelta(minutes=m)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {"uses": 1, "expireTime": iso(session_minutes), "newSessionExpireTime": iso(start_within_min),
            "bidiGenerateContentSetup": setup}
